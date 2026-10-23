package com.budzet.domain.budget.integration;

import com.budzet.domain.budget.dto.BudgetChangeCreateRequest;
import com.budzet.domain.budget.dto.BudgetChangeCreateResponse;
import com.budzet.domain.budget.dto.BudgetChangeDetailResponse;
import com.budzet.domain.budget.dto.BudgetChangeListResponse;
import com.budzet.domain.budget.dto.BudgetChangeUpdateRequest;
import com.budzet.domain.budget.dto.BudgetChangeUpdateResponse;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.budget.service.BudgetChangeService;
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
class BudgetChangeServiceIntegrationTest {

    @Autowired
    private BudgetChangeService budgetChangeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Autowired
    private BudgetRequestRepository budgetRequestRepository;

    @Autowired
    private BudgetChangeRepository budgetChangeRepository;

    // =========================
    // 테스트 데이터 생성
    // =========================

    private User createUser() {
        String email = "b-"
                + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
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
                "정산 통합 테스트 모임",
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

    /**
     * 승인된 신청과 예약된 예산을 준비한다.
     *
     * 초기 총예산: 10,000원
     * 신청금액: 5,000원
     * 승인 후 가용예산: 5,000원
     */
    private ApprovedRequestFixture createApprovedRequestFixture(
            Long totalBudget,
            Long requestedAmount
    ) {
        User user = createUser();
        Room room = createRoom(totalBudget);

        createMembership(user, room, Authority.OWNER);

        // 승인된 신청이 예산을 예약한 상황을 준비한다.
        room.updateAvailableBudget(-requestedAmount);
        roomRepository.saveAndFlush(room);

        BudgetRequest request = new BudgetRequest(
                room,
                user,
                "테스트 예산 신청",
                requestedAmount
        );

        request.approveRequest();

        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        return new ApprovedRequestFixture(user, room, savedRequest);
    }

    /**
     * 정산 등록 완료 상태를 준비한다.
     *
     * 초기 총예산: 10,000원
     * 신청금액: 5,000원
     * 실제 정산금액: 3,000원
     * 정산 후 총예산: 7,000원
     * 정산 후 가용예산: 7,000원
     */
    private SettlementFixture createSettledFixture() {
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        BudgetRequest request = fixture.request();
        Room room = fixture.room();
        User user = fixture.user();

        request.changeToSettlement();
        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        room.settleBudget(5000L, 3000L);
        Room savedRoom = roomRepository.saveAndFlush(room);

        BudgetChange budgetChange = new BudgetChange(
                savedRoom,
                user,
                user.getName(),
                BudgetType.SETTLEMENT,
                "기존 정산 사유",
                5000L,
                3000L
        );

        budgetChange.addBudgetRequest(savedRequest);

        BudgetChange savedChange =
                budgetChangeRepository.saveAndFlush(budgetChange);

        return new SettlementFixture(
                user,
                savedRoom,
                savedRequest,
                savedChange
        );
    }

    private record ApprovedRequestFixture(
            User user,
            Room room,
            BudgetRequest request
    ) {
    }

    private record SettlementFixture(
            User user,
            Room room,
            BudgetRequest request,
            BudgetChange budgetChange
    ) {
    }

    // =========================
    // 정산 등록
    // =========================

    @Test
    @DisplayName("정산 등록 성공 - 정산 내역, 예산, 신청 상태가 DB에 반영된다")
    void createBudgetChange_success() {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(
                        3000L,
                        "장비 구매 정산"
                );

        // when
        BudgetChangeCreateResponse response =
                budgetChangeService.createBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.request().getId(),
                        request
                );

        // then: 응답 확인
        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(fixture.request().getId(), response.requestId());
        assertEquals(fixture.user().getId(), response.userId());
        assertEquals(5000L, response.changeBudget());
        assertEquals(3000L, response.changedBudget());
        assertEquals(BudgetType.SETTLEMENT, response.budgetType());
        assertEquals("테스트 사용자", response.userName());
        assertEquals("장비 구매 정산", response.reason());
        assertNotNull(response.processedAt());

        // then: 실제 예산 확인
        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, updatedRoom.getTotalBudget());
        assertEquals(7000L, updatedRoom.getAvailableBudget());

