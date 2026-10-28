package com.budzet.domain.room.service;

import com.budzet.domain.budget.dto.BudgetChangeCreateRequest;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.budget.service.BudgetChangeService;
import com.budzet.domain.budget.service.BudgetRequestService;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import com.budzet.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class MemberDepartureIntegrationTest {
    @Autowired protected UserRoomConnectionService members;
    @Autowired protected BudgetRequestService requests;
    @Autowired protected BudgetChangeService changes;
    @Autowired protected RoomRepository rooms;
    @Autowired protected UserRepository users;
    @Autowired protected BudgetRequestRepository requestRepository;
    @Autowired protected BudgetChangeRepository changeRepository;
    @Autowired protected JdbcTemplate jdbc;
    @MockitoSpyBean protected UserRoomConnectionRepository connections;

    private final List<Long> roomIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long roomId : roomIds) {
            jdbc.update("DELETE FROM budget_change WHERE room_id = ?", roomId);
            jdbc.update("DELETE FROM budget_request WHERE room_id = ?", roomId);
            jdbc.update("DELETE FROM user_room_connection WHERE room_id = ?", roomId);
            jdbc.update("DELETE FROM room WHERE id = ?", roomId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM user WHERE id = ?", userId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("탈퇴·강퇴는 미정산 신청만 삭제하고 예약 금액을 반환하며 정산 기록과 다른 데이터를 보존한다")
    void departureCleansOnlyMembersUnsettledRequests(boolean kick) {
        Fixture f = fixture();
        BudgetRequest pending = request(f.room(), f.member(), 500L);
        BudgetRequest rejected = request(f.room(), f.member(), 600L);
        requests.rejectBudgetRequest(f.room().getId(), f.owner(), rejected.getId(), "반려");
        BudgetRequest approved = approved(f, 2_000L);
        BudgetRequest secondApproved = approved(f, 1_000L);
        BudgetRequest settled = approved(f, 800L);
        Long changeId = changes.createBudgetChange(f.room().getId(), f.member().getId(), settled.getId(),
                new BudgetChangeCreateRequest(500L, "정산")).getId();
        User otherMember = user();
        connections.save(UserRoomConnection.createMember(otherMember, f.room()));
        BudgetRequest otherRequest = request(f.room(), otherMember, 700L);
        Room otherRoom = room(5_000L);
        connections.save(UserRoomConnection.createOwner(f.owner(), otherRoom));
        connections.save(UserRoomConnection.createMember(f.member(), otherRoom));
        BudgetRequest otherRoomRequest = request(otherRoom, f.member(), 900L);
        requests.approveBudgetRequest(otherRoom.getId(), f.owner(), otherRoomRequest.getId());

        assertBudget(f.room().getId(), 9_500L, 6_500L);
        depart(f, kick);

        assertThat(connections.findByUser_IdAndRoom_Id(f.member().getId(), f.room().getId())).isNull();
        for (Long id : List.of(pending.getId(), rejected.getId(), approved.getId(), secondApproved.getId())) {
            assertThat(requestRepository.existsById(id)).isFalse();
        }
        assertThat(requestRepository.findById(settled.getId()).orElseThrow().getStatus())
                .isEqualTo(BudgetRequestType.SETTLEMENT);
        assertThat(changeRepository.existsById(changeId)).isTrue();
        var detail = changes.budgetChangeDetail(f.room().getId(), f.owner().getId(), changeId);
        assertThat(detail.getRequestId()).isEqualTo(settled.getId());
        assertThat(detail.getUserId()).isEqualTo(f.member().getId());
        assertThat(detail.getChangedBudget()).isEqualTo(500L);
        assertThat(jdbc.queryForObject("SELECT request_id FROM budget_change WHERE id = ?", Long.class, changeId))
                .isEqualTo(settled.getId());
        assertThat(jdbc.queryForObject("SELECT changed_budget FROM budget_change WHERE id = ?", Long.class, changeId))
                .isEqualTo(500L);
        assertThat(requestRepository.existsById(otherRequest.getId())).isTrue();
        assertThat(requestRepository.findById(otherRoomRequest.getId()).orElseThrow().getStatus())
                .isEqualTo(BudgetRequestType.APPROVE);
        assertThat(connections.findByUser_IdAndRoom_Id(f.member().getId(), otherRoom.getId())).isNotNull();
        assertBudget(f.room().getId(), 9_500L, 9_500L);
        assertBudget(otherRoom.getId(), 5_000L, 4_100L);
        // 중복 호출로 예약 예산이 두 번 반환되지 않는다.
        assertThatThrownBy(() -> depart(f, kick)).isInstanceOf(BusinessException.class);
        assertBudget(f.room().getId(), 9_500L, 9_500L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("소속 삭제가 실패하면 신청 삭제와 예약 예산 반환도 함께 롤백된다")
    void departureRollsBackWhenMembershipDeletionFails(boolean kick) {
        Fixture f = fixture();
        BudgetRequest approved = approved(f, 2_000L);
        doThrow(new IllegalStateException("소속 삭제 실패"))
                .when(connections).delete(any(UserRoomConnection.class));

        assertThatThrownBy(() -> depart(f, kick)).isInstanceOf(IllegalStateException.class);

        assertThat(connections.findByUser_IdAndRoom_Id(f.member().getId(), f.room().getId())).isNotNull();
        assertThat(requestRepository.findById(approved.getId()).orElseThrow().getStatus())
                .isEqualTo(BudgetRequestType.APPROVE);
        assertBudget(f.room().getId(), 10_000L, 8_000L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("신청이 없는 멤버도 탈퇴·강퇴할 수 있으며 예산은 변하지 않는다")
    void departureWithoutRequests(boolean kick) {
        Fixture f = fixture();
        depart(f, kick);
        assertThat(connections.findByUser_IdAndRoom_Id(f.member().getId(), f.room().getId())).isNull();
        assertBudget(f.room().getId(), 10_000L, 10_000L);
    }

    protected Fixture fixture() {
        User owner = user();
        User member = user();
        Room room = room(10_000L);
        connections.save(UserRoomConnection.createOwner(owner, room));
        connections.save(UserRoomConnection.createMember(member, room));
        return new Fixture(owner, member, room);
    }

    protected User user() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@t.co", "password", "멤버"));
        userIds.add(user.getId());
        return user;
    }

    protected Room room(long budget) {
        Room room = rooms.saveAndFlush(Room.create("탈퇴 검증", budget, Currency.KRW));
        roomIds.add(room.getId());
        return room;
    }

    protected BudgetRequest request(Room room, User user, long amount) {
        return requestRepository.saveAndFlush(new BudgetRequest(room, user, "신청", amount));
    }

    protected BudgetRequest approved(Fixture f, long amount) {
        BudgetRequest request = request(f.room(), f.member(), amount);
        requests.approveBudgetRequest(f.room().getId(), f.owner(), request.getId());
        return request;
    }

    protected void depart(Fixture f, boolean kick) {
        if (kick) members.kickMember(f.owner().getId(), f.room().getId(), f.member().getId());
        else members.leaveRoom(f.room().getId(), f.member().getId());
    }

    protected void assertBudget(Long roomId, long total, long available) {
        Room saved = rooms.findById(roomId).orElseThrow();
        assertThat(saved.getTotalBudget()).isEqualTo(total);
        assertThat(saved.getAvailableBudget()).isEqualTo(available);
    }

    protected record Fixture(User owner, User member, Room room) {}
}
