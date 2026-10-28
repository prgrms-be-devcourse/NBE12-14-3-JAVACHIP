package com.budzet.domain.room.service

import com.budzet.domain.room.dto.*
import com.budzet.domain.room.entity.Authority
import com.budzet.domain.room.entity.Room
import com.budzet.domain.room.entity.UserRoomConnection
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.domain.user.repository.UserRepository
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val userRoomConnectionRepository: UserRoomConnectionRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun createRoom(userId: Long, request: RoomCreateRequest): RoomCreateResponse {
        val user = userRepository.getReferenceById(userId)
        val room = Room.create(
            requireNotNull(request.name),
            requireNotNull(request.totalBudget),
            requireNotNull(request.currency),
        )
        val savedRoom = roomRepository.save(room)
        val ownerConnection = UserRoomConnection.createOwner(user, savedRoom)
        userRoomConnectionRepository.save(ownerConnection)
        return RoomCreateResponse.from(savedRoom)
    }

    // TODO: (김영우)
    // TODO: 페이징 처리 추가할지 논의한 후, 결정되면 추가 구현하기
    @Transactional(readOnly = true)
    fun getRooms(userId: Long): RoomListResponse {
        val rooms = roomRepository.findAllJoinedRoomsByUserId(userId)
        return RoomListResponse.from(rooms)
    }

    @Transactional(readOnly = true)
    fun getRoom(userId: Long, roomId: Long): RoomDetailResponse {
        val room = roomRepository.findJoinedRoomByIdAndUserId(roomId, userId)
            .orElseThrow { BusinessException(ErrorCode.ROOM_NOT_FOUND) }
        return RoomDetailResponse.from(room)
    }

    @Transactional
    fun updateRoom(userId: Long, roomId: Long, request: RoomUpdateRequest): RoomDetailResponse {
        val room = roomRepository.findById(roomId)
            .orElseThrow { BusinessException(ErrorCode.ROOM_NOT_FOUND) }
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (connection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        room.changeName(requireNotNull(request.name))
        return RoomDetailResponse.from(room)
    }

    @Transactional
    fun deleteRoom(userId: Long, roomId: Long) {
        val room = roomRepository.findById(roomId)
            .orElseThrow { BusinessException(ErrorCode.ROOM_NOT_FOUND) }
        val connection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (connection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        roomRepository.delete(room)
    }

    @Transactional
    fun changeAuthority(actorId: Long, roomId: Long, targetUserId: Long, request: AuthorityChangeRequest) {
        val actorConnection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (actorConnection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        val targetConnection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (targetConnection.authority == Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        if (request.authority == Authority.OWNER) {
            throw BusinessException(ErrorCode.INVALID_AUTHORITY)
        }
        targetConnection.changeAuthority(requireNotNull(request.authority))
    }

    @Transactional
    fun delegateOwner(actorId: Long, roomId: Long, targetUserId: Long) {
        val actorConnection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (actorConnection.authority != Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        val targetConnection = userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (targetConnection.authority == Authority.OWNER) {
            throw BusinessException(ErrorCode.OWNER_REQUIRED)
        }
        actorConnection.changeAuthority(Authority.MEMBER)
        targetConnection.changeAuthority(Authority.OWNER)
    }
}
