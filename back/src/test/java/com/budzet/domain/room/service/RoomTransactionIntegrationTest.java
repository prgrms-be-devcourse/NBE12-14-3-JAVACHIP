package com.budzet.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.budzet.domain.room.dto.RoomCreateRequest;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RoomTransactionIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRoomConnectionRepository connectionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> roomIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final String failedRoomName = "롤백-" + UUID.randomUUID().toString().substring(0, 12);

    @AfterEach
    void tearDown() {
        // 롤백 검증이 실패해 Room이 남더라도, 이 테스트가 만든 데이터만 정리한다.
        List<Long> failedRoomIds = jdbcTemplate.queryForList(
                "SELECT id FROM room WHERE name = ?", Long.class, failedRoomName);
        roomRepository.deleteAllById(failedRoomIds);
        roomRepository.deleteAllById(roomIds);
        userRepository.deleteAllById(userIds);
    }

    @Test
    @DisplayName("삭제된 회원의 OWNER UserRoomConnection 저장이 실패하면 Room도 롤백하고 기존 데이터는 보존한다.")
    void createRoom_rollsBackRoomWhenOwnerConnectionFails() {
        User owner = createUser();
        Room existingRoom = roomRepository.save(Room.create("기존 모임", 50_000L, Currency.USD));
        roomIds.add(existingRoom.getId());
        UserRoomConnection existingConnection = connectionRepository.save(
                UserRoomConnection.createOwner(owner, existingRoom));

        User deletedUser = createUser();
        Long deletedUserId = deletedUser.getId();
        userRepository.deleteById(deletedUserId);
        assertThat(userRepository.existsById(deletedUserId)).isFalse();
        long roomCount = roomRepository.count();
        long connectionCount = connectionRepository.count();
        long userCount = userRepository.count();

        // 테스트 자체에는 트랜잭션을 걸지 않고 Spring 서비스의 트랜잭션 종료 후 조회한다.
        assertThrows(DataIntegrityViolationException.class, () -> roomService.createRoom(
                deletedUserId, new RoomCreateRequest(failedRoomName, 100_000L, Currency.KRW)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM room WHERE name = ?", Long.class, failedRoomName)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_room_connection WHERE user_id = ?",
                Long.class, deletedUserId)).isZero();
        assertThat(roomRepository.count()).isEqualTo(roomCount);
        assertThat(connectionRepository.count()).isEqualTo(connectionCount);
        assertThat(userRepository.count()).isEqualTo(userCount);
        assertThat(userRepository.existsById(deletedUserId)).isFalse();

        Room preservedRoom = roomRepository.findById(existingRoom.getId()).orElseThrow();
        assertThat(preservedRoom.getName()).isEqualTo("기존 모임");
        assertThat(preservedRoom.getTotalBudget()).isEqualTo(50_000L);
        assertThat(preservedRoom.getAvailableBudget()).isEqualTo(50_000L);
        assertThat(preservedRoom.getCurrency()).isEqualTo(Currency.USD);
        assertThat(preservedRoom.getCreatedAt()).isEqualTo(existingRoom.getCreatedAt());
        UserRoomConnection preservedConnection = connectionRepository
                .findByUser_IdAndRoom_Id(owner.getId(), existingRoom.getId()).orElseThrow();
        assertThat(preservedConnection.getAuthority()).isEqualTo(Authority.OWNER);
        assertThat(preservedConnection.isJoined()).isTrue();
        assertThat(preservedConnection.getCreatedAt()).isEqualTo(existingConnection.getCreatedAt());
        assertThat(connectionRepository.findAllByRoom_Id(existingRoom.getId())).hasSize(1);
        User preservedOwner = userRepository.findById(owner.getId()).orElseThrow();
        assertThat(preservedOwner.getEmail()).isEqualTo(owner.getEmail());
        assertThat(preservedOwner.getName()).isEqualTo(owner.getName());
    }

    private User createUser() {
        User user = userRepository.save(new User(
                UUID.randomUUID() + "@test.com", "test-password", "롤백 테스트 회원"));
        userIds.add(user.getId());
        return user;
    }
}
