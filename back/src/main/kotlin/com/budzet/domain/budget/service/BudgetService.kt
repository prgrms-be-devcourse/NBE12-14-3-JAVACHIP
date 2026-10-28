package com.budzet.domain.budget.service

import com.budzet.domain.budget.dto.BudgetHistoryResponse
import com.budzet.domain.budget.dto.BudgetResponse
import com.budzet.domain.budget.dto.BudgetUpdateRequest
import com.budzet.domain.budget.dto.BudgetUpdateResponse
import com.budzet.domain.budget.entity.BudgetChange
import com.budzet.domain.budget.repository.BudgetChangeRepository
import com.budzet.domain.room.entity.Authority
import com.budzet.domain.room.entity.Room
import com.budzet.domain.room.entity.UserRoomConnection
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
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
class BudgetService(
    private val roomRepository: RoomRepository,
    private val budgetChangeRepository: BudgetChangeRepository,
    private val userRoomConnectionRepository: UserRoomConnectionRepository,
) {
    @Transactional(readOnly = true)
    fun getBudget(roomId: Long, userId: Long): BudgetResponse {
        validateRoomMember(roomId, userId)
        val room = findByRoomId(roomId)
        if (room.totalBudget == null || room.availableBudget == null) {
            throw BusinessException(ErrorCode.BUDGET_NOT_FOUND)
        }
        return BudgetResponse.from(room)
    }

    @Transactional(readOnly = true)
    fun getBudgetHistory(roomId: Long, userId: Long): BudgetHistoryResponse {
        validateRoomMember(roomId, userId)
        findByRoomId(roomId)
        val budgetChanges = budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(roomId)
        return BudgetHistoryResponse.from(budgetChanges)
    }

    @Retryable(
        retryFor = [PessimisticLockingFailureException::class, LockTimeoutException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 500),
    )
    @Transactional
    fun updateBudget(roomId: Long, userId: Long, budgetUpdateRequest: BudgetUpdateRequest): BudgetUpdateResponse {
        val connection = validateRoomMember(roomId, userId)
        if (connection.authority != Authority.OWNER && connection.authority != Authority.OPERATOR) {
            throw BusinessException(ErrorCode.FORBIDDEN_ACCESS)
        }
        val room = findByRoomIdWithLock(roomId)
        room.updateTotalBudget(budgetUpdateRequest.totalBudget, budgetUpdateRequest.budgetType)
        val budgetChange = BudgetChange(
            room = room,
            user = connection.user,
            userName = connection.user.name,
            type = requireNotNull(budgetUpdateRequest.budgetType),
            reason = budgetUpdateRequest.reason,
            changeBudget = budgetUpdateRequest.totalBudget,
        )
        room.addBudgetChange(budgetChange)
        budgetChangeRepository.save(budgetChange)
        return BudgetUpdateResponse.from(room)
    }

    fun findByRoomId(roomId: Long): Room = roomRepository.findByIdOrNull(roomId)
        ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)

    fun findByRoomIdWithLock(roomId: Long): Room = roomRepository.findByWithLock(roomId)
        ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)

    fun validateRoomMember(roomId: Long, userId: Long): UserRoomConnection =
        userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId)
            ?: throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
}
