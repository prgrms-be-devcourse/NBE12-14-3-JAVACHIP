package com.budzet.domain.room.service

import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.room.dto.MemberResponse
import com.budzet.domain.room.entity.Authority
import com.budzet.domain.room.entity.Room
import com.budzet.domain.room.entity.UserRoomConnection
import com.budzet.domain.room.entity.UserRoomConnectionId
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserRoomConnectionService(
    private val userRoomConnectionRepository: UserRoomConnectionRepository,
    private val roomRepository: RoomRepository,
    private val budgetRequestRepository: BudgetRequestRepository,
) {
    @Transactional(readOnly = true)
    fun getConnection(roomId: Long, actorId: Long, userId: Long): UserRoomConnection {
        validateRoomMember(roomId, actorId)
        return userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
    }

    @Transactional(readOnly = true)
    fun getMembers(roomId: Long, actorId: Long): List<MemberResponse> {
        validateRoomMember(roomId, actorId)
        return userRoomConnectionRepository.findAllByRoom_Id(roomId).map(MemberResponse::from)
    }

    private fun validateRoomMember(roomId: Long, actorId: Long) {
        if (!userRoomConnectionRepository.existsById(UserRoomConnectionId(actorId, roomId))) {
            throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        }
    }

    @Transactional
    fun kickMember(actorId: Long, roomId: Long, targetUserId: Long) {
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        val actorConnection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (actorConnection.authority != Authority.OWNER && actorConnection.authority != Authority.OPERATOR) {
            throw BusinessException(ErrorCode.ROOM_MANAGER_REQUIRED)
        }
        val targetConnection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (targetConnection.authority == Authority.OWNER) {
            throw BusinessException(ErrorCode.INVALID_AUTHORITY)
        }
        removeUnsettledRequests(room, targetUserId)
        userRoomConnectionRepository.delete(targetConnection)
    }

    @Transactional
    fun leaveRoom(roomId: Long, userId: Long) {
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (connection.authority == Authority.OWNER) {
            throw BusinessException(ErrorCode.CONFLICT)
        }
        removeUnsettledRequests(room, userId)
        userRoomConnectionRepository.delete(connection)
    }

    private fun removeUnsettledRequests(room: Room, userId: Long) {
        // Room → 신청 순서로 잠근 뒤 반환·신청 삭제·소속 삭제를 함께 커밋한다.
        val requests = budgetRequestRepository.findUnsettledByMemberWithLock(
            requireNotNull(room.id), userId,
            listOf(BudgetRequestType.REQUEST, BudgetRequestType.REJECT, BudgetRequestType.APPROVE),
        )
        val reservedAmount = requests.filter { it.status == BudgetRequestType.APPROVE }
            .sumOf { requireNotNull(it.requestedAmount) }
        if (reservedAmount > 0) {
            room.updateAvailableBudget(reservedAmount)
        }
        budgetRequestRepository.deleteAll(requests)
    }
}
