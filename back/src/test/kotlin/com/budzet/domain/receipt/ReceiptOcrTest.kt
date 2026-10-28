package com.budzet.domain.receipt

import com.budzet.domain.budget.dto.BudgetRequestResponse
import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.room.entity.*
import com.budzet.domain.room.repository.*
import com.budzet.global.exception.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.Optional
import javax.imageio.ImageIO

class ReceiptAmountParserTest {
    private val parser = ReceiptAmountParser()
    @ParameterizedTest
    @ValueSource(strings = ["합계 12,300원", "총 결제 금액 : ₩12,300", "TOTAL 12300", "결제금액\n12,300"])
    fun extractsExplicitTotal(text: String) { assertEquals(12300L, parser.parse(text)) }
    @ParameterizedTest
    @ValueSource(strings = ["카드번호 123456789\n부가세 1000", "합계 12,300\n결제금액 14,000", "합계 -1000", "합계 12,34", "합계 0", "TOTAL 12.50", "합계 999999999999999999999", "소계 3000"])
    fun doesNotGuessAmbiguousOrInvalidAmounts(text: String) { assertNull(parser.parse(text)) }
}

class OcrSpaceClientTest {
    @Test fun parsesSuccessfulProviderResponse() {
        assertEquals("합계 12,300", OcrSpaceClient("").parseResponse("""{"OCRExitCode":1,"IsErroredOnProcessing":false,"ParsedResults":[{"ParsedText":"합계 12,300"}]}"""))
    }
    @ParameterizedTest
    @ValueSource(strings = ["""{"OCRExitCode":3,"IsErroredOnProcessing":true}""", """{"OCRExitCode":2,"ParsedResults":[{"ParsedText":"partial"}]}""", """{"OCRExitCode":1,"ParsedResults":[]}"""])
    fun rejectsFailedPartialAndEmptyResponses(json: String) {
        assertThrows(BusinessException::class.java) { OcrSpaceClient("").parseResponse(json) }
    }
    @Test fun missingKeyNeverSendsRequest() {
        assertEquals(ErrorCode.OCR_UNAVAILABLE, assertThrows(BusinessException::class.java) { OcrSpaceClient("").recognize(byteArrayOf(),"image/png") }.errorCode)
    }
}

class ReceiptOcrLimiterTest {
    @Test fun rejectsRapidDuplicateAndCapsDailyCalls() {
        val limiter = ReceiptOcrLimiter(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        limiter.acquire(1)
        assertThrows(BusinessException::class.java) { limiter.acquire(1) }
        for (id in 2L..400L) limiter.acquire(id)
        assertThrows(BusinessException::class.java) { limiter.acquire(401) }
    }
}

class ReceiptOcrServiceTest {
    private val requests = mock(BudgetRequestRepository::class.java)
    private val rooms = mock(RoomRepository::class.java)
    private val members = mock(UserRoomConnectionRepository::class.java)
    private val client = mock(ReceiptOcrClient::class.java)
    private val service = ReceiptOcrService(requests, rooms, members, client, ReceiptAmountParser(), ReceiptOcrLimiter())
    private fun fixture(userId: Long = 1, status: BudgetRequestType = BudgetRequestType.APPROVE) {
        `when`(members.findByUser_IdAndRoom_Id(1, 2)).thenReturn(mock(UserRoomConnection::class.java))
        `when`(requests.findDtoById(2, 3)).thenReturn(BudgetRequestResponse(3, 2, userId,"테스트","식비",20000,status,null,null,null))
        `when`(rooms.findById(2)).thenReturn(Optional.of(Room.create("테스트",30000,Currency.KRW)))
    }
    private fun image(): MockMultipartFile {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out)
        return MockMultipartFile("file","receipt.png","image/png",out.toByteArray())
    }
    @Test fun nonMemberCannotSendImage() {
        `when`(members.findByUser_IdAndRoom_Id(1,2)).thenReturn(null)
        assertEquals(ErrorCode.USER_NOT_JOINED_ROOM, assertThrows(BusinessException::class.java) { service.analyze(2,3,1,image()) }.errorCode)
        verifyNoInteractions(client)
    }
    @Test fun anotherApplicantCannotSendImage() {
        fixture(userId=9)
        assertEquals(ErrorCode.FORBIDDEN_REQUEST_WRITER, assertThrows(BusinessException::class.java) { service.analyze(2,3,1,image()) }.errorCode)
        verifyNoInteractions(client)
    }
    @Test fun unapprovedRequestCannotSendImage() {
        fixture(status=BudgetRequestType.REQUEST)
        assertEquals(ErrorCode.BUDGET_REQUEST_NOT_APPROVED, assertThrows(BusinessException::class.java) { service.analyze(2,3,1,image()) }.errorCode)
        verifyNoInteractions(client)
    }
    @Test fun missingRequestCannotSendImage() {
        fixture(); `when`(requests.findDtoById(2,3)).thenReturn(null)
        assertThrows(BusinessException::class.java) { service.analyze(2,3,1,image()) }
        verifyNoInteractions(client)
    }
    @Test fun spoofedImageIsRejectedBeforeProviderCall() {
        fixture()
        assertThrows(BusinessException::class.java) { service.analyze(2,3,1,MockMultipartFile("file","receipt.png","image/png","not an image".toByteArray())) }
        verifyNoInteractions(client)
    }
    @Test fun oversizedImageIsRejected() {
        fixture()
        assertThrows(BusinessException::class.java) { service.analyze(2,3,1,MockMultipartFile("file","receipt.png","image/png",ByteArray(1_000_001))) }
        verifyNoInteractions(client)
    }
    @Test fun returnsSuggestionWithoutChangingBudgets() {
        fixture()
        `when`(client.recognize(any(ByteArray::class.java) ?: byteArrayOf(),eq("image/png") ?: "")).thenReturn("카드번호 123456789\n합계 12,300원")
        assertEquals(12300L,service.analyze(2,3,1,image()).amount)
        verify(requests).findDtoById(2,3)
        verify(rooms).findById(2)
        verifyNoMoreInteractions(requests, rooms)
    }
    @Test fun providerFailureIsNotConvertedToSuccess() {
        fixture()
        `when`(client.recognize(any(ByteArray::class.java) ?: byteArrayOf(),eq("image/png") ?: "")).thenThrow(BusinessException(ErrorCode.OCR_UNAVAILABLE))
        assertEquals(ErrorCode.OCR_UNAVAILABLE,assertThrows(BusinessException::class.java) { service.analyze(2,3,1,image()) }.errorCode)
    }
}
