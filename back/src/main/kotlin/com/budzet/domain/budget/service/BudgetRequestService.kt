package com.budzet.domain.budget.service

import com.budzet.domain.notification.service.NotificationService
import com.budzet.domain.budget.dto.BudgetRequestResponse
import com.budzet.domain.budget.entity.BudgetRequest
import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.room.entity.Authority
import com.budzet.domain.room.entity.UserRoomConnectionId
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.domain.user.entity.User
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BudgetRequestService(
    private val budgetRequestRepository: BudgetRequestRepository,
    private val roomRepository: RoomRepository,
    private val userRoomConnectionRepository: UserRoomConnectionRepository,
    private val notificationService: NotificationService,
) {
    private fun userInRoomCheck(userRoomConnectionId: UserRoomConnectionId) {
        if (!userRoomConnectionRepository.existsById(userRoomConnectionId)) {
            throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        }
    }

    @Transactional
    fun budgetRequestRegistration(roomId: Long, user: User, reason: String, requestedAmount: Long) {
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        if (requireNotNull(room.availableBudget) < requestedAmount) {
            throw BusinessException(ErrorCode.REQUEST_AMOUNT_OVER_BUDGET)
        }
        val request = BudgetRequest(room, user, reason, requestedAmount)
        budgetRequestRepository.save(request)
        notificationService.notifyBudgetRequested(request)
    }

    fun getBudgetRequestList(roomId: Long, user: User): List<BudgetRequestResponse> {
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        return budgetRequestRepository.findDtoByRoomId(roomId)
    }

    fun getBudgetRequest(roomId: Long, user: User, requestId: Long): BudgetRequestResponse? {
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        return budgetRequestRepository.findDtoById(roomId, requestId)
    }

    @Transactional
    fun deleteBudgetRequest(roomId: Long, user: User, requestId: Long) {
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        val response = budgetRequestRepository.findDtoById(roomId, requestId)
        if (response == null) {
            throw BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND)
        } else if (response.userId != user.id) {
            throw BusinessException(ErrorCode.NOT_BUDGET_REQUESTER)
        } else {
            if (response.status == BudgetRequestType.APPROVE) {
                room.updateAvailableBudget(requireNotNull(response.requestedAmount))
            }
            budgetRequestRepository.deleteById(requestId)
        }
    }

    @Transactional
    fun approveBudgetRequest(roomId: Long, user: User, requestId: Long) {
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(requireNotNull(user.id), roomId)
            ?: throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        if (connection.authority == Authority.MEMBER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        val request = budgetRequestRepository.findByIdAndRoomId(requestId, roomId)
            .orElseThrow { BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND) }
        if (request.status != BudgetRequestType.REQUEST) {
            throw BusinessException(ErrorCode.NOT_APPROVABLE)
        } else if (requireNotNull(room.availableBudget) < requireNotNull(request.requestedAmount)) {
            throw BusinessException(ErrorCode.BUDGET_EXCEEDED)
        } else {
            room.updateAvailableBudget(-1 * requireNotNull(request.requestedAmount))
            request.approveRequest()
            notificationService.notifyBudgetDecision(request)
        }
    }

    @Transactional
    fun rejectBudgetRequest(roomId: Long, user: User, requestId: Long, rejectReason: String?) {
        roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(requireNotNull(user.id), roomId)
            ?: throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        if (connection.authority == Authority.MEMBER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        val request = budgetRequestRepository.findByIdAndRoomId(requestId, roomId)
            .orElseThrow { BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND) }
        if (request.status != BudgetRequestType.REQUEST) {
            throw BusinessException(ErrorCode.NOT_REJECTABLE)
        } else {
            request.rejectRequest(rejectReason)
            notificationService.notifyBudgetDecision(request)
        }
    }

    @Transactional
    fun modifyBudgetRequest(roomId: Long, user: User, requestId: Long, reason: String, requestedAmount: Long) {
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        userInRoomCheck(UserRoomConnectionId(user.id, roomId))
        val request = budgetRequestRepository.findByIdAndRoomId(requestId, roomId)
            .orElseThrow { BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND) }
        if (request.status == BudgetRequestType.SETTLEMENT) {
            throw BusinessException(ErrorCode.NOT_MODIFYABLE)
        } else if (requireNotNull(room.availableBudget) + requireNotNull(request.requestedAmount) < requestedAmount) {
            throw BusinessException(ErrorCode.REQUEST_AMOUNT_OVER_BUDGET)
        } else if (request.user.id != user.id) {
            throw BusinessException(ErrorCode.NOT_BUDGET_REQUESTER)
        } else {
            if (request.status == BudgetRequestType.APPROVE) {
                room.updateAvailableBudget(requireNotNull(request.requestedAmount) - requestedAmount)
            }
            request.modifyRequest(reason, requestedAmount)
        }
    }
}
