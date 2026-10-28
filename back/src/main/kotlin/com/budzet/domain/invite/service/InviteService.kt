package com.budzet.domain.invite.service

import com.budzet.domain.notification.service.NotificationService
import com.budzet.domain.invite.dto.InviteJoinResponse
import com.budzet.domain.invite.dto.InviteResponse
import com.budzet.domain.invite.entity.Invite
import com.budzet.domain.invite.repository.InviteRepository
import com.budzet.domain.room.entity.Authority
import com.budzet.domain.room.entity.UserRoomConnection
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.domain.user.repository.UserRepository
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import jakarta.persistence.LockTimeoutException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class InviteService(
    private val inviteRepository: InviteRepository,
    private val roomRepository: RoomRepository,
    private val userRoomConnectionRepository: UserRoomConnectionRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
) {
    @Transactional
    fun createInvite(roomId: Long, userId: Long): InviteResponse {
        val room = roomRepository.findById(roomId).orElseThrow { BusinessException(ErrorCode.ROOM_NOT_FOUND) }
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId)
            ?: throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        if (connection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        val code = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        val expireAt = LocalDateTime.now().plusHours(1)
        val invite = Invite(code, room, expireAt)
        inviteRepository.save(invite)
        return InviteResponse(invite.code, invite.expireAt)
    }

    @Transactional(readOnly = true)
    fun verifyInvite(token: String): InviteResponse {
        val invite = inviteRepository.findById(token)
            .orElseThrow { BusinessException(ErrorCode.NOT_FOUND) }
        if (!invite.expireAt.isAfter(LocalDateTime.now())) {
            throw BusinessException(ErrorCode.CONFLICT)
        }
        return InviteResponse(invite.code, invite.expireAt)
    }

    @Transactional(readOnly = true)
    fun getInvites(roomId: Long, actorId: Long): List<InviteResponse> {
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId)
            ?: throw BusinessException(ErrorCode.USER_NOT_JOINED_ROOM)
        if (connection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        val invites = inviteRepository.findAllByRoom_Id(roomId)
        return invites.map { InviteResponse(it.code, it.expireAt) }
    }

    @Retryable(
        retryFor = [PessimisticLockingFailureException::class, LockTimeoutException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 500),
    )
    @Transactional
    fun joinRoom(code: String, userId: Long): InviteJoinResponse {
        // 초대 코드 조회
        val invite = inviteRepository.findById(code)
            .orElseThrow { BusinessException(ErrorCode.INVITE_NOT_FOUND) }
        // 대상 Room을 잠근 상태로 조회
        val roomId = requireNotNull(invite.room.id)
        val room = roomRepository.findByWithLock(roomId)
            ?: throw BusinessException(ErrorCode.ROOM_NOT_FOUND)
        // 잠금 획득 후 만료일이 유효한지 확인
        if (!invite.expireAt.isAfter(LocalDateTime.now())) {
            throw BusinessException(ErrorCode.INVITE_EXPIRED)
        }
        // 이미 참여한 상태인지 확인
        if (userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId) != null) {
            throw BusinessException(ErrorCode.USER_ALREADY_JOINED_ROOM)
        }
        // 멤버 참여 처리하기(UserRoomConnection 생성)
        val user = userRepository.getReferenceById(userId)
        val connection = UserRoomConnection.createMember(user, room)
        val savedConnection = userRoomConnectionRepository.save(connection)
        notificationService.notifyMemberJoined(savedConnection)
        return InviteJoinResponse.from(savedConnection)
    }
}
