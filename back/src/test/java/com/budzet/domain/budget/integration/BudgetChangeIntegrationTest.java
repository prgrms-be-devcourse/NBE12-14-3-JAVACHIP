package com.budzet.domain.budget.integration;

import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.dto.UserLoginResponse;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import com.budzet.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller -> Service -> Repository -> H2 DB 흐름을 HTTP API로 검증한다.
 * 인증 필터를 비활성화하지 않으며, 테스트 사용자를 실제 로그인시켜 발급받은
 * Access Token을 Authorization 헤더에 전달한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetChangeIntegrationTest {

    private static final String TEST_PASSWORD = "test-password";
    private static final String TEST_USER_NAME = "테스트 사용자";

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---------------------------------------------------------------------
    // 테스트 데이터 생성
    // ---------------------------------------------------------------------

    /**
     * 테스트 사용자를 저장하고 실제 로그인으로 Access Token을 발급받는다.
     */
    private TestActor createAuthenticatedUser() {
        String email = "b-"
                + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
                + "@test.com";

        User user = new User(
                email,
                passwordEncoder.encode(TEST_PASSWORD),
                TEST_USER_NAME
        );

        User savedUser = userRepository.saveAndFlush(user);

        UserLoginResponse loginResponse =
                userService.login(email, TEST_PASSWORD);

        return new TestActor(savedUser, loginResponse.accessToken());
    }

    private Room createRoom(Long totalBudget) {
        String roomName =
                "정산API-" + UUID.randomUUID().toString().substring(0, 8);

        Room room = Room.create(
                roomName,
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
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(totalBudget);

        createMembership(actor.user(), room, Authority.OWNER);

        // 승인된 신청이 예산을 예약한 상황을 준비한다.
        room.updateAvailableBudget(-requestedAmount);

        Room savedRoom = roomRepository.saveAndFlush(room);

        BudgetRequest request = new BudgetRequest(
                savedRoom,
                actor.user(),
                "테스트 예산 신청",
                requestedAmount
        );

        request.approveRequest();

        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        return new ApprovedRequestFixture(
                actor,
                savedRoom,
                savedRequest
        );
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
        User user = fixture.actor().user();

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
                fixture.actor(),
                savedRoom,
                savedRequest,
                savedChange
        );
    }

    /**
     * Access Token을 Authorization 헤더에 추가한다.
     */
    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request,
            TestActor actor
    ) {
        return request.header(
                "Authorization",
                "Bearer " + actor.accessToken()
        );
    }

    private String createRequestJson(
            long changedBudget,
            String reason
    ) {
        return """
                {
                  "changedBudget": %d,
                  "reason": "%s"
                }
                """.formatted(changedBudget, reason);
    }

    private String updateRequestJson(
            long changeBudget,
            long changedBudget,
            String userName,
            String userEmail,
            String reason
    ) {
        return """
                {
                  "changeBudget": %d,
                  "changedBudget": %d,
                  "userName": "%s",
                  "userEmail": "%s",
                  "reason": "%s"
                }
                """.formatted(
                changeBudget,
                changedBudget,
                userName,
                userEmail,
                reason
        );
    }

    private List<BudgetChange> settlementHistories(Long roomId) {
        return budgetChangeRepository
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                        roomId,
                        BudgetType.SETTLEMENT
                );
    }

    private record TestActor(
            User user,
            String accessToken
    ) {
    }

    private record ApprovedRequestFixture(
            TestActor actor,
            Room room,
            BudgetRequest request
    ) {
    }

    private record SettlementFixture(
            TestActor actor,
            Room room,
            BudgetRequest request,
            BudgetChange budgetChange
    ) {
    }

    // ---------------------------------------------------------------------
    // 인증 테스트
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정산 등록 API - Access Token이 없으면 401을 반환한다")
    void createBudgetChange_requiresAuthentication() throws Exception {
        mockMvc.perform(
                        post("/rooms/1/budget/changes/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        createRequestJson(
                                                3000L,
                                                "인증 테스트"
                                        )
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.errorCode").value("UNAUTHORIZED")
                );
    }

    @Test
    @DisplayName("정산 목록 조회 API - Access Token이 없으면 401을 반환한다")
    void budgetChangeList_requiresAuthentication() throws Exception {
        mockMvc.perform(
                        get("/rooms/1/budget/changes/settlement")
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.errorCode").value("UNAUTHORIZED")
                );
    }

    @Test
    @DisplayName("정산 상세 조회 API - Access Token이 없으면 401을 반환한다")
    void budgetChangeDetail_requiresAuthentication() throws Exception {
        mockMvc.perform(
                        get("/rooms/1/budget/changes/1")
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.errorCode").value("UNAUTHORIZED")
                );
    }

    @Test
    @DisplayName("정산 수정 API - Access Token이 없으면 401을 반환한다")
    void updateBudgetChange_requiresAuthentication() throws Exception {
        mockMvc.perform(
                        put("/rooms/1/budget/changes/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                6000L,
                                                3500L,
                                                TEST_USER_NAME,
                                                "test@test.com",
                                                "인증 테스트"
                                        )
                                )
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.errorCode").value("UNAUTHORIZED")
                );
    }

    // ---------------------------------------------------------------------
    // 정산 등록
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정산 등록 성공 - 정산 내역, 예산, 신청 상태가 DB에 반영된다")
    void createBudgetChange_success() throws Exception {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        // when
        mockMvc.perform(
                        authenticated(
                                post(
                                        "/rooms/{roomId}/budget/changes/{requestId}",
                                        fixture.room().getId(),
                                        fixture.request().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        createRequestJson(
                                                3000L,
                                                "장비 구매 정산"
                                        )
                                )
                )
                // then: API 응답
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultCode").value(201))
                .andExpect(jsonPath("$.message").value("정산 처리 성공"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(
                        jsonPath("$.data.requestId")
                                .value(fixture.request().getId())
                )
                .andExpect(
                        jsonPath("$.data.userId")
                                .value(fixture.actor().user().getId())
                )
                .andExpect(jsonPath("$.data.changeBudget").value(5000))
                .andExpect(jsonPath("$.data.changedBudget").value(3000))
                .andExpect(jsonPath("$.data.budgetType").value("SETTLEMENT"))
                .andExpect(jsonPath("$.data.userName").value(TEST_USER_NAME))
                .andExpect(jsonPath("$.data.reason").value("장비 구매 정산"))
                .andExpect(jsonPath("$.data.processedAt").isNotEmpty());

        // then: 실제 예산
        Room updatedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, updatedRoom.getTotalBudget());
        assertEquals(7000L, updatedRoom.getAvailableBudget());

        // then: 신청 상태
        BudgetRequest updatedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        // then: 정산 이력
        List<BudgetChange> histories =
                settlementHistories(fixture.room().getId());

        assertEquals(1, histories.size());

        BudgetChange savedChange = histories.get(0);

        assertEquals(5000L, savedChange.getChangeBudget());
        assertEquals(3000L, savedChange.getChangedBudget());
        assertEquals("장비 구매 정산", savedChange.getReason());
        assertNotNull(savedChange.getCreatedAt());
    }

    @Test
    @DisplayName("정산 등록 실패 - 승인금액을 초과하면 예산과 신청 상태가 유지된다")
    void createBudgetChange_exceedsApprovedAmount() throws Exception {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        // when
        mockMvc.perform(
                        authenticated(
                                post(
                                        "/rooms/{roomId}/budget/changes/{requestId}",
                                        fixture.room().getId(),
                                        fixture.request().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        createRequestJson(
                                                5001L,
                                                "승인금액 초과"
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400));

        // then: 신청 상태 유지
        BudgetRequest unchangedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(
                BudgetRequestType.APPROVE.name(),
                unchangedRequest.getStatus()
        );

        // then: 예산 유지
        Room unchangedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(10000L, unchangedRoom.getTotalBudget());
        assertEquals(5000L, unchangedRoom.getAvailableBudget());

        // then: 정산 내역이 생성되지 않음
        assertTrue(
                settlementHistories(fixture.room().getId()).isEmpty()
        );
    }

    @Test
    @DisplayName("정산 등록 실패 - 승인되지 않은 신청은 정산할 수 없다")
    void createBudgetChange_notApproved() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(10000L);

        createMembership(actor.user(), room, Authority.OWNER);

        BudgetRequest request = new BudgetRequest(
                room,
                actor.user(),
                "미승인 신청",
                3000L
        );

        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        // when
        mockMvc.perform(
                        authenticated(
                                post(
                                        "/rooms/{roomId}/budget/changes/{requestId}",
                                        room.getId(),
                                        savedRequest.getId()
                                ),
                                actor
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        createRequestJson(
                                                2000L,
                                                "정산 시도"
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400));

        // then: 신청 상태 유지
        BudgetRequest unchangedRequest = budgetRequestRepository
                .findById(savedRequest.getId())
                .orElseThrow();

        assertEquals(
                BudgetRequestType.REQUEST.name(),
                unchangedRequest.getStatus()
        );

        // then: 예산 유지
        Room unchangedRoom = roomRepository
                .findById(room.getId())
                .orElseThrow();

        assertEquals(10000L, unchangedRoom.getTotalBudget());
        assertEquals(10000L, unchangedRoom.getAvailableBudget());

        // then: 정산 내역이 생성되지 않음
        assertTrue(settlementHistories(room.getId()).isEmpty());
    }

    @Test
    @DisplayName("정산 등록 성공 - 최소 양수인 1원을 정산한다")
    void createBudgetChange_minimumPositiveAmount() throws Exception {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        // when
        mockMvc.perform(
                        authenticated(
                                post(
                                        "/rooms/{roomId}/budget/changes/{requestId}",
                                        fixture.room().getId(),
                                        fixture.request().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        createRequestJson(
                                                1L,
                                                "최소 금액 정산"
                                        )
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.changeBudget").value(5000))
                .andExpect(jsonPath("$.data.changedBudget").value(1));

        // then: 예산
        Room updatedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(9999L, updatedRoom.getTotalBudget());
        assertEquals(9999L, updatedRoom.getAvailableBudget());

        // then: 신청 상태
        BudgetRequest updatedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        // then: 정산 이력
        List<BudgetChange> histories =
                settlementHistories(fixture.room().getId());

        assertEquals(1, histories.size());
        assertEquals(1L, histories.get(0).getChangedBudget());
    }

    @Test
    @DisplayName("정산 등록 성공 - 정산금액이 승인금액과 동일하다")
    void createBudgetChange_exactlyApprovedAmount() throws Exception {
        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        // when
        mockMvc.perform(
                        authenticated(
                                post(
                                        "/rooms/{roomId}/budget/changes/{requestId}",
                                        fixture.room().getId(),
                                        fixture.request().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        createRequestJson(
                                                5000L,
                                                "승인금액과 동일한 정산"
                                        )
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.changeBudget").value(5000))
                .andExpect(jsonPath("$.data.changedBudget").value(5000));

        // then: 예산
        Room updatedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(5000L, updatedRoom.getTotalBudget());
        assertEquals(5000L, updatedRoom.getAvailableBudget());

        // then: 신청 상태
        BudgetRequest updatedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        // then: 정산 이력
        List<BudgetChange> histories =
                settlementHistories(fixture.room().getId());

        assertEquals(1, histories.size());
        assertEquals(5000L, histories.get(0).getChangedBudget());
    }

    // ---------------------------------------------------------------------
    // 정산 목록 조회
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정산 목록 조회 - SETTLEMENT 타입의 내역만 반환한다")
    void budgetChangeList_onlySettlementRecords() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        BudgetChange increase = new BudgetChange(
                fixture.room(),
                fixture.actor().user(),
                fixture.actor().user().getName(),
                BudgetType.INCREASE,
                "별도 예산 증액",
                1000L
        );

        budgetChangeRepository.saveAndFlush(increase);

        // when
        mockMvc.perform(
                        authenticated(
                                get(
                                        "/rooms/{roomId}/budget/changes/settlement",
                                        fixture.room().getId()
                                ),
                                fixture.actor()
                        )
                )
                // then: API 응답
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(
                        jsonPath("$.message").value("정산 내역 조회 성공")
                )
                .andExpect(
                        jsonPath("$.data.changes.length()").value(1)
                )
                .andExpect(
                        jsonPath("$.data.changes[0].id")
                                .value(fixture.budgetChange().getId())
                )
                .andExpect(
                        jsonPath("$.data.changes[0].changeBudget").value(5000)
                )
                .andExpect(
                        jsonPath("$.data.changes[0].changedBudget").value(3000)
                )
                .andExpect(
                        jsonPath("$.data.changes[0].type").value("SETTLEMENT")
                )
                .andExpect(
                        jsonPath("$.data.changes[0].userName")
                                .value(TEST_USER_NAME)
                )
                .andExpect(
                        jsonPath("$.data.changes[0].reason")
                                .value("기존 정산 사유")
                )
                .andExpect(
                        jsonPath("$.data.changes[0].processedAt").isNotEmpty()
                );
    }

    // ---------------------------------------------------------------------
    // 정산 상세 조회
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정산 상세 조회 - 저장된 정산 및 신청 정보를 반환한다")
    void budgetChangeDetail_success() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        mockMvc.perform(
                        authenticated(
                                get(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                )
                // then: API 응답
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(
                        jsonPath("$.message").value("정산 내역 상세조회 성공")
                )
                .andExpect(
                        jsonPath("$.data.id")
                                .value(fixture.budgetChange().getId())
                )
                .andExpect(
                        jsonPath("$.data.roomId")
                                .value(fixture.room().getId())
                )
                .andExpect(
                        jsonPath("$.data.userId")
                                .value(fixture.actor().user().getId())
                )
                .andExpect(
                        jsonPath("$.data.requestId")
                                .value(fixture.request().getId())
                )
                .andExpect(
                        jsonPath("$.data.changeBudget").value(5000)
                )
                .andExpect(
                        jsonPath("$.data.changedBudget").value(3000)
                )
                .andExpect(
                        jsonPath("$.data.budgetType").value("SETTLEMENT")
                )
                .andExpect(
                        jsonPath("$.data.userName").value(TEST_USER_NAME)
                )
                .andExpect(
                        jsonPath("$.data.userEmail")
                                .value(fixture.actor().user().getEmail())
                )
                .andExpect(
                        jsonPath("$.data.reason").value("기존 정산 사유")
                )
                .andExpect(
                        jsonPath("$.data.processedAt").isNotEmpty()
                )
                .andExpect(
                        jsonPath("$.data.requestCreatedAt").isNotEmpty()
                )
                .andExpect(
                        jsonPath("$.data.requestUpdatedAt").isNotEmpty()
                );
    }

    // ---------------------------------------------------------------------
    // 정산 수정
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("정산 수정 성공 - 예산, 정산 내역, 신청 정보가 함께 변경된다")
    void updateBudgetChange_success() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();
        String email = fixture.actor().user().getEmail();

        // when
        mockMvc.perform(
                        authenticated(
                                put(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                6000L,
                                                3500L,
                                                TEST_USER_NAME,
                                                email,
                                                "수정된 정산 사유"
                                        )
                                )
                )
                // then: API 응답
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(
                        jsonPath("$.message").value("정산 내역 수정 성공")
                )
                .andExpect(
                        jsonPath("$.data.id")
                                .value(fixture.budgetChange().getId())
                )
                .andExpect(
                        jsonPath("$.data.changeBudget").value(6000)
                )
                .andExpect(
                        jsonPath("$.data.changedBudget").value(3500)
                )
                .andExpect(
                        jsonPath("$.data.userName").value(TEST_USER_NAME)
                )
                .andExpect(
                        jsonPath("$.data.userEmail").value(email)
                )
                .andExpect(
                        jsonPath("$.data.reason").value("수정된 정산 사유")
                );

        // then: 실제 DB 예산
        Room updatedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(6500L, updatedRoom.getTotalBudget());
        assertEquals(6500L, updatedRoom.getAvailableBudget());

        // then: 정산 내역
        BudgetChange updatedChange = budgetChangeRepository
                .findById(fixture.budgetChange().getId())
                .orElseThrow();

        assertEquals(6000L, updatedChange.getChangeBudget());
        assertEquals(3500L, updatedChange.getChangedBudget());
        assertEquals("수정된 정산 사유", updatedChange.getReason());
        assertEquals(BudgetType.SETTLEMENT, updatedChange.getType());

        // then: 연결된 예산 신청
        BudgetRequest updatedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(6000L, updatedRequest.getRequestedAmount());
        assertEquals("수정된 정산 사유", updatedRequest.getReason());
        assertEquals(
                fixture.actor().user().getId(),
                updatedRequest.getUser().getId()
        );
        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );
    }

    @Test
    @DisplayName("정산 수정 실패 - 신청 금액 변경분이 가용예산을 초과하면 데이터가 유지된다")
    void updateBudgetChange_exceedsAvailableBudget() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        mockMvc.perform(
                        authenticated(
                                put(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                20000L,
                                                3500L,
                                                TEST_USER_NAME,
                                                fixture.actor().user().getEmail(),
                                                "가용예산 초과 시도"
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400));

        // then: 예산 유지
        Room unchangedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, unchangedRoom.getTotalBudget());
        assertEquals(7000L, unchangedRoom.getAvailableBudget());

        // then: 정산 내역 유지
        BudgetChange unchangedChange = budgetChangeRepository
                .findById(fixture.budgetChange().getId())
                .orElseThrow();

        assertEquals(5000L, unchangedChange.getChangeBudget());
        assertEquals(3000L, unchangedChange.getChangedBudget());
        assertEquals("기존 정산 사유", unchangedChange.getReason());

        // then: 신청 정보 유지
        BudgetRequest unchangedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
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
    void updateBudgetChange_requestAmountAtAvailableBoundary() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        mockMvc.perform(
                        authenticated(
                                put(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                12000L,
                                                3000L,
                                                TEST_USER_NAME,
                                                fixture.actor().user().getEmail(),
                                                "신청금액 경계값"
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeBudget").value(12000));

        // then: 예산
        Room updatedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, updatedRoom.getTotalBudget());
        assertEquals(7000L, updatedRoom.getAvailableBudget());

        // then: 신청 내역
        BudgetRequest updatedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(12000L, updatedRequest.getRequestedAmount());
        assertEquals("신청금액 경계값", updatedRequest.getReason());

        // then: 정산 내역
        BudgetChange updatedChange = budgetChangeRepository
                .findById(fixture.budgetChange().getId())
                .orElseThrow();

        assertEquals(12000L, updatedChange.getChangeBudget());
        assertEquals(3000L, updatedChange.getChangedBudget());
    }

    @Test
    @DisplayName("정산 수정 실패 - 신청금액 증가분이 가용예산보다 1원 많다")
    void updateBudgetChange_requestAmountExceedsAvailableByOne() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        mockMvc.perform(
                        authenticated(
                                put(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                12001L,
                                                3000L,
                                                TEST_USER_NAME,
                                                fixture.actor().user().getEmail(),
                                                "신청금액 1원 초과"
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400));

        // then: 예산 유지
        Room unchangedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, unchangedRoom.getTotalBudget());
        assertEquals(7000L, unchangedRoom.getAvailableBudget());

        // then: 정산 내역 유지
        BudgetChange unchangedChange = budgetChangeRepository
                .findById(fixture.budgetChange().getId())
                .orElseThrow();

        assertEquals(5000L, unchangedChange.getChangeBudget());
        assertEquals(3000L, unchangedChange.getChangedBudget());
        assertEquals("기존 정산 사유", unchangedChange.getReason());

        // then: 신청 내역 유지
        BudgetRequest unchangedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(5000L, unchangedRequest.getRequestedAmount());
        assertEquals("테스트 예산 신청", unchangedRequest.getReason());
    }

    @Test
    @DisplayName("정산 수정 성공 - 정산금액 변경 후 가용예산이 정확히 0원이 된다")
    void updateBudgetChange_exactlyConsumesAvailableBudget() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        mockMvc.perform(
                        authenticated(
                                put(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                12000L,
                                                10000L,
                                                TEST_USER_NAME,
                                                fixture.actor().user().getEmail(),
                                                "가용예산 전액 사용"
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeBudget").value(12000))
                .andExpect(jsonPath("$.data.changedBudget").value(10000));

        // then: 예산
        Room updatedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(0L, updatedRoom.getTotalBudget());
        assertEquals(0L, updatedRoom.getAvailableBudget());

        // then: 정산 내역
        BudgetChange updatedChange = budgetChangeRepository
                .findById(fixture.budgetChange().getId())
                .orElseThrow();

        assertEquals(10000L, updatedChange.getChangedBudget());

        // then: 신청 내역
        BudgetRequest updatedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(12000L, updatedRequest.getRequestedAmount());
    }

    @Test
    @DisplayName("정산 수정 실패 - 가용예산을 1원 초과하여 예산과 신청 정보가 유지된다")
    void updateBudgetChange_changedBudgetExceedsAvailableByOne() throws Exception {
        // given
        SettlementFixture fixture = createSettledFixture();

        // when
        mockMvc.perform(
                        authenticated(
                                put(
                                        "/rooms/{roomId}/budget/changes/{changeId}",
                                        fixture.room().getId(),
                                        fixture.budgetChange().getId()
                                ),
                                fixture.actor()
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        updateRequestJson(
                                                12000L,
                                                10001L,
                                                TEST_USER_NAME,
                                                fixture.actor().user().getEmail(),
                                                "가용예산 1원 초과"
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400));

        // then: 예산 유지
        Room unchangedRoom = roomRepository
                .findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, unchangedRoom.getTotalBudget());
        assertEquals(7000L, unchangedRoom.getAvailableBudget());

        // then: 정산 내역 유지
        BudgetChange unchangedChange = budgetChangeRepository
                .findById(fixture.budgetChange().getId())
                .orElseThrow();

        assertEquals(5000L, unchangedChange.getChangeBudget());
        assertEquals(3000L, unchangedChange.getChangedBudget());
        assertEquals("기존 정산 사유", unchangedChange.getReason());

        // then: 신청 내역 유지
        BudgetRequest unchangedRequest = budgetRequestRepository
                .findById(fixture.request().getId())
                .orElseThrow();

        assertEquals(5000L, unchangedRequest.getRequestedAmount());
        assertEquals("테스트 예산 신청", unchangedRequest.getReason());
        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                unchangedRequest.getStatus()
        );
    }
}