package com.budzet.domain.budget.integration;

import com.budzet.domain.budget.dto.BudgetHistoryResponse;
import com.budzet.domain.budget.dto.BudgetResponse;
import com.budzet.domain.budget.dto.BudgetUpdateRequest;
import com.budzet.domain.budget.dto.BudgetUpdateResponse;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.budget.service.BudgetService;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import com.budzet.global.exception.BusinessException;
import com.budzet.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BudgetServiceIntegrationTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Autowired
    private BudgetChangeRepository budgetChangeRepository;



    // =========================
    // 테스트 데이터 생성
    // =========================

    private User createUser() {

        String email = "b-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                + "@test.com";

        User user = new User(
                email,
                "test-password",
                "테스트 사용자"
        );

        return userRepository.saveAndFlush(user);
    }

    private Room createRoom(Long totalBudget) {
        Room room = Room.create(
                "통합 테스트 모임",
                totalBudget,
                Currency.KRW
        );

        return roomRepository.saveAndFlush(room);
    }

    private UserRoomConnection createMembership(
            User user,
            Room room,
            Authority authority
    ) {
        UserRoomConnection connection;

        if (authority == Authority.OWNER) {
            connection = UserRoomConnection.createOwner(user, room);
        } else {
            connection = UserRoomConnection.createMember(user, room);
            connection.changeAuthority(authority);
        }

        return userRoomConnectionRepository.saveAndFlush(connection);
    }

    // =========================
    // 예산 조회
    // =========================

    @Test
    @DisplayName("예산 조회 성공 - DB에 저장된 예산 정보를 반환한다")
    void getBudget_success() {
        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createMembership(user, room, Authority.OWNER);

        // when
        BudgetResponse response = budgetService.getBudget(
                room.getId(),
                user.getId()
        );

        // then
        assertNotNull(response);
        assertEquals(room.getId(), response.roomId());
        assertEquals("통합 테스트 모임", response.roomName());
        assertEquals(10000L, response.totalBudget());
        assertEquals(10000L, response.availableBudget());
        assertEquals(0L, response.reserveBudget());
        assertEquals("KRW", response.currency());
    }

    // =========================
    // 예산 변경 이력 조회
    // =========================

    @Test
    @DisplayName("예산 변경 이력 조회 성공 - DB에 저장된 변경 이력을 반환한다")
    void getBudgetHistory_success() {
        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createMembership(user, room, Authority.OWNER);

        BudgetChange budgetChange = new BudgetChange(
                room,
                user,
                user.getName(),
                BudgetType.INCREASE,
                "초기 예산 증액",
                2000L
        );

        BudgetChange savedChange =
                budgetChangeRepository.saveAndFlush(budgetChange);

        // when
        BudgetHistoryResponse response = budgetService.getBudgetHistory(
                room.getId(),
                user.getId()
        );

        // then
        assertNotNull(response);
        assertEquals(1, response.history().size());

        BudgetHistoryResponse.HistoryItem historyItem =
                response.history().get(0);

        assertEquals(savedChange.getId(), historyItem.id());
        assertEquals(2000L, historyItem.changeBudget());
        assertEquals(2000L, historyItem.changedBudget());
        assertEquals("INCREASE", historyItem.type());
        assertEquals("테스트 사용자", historyItem.userName());
        assertEquals("초기 예산 증액", historyItem.reason());
        assertNotNull(historyItem.processedAt());
    }

    // =========================
    // 예산 증액
    // =========================

    @Test
    @DisplayName("예산 수정 성공 - 증액 결과와 변경 이력이 DB에 저장된다")
    void updateBudget_increase_success() {
        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createMembership(user, room, Authority.OWNER);

        BudgetUpdateRequest request = new BudgetUpdateRequest(
                5000L,
                BudgetType.INCREASE,
                "지원금 추가"
        );

        // when
        BudgetUpdateResponse response = budgetService.updateBudget(
                room.getId(),
                user.getId(),
                request
        );

        // 실제 DB에서 다시 조회
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        // then
        assertEquals(room.getId(), response.roomId());
        assertEquals(15000L, response.totalBudget());

        // 실제 예산 및 가용예산 확인
        assertEquals(15000L, updatedRoom.getTotalBudget());
        assertEquals(15000L, updatedRoom.getAvailableBudget());

        // 변경 이력 저장 확인
        assertEquals(1, histories.size());

        BudgetChange history = histories.get(0);

        assertEquals(user.getId(), history.getUser().getId());
        assertEquals(room.getId(), history.getRoom().getId());
        assertEquals("테스트 사용자", history.getUserName());
        assertEquals(BudgetType.INCREASE, history.getType());
        assertEquals(5000L, history.getChangeBudget());
        assertEquals(5000L, history.getChangedBudget());
        assertEquals("지원금 추가", history.getReason());
        assertNotNull(history.getCreatedAt());
    }

    // =========================
    // 예산 감액
    // =========================

    @Test
    @DisplayName("예산 수정 성공 - 감액 결과와 변경 이력이 DB에 저장된다")
    void updateBudget_decrease_success() {
        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createMembership(user, room, Authority.OPERATOR);

        BudgetUpdateRequest request = new BudgetUpdateRequest(
                4000L,
                BudgetType.DECREASE,
                "비품 구매"
        );

        // when
        BudgetUpdateResponse response = budgetService.updateBudget(
                room.getId(),
                user.getId(),
                request
        );

        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        // then
        assertEquals(6000L, response.totalBudget());
        assertEquals(6000L, updatedRoom.getTotalBudget());
        assertEquals(6000L, updatedRoom.getAvailableBudget());

        assertEquals(1, histories.size());

        BudgetChange history = histories.get(0);

        assertEquals(BudgetType.DECREASE, history.getType());
        assertEquals(4000L, history.getChangeBudget());
        assertEquals("비품 구매", history.getReason());
    }

    @Test
    @DisplayName("예산 수정 성공 - 가용예산보다 1원 적게 감액")
    void updateBudget_decrease_justBelowAvailableBudget() {
        // given
        User user = createUser();
        Room room = createRoom(5000L);

        createMembership(user, room, Authority.OWNER);

        BudgetUpdateRequest request = new BudgetUpdateRequest(
                4999L,
                BudgetType.DECREASE,
                "경계값 테스트"
        );

        // when
        BudgetUpdateResponse response = budgetService.updateBudget(
                room.getId(),
                user.getId(),
                request
        );

        // then
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(1L, response.totalBudget());
        assertEquals(1L, updatedRoom.getTotalBudget());
        assertEquals(1L, updatedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertEquals(1, histories.size());
        assertEquals(4999L, histories.get(0).getChangeBudget());
    }

    @Test
    @DisplayName("예산 수정 성공 - 가용예산과 동일한 금액 감액")
    void updateBudget_decrease_exactlyAvailableBudget() {
        // given
        User user = createUser();
        Room room = createRoom(5000L);

        createMembership(user, room, Authority.OWNER);

        BudgetUpdateRequest request = new BudgetUpdateRequest(
                5000L,
                BudgetType.DECREASE,
                "경계값 테스트"
        );

        // when
        BudgetUpdateResponse response = budgetService.updateBudget(
                room.getId(),
                user.getId(),
                request
        );

        // then
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(0L, response.totalBudget());
        assertEquals(0L, updatedRoom.getTotalBudget());
        assertEquals(0L, updatedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertEquals(1, histories.size());
        assertEquals(5000L, histories.get(0).getChangeBudget());
    }

    // =========================
    // 권한 검증
    // =========================

    @Test
    @DisplayName("예산 수정 실패 - MEMBER는 예산을 수정할 수 없다")
    void updateBudget_forbiddenAccess() {
        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createMembership(user, room, Authority.MEMBER);

        BudgetUpdateRequest request = new BudgetUpdateRequest(
                5000L,
                BudgetType.INCREASE,
                "권한 없는 증액 시도"
        );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.updateBudget(
                        room.getId(),
                        user.getId(),
                        request
                )
        );

        // then
        assertEquals(
                ErrorCode.FORBIDDEN_ACCESS,
                exception.getErrorCode()
        );

        Room unchangedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(10000L, unchangedRoom.getTotalBudget());
        assertEquals(10000L, unchangedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertTrue(histories.isEmpty());
    }

    // =========================
    // 예산 경계값 및 예외 처리
    // =========================

    @Test
    @DisplayName("예산 수정 실패 - 감액 금액이 가용예산을 초과하면 DB 값이 유지된다")
    void updateBudget_exceedAvailableBudget() {
        // given
        User user = createUser();
        Room room = createRoom(5000L);

        createMembership(user, room, Authority.OWNER);

        BudgetUpdateRequest request = new BudgetUpdateRequest(
                5001L,
                BudgetType.DECREASE,
                "가용예산 초과"
        );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.updateBudget(
                        room.getId(),
                        user.getId(),
                        request
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_EXCEEDED,
                exception.getErrorCode()
        );

        // 서비스 호출 이후 DB의 실제 값을 다시 확인
        Room unchangedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(5000L, unchangedRoom.getTotalBudget());
        assertEquals(5000L, unchangedRoom.getAvailableBudget());

        // 실패한 예산 수정에 대한 이력은 저장되지 않아야 함
        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertTrue(histories.isEmpty());
    }
}