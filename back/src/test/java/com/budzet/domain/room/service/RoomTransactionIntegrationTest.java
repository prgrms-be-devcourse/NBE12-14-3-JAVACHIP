package com.budzet.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.budzet.domain.room.dto.RoomCreateRequest;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.entity.UserRoomConnectionId;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

// 모임(Room)에 대한 트랜잭션 통합 테스트
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

    @Autowired
    private EntityManager entityManager;

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
        connectionRepository.save(
                UserRoomConnection.createOwner(owner, existingRoom));
        // 최초 객체의 나노초 값 대신 DB에 저장된 정밀도의 시간을 기준으로 비교한다.
        LocalDateTime originalRoomCreatedAt = roomRepository.findById(existingRoom.getId())
                .orElseThrow().getCreatedAt();
        LocalDateTime originalConnectionCreatedAt = connectionRepository
                .findByUser_IdAndRoom_Id(owner.getId(), existingRoom.getId())
                .getCreatedAt();

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
        assertThat(preservedRoom.getCreatedAt()).isEqualTo(originalRoomCreatedAt);
        UserRoomConnection preservedConnection = connectionRepository
                .findByUser_IdAndRoom_Id(owner.getId(), existingRoom.getId());
        assertThat(preservedConnection.getAuthority()).isEqualTo(Authority.OWNER);
        assertThat(preservedConnection.isJoined()).isTrue();
        assertThat(preservedConnection.getCreatedAt()).isEqualTo(originalConnectionCreatedAt);
        assertThat(connectionRepository.findAllByRoom_Id(existingRoom.getId())).hasSize(1);
        User preservedOwner = userRepository.findById(owner.getId()).orElseThrow();
        assertThat(preservedOwner.getEmail()).isEqualTo(owner.getEmail());
        assertThat(preservedOwner.getName()).isEqualTo(owner.getName());
    }

    @Test
    @Transactional
    @DisplayName("모임 프록시·지연 컬렉션·복합키 조회·감사·변경 감지가 영속성 컨텍스트를 비운 뒤에도 동작한다.")
    void roomAndConnectionPersistenceSupportsProxyCompositeKeyAndDirtyChecking() {
        User owner = createUser();
        Room room = Room.create("영속성 검증 모임", 10_000L, Currency.KRW);
        assertThat(room.getId()).isNull();
        assertThat(room.getCreatedAt()).isNull();
        assertThat(room.getUserConnections()).isEmpty();
        roomRepository.save(room);
        roomIds.add(room.getId());
        UserRoomConnection connection = UserRoomConnection.createOwner(owner, room);
        assertThat(connection.getCreatedAt()).isNull();
        // 복합키 엔티티는 merge될 수 있으므로 반환된 관리 객체를 확인한다.
        UserRoomConnection savedConnection = connectionRepository.save(connection);
        entityManager.flush();
        assertThat(room.getCreatedAt()).isNotNull();
        assertThat(savedConnection.getCreatedAt()).isNotNull();
        Long roomId = room.getId();
        Long ownerId = owner.getId();
        entityManager.clear();

        Room reference = entityManager.getReference(Room.class, roomId);
        assertThat(Hibernate.isInitialized(reference)).isFalse();
        assertThat(reference.getName()).isEqualTo("영속성 검증 모임");
        assertThat(Hibernate.isInitialized(reference)).isTrue();
        assertThat(Hibernate.isInitialized(reference.getUserConnections())).isFalse();
        assertThat(reference.getUserConnections()).hasSize(1);
        assertThat(Hibernate.isInitialized(reference.getUserConnections())).isTrue();
        assertThat(Hibernate.isInitialized(reference.getInvites())).isFalse();
        assertThat(Hibernate.isInitialized(reference.getBudgetRequests())).isFalse();
        assertThat(Hibernate.isInitialized(reference.getBudgetChanges())).isFalse();

        UserRoomConnectionId key = new UserRoomConnectionId(ownerId, roomId);
        assertThat(key).isEqualTo(new UserRoomConnectionId(ownerId, roomId));
        assertThat(key.hashCode()).isEqualTo(new UserRoomConnectionId(ownerId, roomId).hashCode());
        UserRoomConnection reloaded = connectionRepository.findById(key).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(ownerId);
        assertThat(reloaded.getRoom().getId()).isEqualTo(roomId);
        assertThat(reloaded.isJoined()).isTrue();
        reference.changeName("변경된 모임");
        reference.updateAvailableBudget(-1_000L);
        reloaded.changeAuthority(Authority.OPERATOR);
        entityManager.flush();
        entityManager.clear();

        Room changedRoom = roomRepository.findById(roomId).orElseThrow();
        assertThat(changedRoom.getName()).isEqualTo("변경된 모임");
        assertThat(changedRoom.getTotalBudget()).isEqualTo(10_000L);
        assertThat(changedRoom.getAvailableBudget()).isEqualTo(9_000L);
        UserRoomConnection changedConnection = connectionRepository.findById(
                new UserRoomConnectionId(ownerId, roomId)).orElseThrow();
        assertThat(changedConnection.getAuthority()).isEqualTo(Authority.OPERATOR);
        assertThat(changedConnection.getCreatedAt()).isNotNull();
        assertThat(changedConnection.isJoined()).isTrue();
    }

    private User createUser() {
        User user = userRepository.save(new User(
                UUID.randomUUID() + "@test.com", "test-password", "롤백 테스트 회원"));
        userIds.add(user.getId());
        return user;
    }
}