        // then: 신청 상태 확인
        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        // then: 정산 이력이 DB에 저장되었는지 확인
        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                fixture.room().getId(),
                                BudgetType.SETTLEMENT
                        );

        assertEquals(1, histories.size());

        BudgetChange savedChange = histories.get(0);

        assertEquals(response.id(), savedChange.getId());
        assertEquals(5000L, savedChange.getChangeBudget());
        assertEquals(3000L, savedChange.getChangedBudget());
        assertEquals("장비 구매 정산", savedChange.getReason());
        assertNotNull(savedChange.getCreatedAt());
    }

    @Test
    @DisplayName("정산 등록 실패 - 승인금액을 초과하면 예산과 신청 상태가 유지된다")
    void createBudgetChange_exceedsApprovedAmount() {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(
                        5001L,
                        "승인금액 초과"
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.request().getId(),
                        request
                )
        );

        // then
        assertEquals(
                ErrorCode.SETTLEMENT_AMOUNT_EXCEEDS_APPROVED,
                exception.getErrorCode()
        );

        BudgetRequest unchangedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.APPROVE.name(),
                unchangedRequest.getStatus()
        );

        Room unchangedRoom =
                roomRepository.findById(fixture.room().getId())
                        .orElseThrow();

        assertEquals(10000L, unchangedRoom.getTotalBudget());
        assertEquals(5000L, unchangedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                fixture.room().getId(),
                                BudgetType.SETTLEMENT
                        );

        assertTrue(histories.isEmpty());
    }

    @Test
    @DisplayName("정산 등록 실패 - 승인되지 않은 신청은 정산할 수 없다")
    void createBudgetChange_notApproved() {
        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createMembership(user, room, Authority.OWNER);

        BudgetRequest request = new BudgetRequest(
                room,
                user,
                "미승인 신청",
                3000L
        );

        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        BudgetChangeCreateRequest createRequest =
                new BudgetChangeCreateRequest(
                        2000L,
                        "정산 시도"
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        room.getId(),
                        user.getId(),
                        savedRequest.getId(),
                        createRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_REQUEST_NOT_APPROVED,
                exception.getErrorCode()
        );

        BudgetRequest unchangedRequest =
                budgetRequestRepository.findById(savedRequest.getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.REQUEST.name(),
                unchangedRequest.getStatus()
        );

        Room unchangedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(10000L, unchangedRoom.getTotalBudget());
        assertEquals(10000L, unchangedRoom.getAvailableBudget());

        assertTrue(
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                room.getId(),
                                BudgetType.SETTLEMENT
                        )
                        .isEmpty()
        );
    }

    @Test
    @DisplayName("정산 등록 성공 - 최소 양수인 1원을 정산한다")
    void createBudgetChange_minimumPositiveAmount() {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(
                        1L,
                        "최소 금액 정산"
                );

        // when
        BudgetChangeCreateResponse response =
                budgetChangeService.createBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.request().getId(),
                        request
                );

        // then
        assertEquals(5000L, response.changeBudget());
        assertEquals(1L, response.changedBudget());

        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(9999L, updatedRoom.getTotalBudget());
        assertEquals(9999L, updatedRoom.getAvailableBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                fixture.room().getId(),
                                BudgetType.SETTLEMENT
                        );

        assertEquals(1, histories.size());
        assertEquals(1L, histories.get(0).getChangedBudget());
    }

    @Test
    @DisplayName("정산 등록 성공 - 정산금액이 승인금액과 동일하다")
    void createBudgetChange_exactlyApprovedAmount() {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(
                        5000L,
                        "승인금액과 동일한 정산"
                );

        // when
        BudgetChangeCreateResponse response =
                budgetChangeService.createBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.request().getId(),
                        request
                );

        // then
        assertEquals(5000L, response.changeBudget());
        assertEquals(5000L, response.changedBudget());

        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(5000L, updatedRoom.getTotalBudget());
        assertEquals(5000L, updatedRoom.getAvailableBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                fixture.room().getId(),
                                BudgetType.SETTLEMENT
                        );

        assertEquals(1, histories.size());
        assertEquals(5000L, histories.get(0).getChangedBudget());
    }

    // =========================
    // 정산 목록 조회
    // =========================

    @Test
    @DisplayName("정산 목록 조회 - SETTLEMENT 타입의 내역만 반환한다")
    void budgetChangeList_onlySettlementRecords() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChange increase = new BudgetChange(
                fixture.room(),
                fixture.user(),
                fixture.user().getName(),
                BudgetType.INCREASE,
                "별도 예산 증액",
                1000L
        );

        budgetChangeRepository.saveAndFlush(increase);

        // when
        BudgetChangeListResponse response =
                budgetChangeService.budgetChangeList(
                        fixture.room().getId(),
                        fixture.user().getId()
                );

        // then
        assertEquals(1, response.changes().size());

        BudgetChangeListResponse.ListItem item =
                response.changes().get(0);

        assertEquals(fixture.budgetChange().getId(), item.id());
        assertEquals(5000L, item.changeBudget());
        assertEquals(3000L, item.changedBudget());
        assertEquals(BudgetType.SETTLEMENT, item.type());
        assertEquals("테스트 사용자", item.userName());
        assertEquals("기존 정산 사유", item.reason());
        assertNotNull(item.processedAt());
    }

    // =========================
    // 정산 상세 조회
    // =========================

    @Test
    @DisplayName("정산 상세 조회 - 저장된 정산 및 신청 정보를 반환한다")
    void budgetChangeDetail_success() {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        BudgetChangeDetailResponse response =
                budgetChangeService.budgetChangeDetail(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId()
                );

        // then
        assertEquals(fixture.budgetChange().getId(), response.id());
        assertEquals(fixture.room().getId(), response.roomId());
        assertEquals(fixture.user().getId(), response.userId());
        assertEquals(fixture.request().getId(), response.requestId());

        assertEquals(5000L, response.changeBudget());
        assertEquals(3000L, response.changedBudget());
        assertEquals(BudgetType.SETTLEMENT, response.budgetType());

        assertEquals("테스트 사용자", response.userName());
        assertEquals(fixture.user().getEmail(), response.userEmail());
        assertEquals("기존 정산 사유", response.reason());

        assertNotNull(response.processedAt());
        assertNotNull(response.requestCreatedAt());
        assertNotNull(response.requestUpdatedAt());
    }

    // =========================
    // 정산 수정
    // =========================

    @Test
    @DisplayName("정산 수정 성공 - 예산, 정산 내역, 신청 정보가 함께 변경된다")
    void updateBudgetChange_success() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        6000L,
                        3500L,
                        fixture.user().getName(),
                        fixture.user().getEmail(),
                        "수정된 정산 사유"
                );

        // when
        BudgetChangeUpdateResponse response =
                budgetChangeService.updateBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId(),
                        request
                );

        // then: 응답 확인
        assertEquals(fixture.budgetChange().getId(), response.id());
        assertEquals(6000L, response.changeBudget());
        assertEquals(3500L, response.changedBudget());
        assertEquals("테스트 사용자", response.userName());
        assertEquals(fixture.user().getEmail(), response.userEmail());
        assertEquals("수정된 정산 사유", response.reason());

        // then: 실제 DB 예산 확인
        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(6500L, updatedRoom.getTotalBudget());
        assertEquals(6500L, updatedRoom.getAvailableBudget());

        // then: 정산 내역 확인
        BudgetChange updatedChange =
                budgetChangeRepository.findById(
                        fixture.budgetChange().getId()
                ).orElseThrow();

        assertEquals(6000L, updatedChange.getChangeBudget());
        assertEquals(3500L, updatedChange.getChangedBudget());
        assertEquals("수정된 정산 사유", updatedChange.getReason());
        assertEquals(BudgetType.SETTLEMENT, updatedChange.getType());

        // then: 연결된 예산 신청도 수정되어야 한다.
        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(6000L, updatedRequest.getRequestedAmount());
        assertEquals("수정된 정산 사유", updatedRequest.getReason());
        assertEquals(
                fixture.user().getId(),
                updatedRequest.getUser().getId()
        );
        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );
    }

    @Test
    @DisplayName("정산 수정 실패 - 신청 금액 변경분이 가용예산을 초과하면 데이터가 유지된다")
    void updateBudgetChange_exceedsAvailableBudget() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        20000L,
                        3500L,
                        fixture.user().getName(),
                        fixture.user().getEmail(),
                        "가용예산 초과 시도"
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId(),
                        request
                )
        );

        // then
        assertEquals(
                ErrorCode.REQUEST_AMOUNT_OVER_BUDGET,
                exception.getErrorCode()
        );

        Room unchangedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, unchangedRoom.getTotalBudget());
        assertEquals(7000L, unchangedRoom.getAvailableBudget());

        BudgetChange unchangedChange =
                budgetChangeRepository.findById(
                        fixture.budgetChange().getId()
                ).orElseThrow();

        assertEquals(5000L, unchangedChange.getChangeBudget());
        assertEquals(3000L, unchangedChange.getChangedBudget());
        assertEquals("기존 정산 사유", unchangedChange.getReason());

        BudgetRequest unchangedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(5000L, unchangedRequest.getRequestedAmount());
        assertEquals("테스트 예산 신청", unchangedRequest.getReason());
        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                unchangedRequest.getStatus()
        );
    }

    @Test
    @DisplayName("정산 수정 성공 - 신청금액 증가분이 가용예산과 동일하다")
    void updateBudgetChange_requestAmountAtAvailableBoundary() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        12000L,
                        3000L,
                        fixture.user().getName(),
                        fixture.user().getEmail(),
                        "신청금액 경계값"
                );

        // when
        BudgetChangeUpdateResponse response =
                budgetChangeService.updateBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId(),
                        request
                );

        // then
        assertEquals(12000L, response.changeBudget());

        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, updatedRoom.getTotalBudget());
        assertEquals(7000L, updatedRoom.getAvailableBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(12000L, updatedRequest.getRequestedAmount());
        assertEquals("신청금액 경계값", updatedRequest.getReason());

        BudgetChange updatedChange =
                budgetChangeRepository.findById(
                        fixture.budgetChange().getId()
                ).orElseThrow();

        assertEquals(12000L, updatedChange.getChangeBudget());
        assertEquals(3000L, updatedChange.getChangedBudget());
    }

    @Test
    @DisplayName("정산 수정 실패 - 신청금액 증가분이 가용예산보다 1원 많다")
    void updateBudgetChange_requestAmountExceedsAvailableByOne() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        12001L,
                        3000L,
                        fixture.user().getName(),
                        fixture.user().getEmail(),
                        "신청금액 1원 초과"
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId(),
                        request
                )
        );

        // then
        assertEquals(
                ErrorCode.REQUEST_AMOUNT_OVER_BUDGET,
                exception.getErrorCode()
        );

        Room unchangedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, unchangedRoom.getTotalBudget());
        assertEquals(7000L, unchangedRoom.getAvailableBudget());

        BudgetChange unchangedChange =
                budgetChangeRepository.findById(
                        fixture.budgetChange().getId()
                ).orElseThrow();

        assertEquals(5000L, unchangedChange.getChangeBudget());
        assertEquals(3000L, unchangedChange.getChangedBudget());
        assertEquals("기존 정산 사유", unchangedChange.getReason());

        BudgetRequest unchangedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(5000L, unchangedRequest.getRequestedAmount());
        assertEquals("테스트 예산 신청", unchangedRequest.getReason());
    }

    @Test
    @DisplayName("정산 수정 성공 - 정산금액 변경 후 가용예산이 정확히 0원이 된다")
    void updateBudgetChange_exactlyConsumesAvailableBudget() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        12000L,
                        10000L,
                        fixture.user().getName(),
                        fixture.user().getEmail(),
                        "가용예산 전액 사용"
                );

        // when
        BudgetChangeUpdateResponse response =
                budgetChangeService.updateBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId(),
                        request
                );

        // then
        assertEquals(12000L, response.changeBudget());
        assertEquals(10000L, response.changedBudget());

        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(0L, updatedRoom.getTotalBudget());
        assertEquals(0L, updatedRoom.getAvailableBudget());

        BudgetChange updatedChange =
                budgetChangeRepository.findById(
                        fixture.budgetChange().getId()
                ).orElseThrow();

        assertEquals(10000L, updatedChange.getChangedBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(12000L, updatedRequest.getRequestedAmount());
    }

    @Test
    @DisplayName("정산 수정 실패 - 가용예산을 1원 초과하여 예산과 신청 정보가 유지된다")
    void updateBudgetChange_changedBudgetExceedsAvailableByOne() {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        12000L,
                        10001L,
                        fixture.user().getName(),
                        fixture.user().getEmail(),
                        "가용예산 1원 초과"
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.budgetChange().getId(),
                        request
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_EXCEEDED,
                exception.getErrorCode()
        );

        Room unchangedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, unchangedRoom.getTotalBudget());
        assertEquals(7000L, unchangedRoom.getAvailableBudget());

        BudgetChange unchangedChange =
                budgetChangeRepository.findById(
                        fixture.budgetChange().getId()
                ).orElseThrow();

        assertEquals(5000L, unchangedChange.getChangeBudget());
        assertEquals(3000L, unchangedChange.getChangedBudget());
        assertEquals("기존 정산 사유", unchangedChange.getReason());

        BudgetRequest unchangedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(5000L, unchangedRequest.getRequestedAmount());
        assertEquals("테스트 예산 신청", unchangedRequest.getReason());
        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                unchangedRequest.getStatus()
        );
    }
}