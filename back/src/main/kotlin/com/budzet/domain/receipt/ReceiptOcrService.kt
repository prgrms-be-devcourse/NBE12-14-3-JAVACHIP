package com.budzet.domain.receipt

import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.room.entity.Currency
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Clock
import java.time.LocalDate

@Component
class ReceiptOcrLimiter(private val clock: Clock = Clock.systemUTC()) {
    private var day = LocalDate.now(clock)
    private var calls = 0
    private val users = mutableMapOf<Long, Long>()

    @Synchronized
    fun acquire(userId: Long) {
        val today = LocalDate.now(clock)
        if (day != today) { day = today; calls = 0; users.clear() }
        val now = clock.millis()
        if (calls >= 400 || users[userId]?.let { now - it < 30_000 } == true) {
            throw BusinessException(ErrorCode.OCR_LIMIT_REACHED)
        }
        calls++
        users[userId] = now
    }
}

data class ReceiptOcrResponse(val amount: Long?, val message: String)

@Service
class ReceiptOcrService(
    private val requests: BudgetRequestRepository,
    private val rooms: RoomRepository,
    private val members: UserRoomConnectionRepository,
    private val client: ReceiptOcrClient,
    private val parser: ReceiptAmountParser,
    private val limiter: ReceiptOcrLimiter,
) {
    // Repository calls finish before the external API request; no long DB transaction.
    fun analyze(roomId: Long, requestId: Long, userId: Long, file: MultipartFile): ReceiptOcrResponse {
        if (members.findByUser_IdAndRoom_Id(userId, roomId) == null) throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        val request = requests.findDtoById(roomId, requestId)
            ?: throw BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND)
        if (request.userId != userId) throw BusinessException(ErrorCode.FORBIDDEN_REQUEST_WRITER)
        if (request.status != BudgetRequestType.APPROVE) throw BusinessException(ErrorCode.BUDGET_REQUEST_NOT_APPROVED)
        // First release supports whole-won Korean receipts only; never mix currencies.
        if (rooms.findById(roomId).orElseThrow { BusinessException(ErrorCode.ROOM_NOT_FOUND) }.currency != Currency.KRW) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "영수증 인식은 원화 모임에서만 지원합니다.")
        }
        val bytes = validateImage(file)
        limiter.acquire(userId)
        val text = client.recognize(bytes, requireNotNull(file.contentType))
        val amount = parser.parse(text)
        return ReceiptOcrResponse(amount, if (amount == null) "총 결제 금액을 확실하게 찾지 못했습니다. 직접 입력해 주세요."
            else "인식된 금액을 영수증과 비교한 뒤 적용해 주세요.")
    }

    internal fun validateImage(file: MultipartFile): ByteArray = ReceiptImageValidator.validate(file)
}
