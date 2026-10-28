package com.budzet.domain.receipt

import com.budzet.domain.budget.entity.BudgetRequest
import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.repository.BudgetChangeRepository
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.room.entity.Currency
import com.budzet.domain.room.entity.Room
import com.budzet.domain.room.entity.UserRoomConnection
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.domain.user.service.UserService
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class ReceiptEvidenceIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: UserService
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var members: UserRoomConnectionRepository
    @Autowired lateinit var requests: BudgetRequestRepository
    @Autowired lateinit var changes: BudgetChangeRepository
    @Autowired lateinit var evidence: ReceiptEvidenceRepository
    private lateinit var room: Room
    private lateinit var request: BudgetRequest
    private lateinit var token: String
    private lateinit var outsider: String
    private lateinit var image: ByteArray
    @BeforeEach fun setup() {
        val owner = users.join("${UUID.randomUUID()}@test.com", "password123", "OCR 작성자")
        val other = users.join("${UUID.randomUUID()}@test.com", "password123", "외부인")
        token = users.login(owner.email, "password123").accessToken
        outsider = users.login(other.email, "password123").accessToken
        room = rooms.save(Room.create("OCR 증빙 테스트",100000,Currency.KRW))
        members.save(UserRoomConnection.createOwner(owner,room))
        request = BudgetRequest(room,owner,"식비",20000)
        request.approveRequest()
        requests.saveAndFlush(request)
        room.updateAvailableBudget(-20000)
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out)
        image = out.toByteArray()
    }
    private fun upload(auth: String = token, amount: Long = 12300, bytes: ByteArray = image) =
        multipart("/rooms/${room.id}/budget/changes/${request.id}/with-receipt")
            .file(MockMultipartFile("file","receipt.png","image/png",bytes))
            .file(MockMultipartFile("request","request.json","application/json","""{"changedBudget":$amount,"reason":"팀 회의 식비"}""".toByteArray()))
            .header("Authorization","Bearer $auth")

    @Test fun savesProofAndSettlementTogetherAndMemberCanReadOriginal() {
        val result=mvc.perform(upload()).andExpect(status().isCreated).andReturn()
        val id=JsonMapper.builder().build().readTree(result.response.contentAsString).path("data").path("id").asLong()
        assertArrayEquals(image,evidence.findByChange_Id(id)!!.image)
        assertEquals(87700L,rooms.findById(room.id!!).orElseThrow().totalBudget)
        assertEquals(BudgetRequestType.SETTLEMENT,requests.findById(request.id!!).orElseThrow().status)
        mvc.perform(get("/rooms/${room.id}/budget/changes/$id/receipt").header("Authorization","Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.contentType").value("image/png"))
            .andExpect(jsonPath("$.data.base64").value(java.util.Base64.getEncoder().encodeToString(image)))
        mvc.perform(get("/rooms/${room.id}/budget/changes/$id/receipt").header("Authorization","Bearer $outsider"))
            .andExpect(status().isForbidden)
    }
    @Test fun anonymousCannotStoreProof() {
        mvc.perform(multipart("/rooms/${room.id}/budget/changes/${request.id}/with-receipt").file(MockMultipartFile("file",image)))
            .andExpect(status().isUnauthorized)
        assertEquals(0,evidence.count())
    }
    @Test fun nonMemberCannotSettleWithProof() {
        mvc.perform(upload(outsider)).andExpect(status().isForbidden)
        assertEquals(0,evidence.count())
        assertEquals(BudgetRequestType.APPROVE,request.status)
    }
    @Test fun corruptImageDoesNotChangeMoney() {
        mvc.perform(upload(bytes="not an image".toByteArray())).andExpect(status().isBadRequest)
        assertEquals(100000L,room.totalBudget)
        assertEquals(80000L,room.availableBudget)
        assertEquals(0,evidence.count())
    }
    @Test fun overApprovedAmountRollsBackAndDoesNotStoreProof() {
        mvc.perform(upload(amount=20001)).andExpect(status().isBadRequest)
        assertEquals(100000L,room.totalBudget)
        assertEquals(80000L,room.availableBudget)
        assertEquals(0,evidence.count())
        // Database state after rollback is authoritative (managed objects may retain dirty values).
    }
    @Test fun zeroAmountRejectedBeforeSettlement() {
        mvc.perform(upload(amount=0)).andExpect(status().isBadRequest)
        assertEquals(0,evidence.count())
    }
    @Test fun sameProofRequestCannotChargeTwice() {
        mvc.perform(upload()).andExpect(status().isCreated)
        mvc.perform(upload()).andExpect(status().isBadRequest)
        assertEquals(1,evidence.count())
        assertEquals(87700L,room.totalBudget)
    }
}
