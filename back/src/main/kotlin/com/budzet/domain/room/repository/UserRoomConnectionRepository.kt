package com.budzet.domain.room.repository

import com.budzet.domain.room.entity.UserRoomConnection
import com.budzet.domain.room.entity.UserRoomConnectionId
import org.springframework.data.jpa.repository.JpaRepository

interface UserRoomConnectionRepository : JpaRepository<UserRoomConnection, UserRoomConnectionId> {
    fun findByUser_IdAndRoom_Id(userId: Long, roomId: Long): UserRoomConnection?

    fun findAllByRoom_Id(roomId: Long): List<UserRoomConnection>
}
