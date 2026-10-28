package com.budzet.domain.room.repository

import com.budzet.domain.room.entity.Room
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.util.*

interface RoomRepository : JpaRepository<Room, Long> {
    @Query("""
        SELECT room
        FROM Room room
        JOIN room.userConnections connection
        WHERE connection.user.id = :userId
        ORDER BY room.createdAt DESC
    """)
    fun findAllJoinedRoomsByUserId(@Param("userId") userId: Long): List<Room>

    @Query("""
        SELECT room
        FROM Room room
        JOIN room.userConnections connection
        WHERE room.id = :roomId
          AND connection.user.id = :userId
    """)
    fun findJoinedRoomByIdAndUserId(
        @Param("roomId") roomId: Long,
        @Param("userId") userId: Long,
    ): Optional<Room>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
        SELECT room
        FROM Room room
        WHERE room.id = :roomId
    """)
    fun findByWithLock(@Param("roomId") roomId: Long): Room?
}
