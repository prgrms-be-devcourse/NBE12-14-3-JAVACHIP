package com.budzet.domain.budget.service

import com.budzet.domain.budget.dto.*
import com.budzet.domain.budget.entity.BudgetChange
import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.entity.BudgetType
import com.budzet.domain.budget.repository.BudgetChangeRepository
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.room.entity.Authority
import com.budzet.domain.user.repository.UserRepository
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import jakarta.persistence.LockTimeoutException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BudgetChangeService(
    private val budgetChangeRepository: BudgetChangeRepository,
    private val budgetRequestRepository: BudgetRequestRepository,
    private val budgetService: BudgetService,
    private val userRepository: UserRepository,
) {
    @Retryable(
        retryFor = [PessimisticLockingFailureException::class, LockTimeoutException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 500),
    )
    @Transactional
    fun createBudgetChange(
        roomId: Long,
        userId: Long,
        requestId: Long,
        createRequest: BudgetChangeCreateRequest,
    ): BudgetChangeCreateResponse {
        val room = budgetService.findByRoomIdWithLock(roomId)
        val connection = budgetService.validateRoomMember(roomId, userId)
        val request = budgetRequestRepository.findByIdOrNull(requestId)
            ?: throw BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND)
        if (request.room.id != roomId) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "해당 모임의 신청건이 아닙니다.")
        }
        // 해당 신청의 신청자인지 확인
        if (request.user.id != userId) {
            throw BusinessException(ErrorCode.FORBIDDEN_REQUEST_WRITER)
        }
        // 승인된 요청이 아닐 때 예외처리
        if (request.status != BudgetRequestType.APPROVE) {
            throw BusinessException(ErrorCode.BUDGET_REQUEST_NOT_APPROVED)
        }

        val requestedAmount = requireNotNull(request.requestedAmount)
        val changedBudget = createRequest.changedBudget
            ?: throw BusinessException(
                ErrorCode.BAD_REQUEST,
                "입력된 금액이 올바르지 않습니다.",
            )
        val budgetChange = BudgetChange(
            room = room,
            user = connection.user,
            userName = connection.user.name,
            type = BudgetType.SETTLEMENT,
            reason = createRequest.reason,
            changeBudget = requestedAmount,
            changedBudget = changedBudget,
        )
        budgetChange.addBudgetRequest(request)
        // 신청 상태 변경: 승인 -> 정산
        request.changeToSettlement()
        room.settleBudget(requireNotNull(budgetChange.changeBudget), budgetChange.changedBudget)
        room.addBudgetChange(budgetChange)
        budgetChangeRepository.save(budgetChange)
        return BudgetChangeCreateResponse.from(budgetChange)
    }

    @Transactional(readOnly = true)
    fun budgetChangeList(roomId: Long, userId: Long): BudgetChangeListResponse {
        budgetService.validateRoomMember(roomId, userId)
        val budgetChanges = budgetChangeRepository.findAllByRoomIdAndTypeOrderByCreatedAtDesc(
            roomId, BudgetType.SETTLEMENT,
        )
        return BudgetChangeListResponse.from(budgetChanges)
    }

    @Transactional(readOnly = true)
    fun budgetChangeDetail(roomId: Long, userId: Long, changeId: Long): BudgetChangeDetailResponse {
        budgetService.validateRoomMember(roomId, userId)
        val budgetChange = budgetChangeRepository.findByIdOrNull(changeId)
            ?: throw BusinessException(ErrorCode.BUDGET_CHANGE_NOT_FOUND)
        if (budgetChange.room.id != roomId) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "해당 모임의 정산건이 아닙니다.")
        }
        return BudgetChangeDetailResponse.from(budgetChange)
    }

    @Retryable(
        retryFor = [PessimisticLockingFailureException::class, LockTimeoutException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 500),
    )
    @Transactional
    fun updateBudgetChange(
        roomId: Long,
        userId: Long,
        changeId: Long,
        updateRequest: BudgetChangeUpdateRequest,
    ): BudgetChangeUpdateResponse {
        val connection = budgetService.validateRoomMember(roomId, userId)
        if (connection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.FORBIDDEN_ACCESS)
        }
        val userName = requireNotNull(updateRequest.userName)
        val userEmail = requireNotNull(updateRequest.userEmail)
        val user = userRepository.findByNameAndEmail(
            userName, userEmail,
        )?: throw BusinessException(ErrorCode.BAD_REQUEST, "유저 이름과 이메일이 일치하지 않습니다.")
        budgetService.validateRoomMember(roomId, requireNotNull(user.id))
        val budgetChange = budgetChangeRepository.findByIdOrNull(changeId)
            ?: throw BusinessException(ErrorCode.BUDGET_CHANGE_NOT_FOUND)
        if (budgetChange.type != BudgetType.SETTLEMENT) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "정산(SETTLEMENT) 타입의 내역만 수정할 수 있습니다.")
        }
        if (budgetChange.room.id != roomId) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "유효한 정산내역이 아닙니다.")
        }
        val room = budgetService.findByRoomIdWithLock(roomId)
        val changeBudget = requireNotNull(updateRequest.changeBudget)
        // 신청 금액 증가분이 현재 가용예산을 초과할 수 없다.
        val resultAmount = changeBudget - budgetChange.changeBudget
        if (resultAmount > 0 && requireNotNull(room.availableBudget) < resultAmount) {
            throw BusinessException(ErrorCode.REQUEST_AMOUNT_OVER_BUDGET)
        }
        val changedBudget = requireNotNull(updateRequest.changedBudget)
        val reason = requireNotNull(updateRequest.reason)
        // 수정 전 정산 금액 - 수정 후 정산 금액의 차액을 실제·가용예산에 반영한다.
        val resultChanged = budgetChange.changedBudget - changedBudget
        room.updateTotalBudget(resultChanged)
        budgetChange.updateChange(userName, changeBudget, changedBudget, reason, user)
        budgetChange.request?.updateRequest(budgetChange, user)
        return BudgetChangeUpdateResponse.from(budgetChange)
    }
}
