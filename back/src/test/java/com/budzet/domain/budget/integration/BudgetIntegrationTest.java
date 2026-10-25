package com.budzet.domain.budget.integration;

import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Autowired
    private BudgetChangeRepository budgetChangeRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String TEST_PASSWORD = "test-password";

    private record TestActor(User user, String accessToken) {
    }

    // =========================
    // 테스트 데이터 생성
    // =========================

    /**
     * 테스트 사용자를 DB에 저장하고,
     * 로그인 서비스를 이용해 실제 액세스 토큰을 발급받는다.
     *
     * 회원가입 API 자체를 테스트하는 것은 아니다.
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
                "테스트 사용자"
        );

        userRepository.saveAndFlush(user);

        UserLoginResponse loginResponse =
                userService.login(email, TEST_PASSWORD);

        return new TestActor(user, loginResponse.accessToken());
    }

    private Room createRoom(Long totalBudget) {
        String roomName = "API-" + UUID.randomUUID()
                .toString()
                .substring(0, 8);

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

    private MockHttpServletRequestBuilder authenticatedGet(
            String url,
            Long roomId,
            TestActor actor
    ) {
        return get(url, roomId)
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + actor.accessToken()
                );
    }

    private MockHttpServletRequestBuilder authenticatedPatch(
            Long roomId,
            TestActor actor,
            String requestBody
    ) {
        return patch("/rooms/{roomId}/budget", roomId)
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + actor.accessToken()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody);
    }

    // =========================
    // 인증 검증
    // =========================

    @Test
    @DisplayName("인증 정보 없이 예산 조회를 요청하면 401을 반환한다")
    void getBudget_withoutAuthentication_returnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get("/rooms/{roomId}/budget", 1L)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 정보 없이 예산 변경 이력 조회를 요청하면 401을 반환한다")
    void getBudgetHistory_withoutAuthentication_returnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get("/rooms/{roomId}/budget/history", 1L)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 정보 없이 예산 수정을 요청하면 401을 반환한다")
    void updateBudget_withoutAuthentication_returnsUnauthorized()
            throws Exception {

        String requestBody = """
                {
                    "totalBudget": 5000,
                    "budgetType": "INCREASE",
                    "reason": "인증 테스트"
                }
                """;

        mockMvc.perform(
                        patch("/rooms/{roomId}/budget", 1L)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isUnauthorized());
    }

    // =========================
    // 예산 조회 API
    // =========================

    @Test
    @DisplayName("예산 조회 성공 - 인증된 사용자의 예산을 반환한다")
    void getBudget_success() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(10000L);

        createMembership(actor.user(), room, Authority.OWNER);

        // when & then
        mockMvc.perform(
                        authenticatedGet(
                                "/rooms/{roomId}/budget",
                                room.getId(),
                                actor
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message")
                        .value("예산 조회에 성공하였습니다."))
                .andExpect(jsonPath("$.data.roomId")
                        .value(room.getId()))
                .andExpect(jsonPath("$.data.roomName")
                        .value(room.getName()))
                .andExpect(jsonPath("$.data.totalBudget")
                        .value(10000))
                .andExpect(jsonPath("$.data.availableBudget")
                        .value(10000))
                .andExpect(jsonPath("$.data.reserveBudget")
                        .value(0))
                .andExpect(jsonPath("$.data.currency")
                        .value("KRW"));
    }

    @Test
    @DisplayName("예산 조회 실패 - 모임에 속하지 않은 사용자는 조회할 수 없다")
    void getBudget_userNotJoinedRoom() throws Exception {
        // given
        TestActor owner = createAuthenticatedUser();
        TestActor outsider = createAuthenticatedUser();

        Room room = createRoom(10000L);

        createMembership(owner.user(), room, Authority.OWNER);

        // outsider는 해당 모임에 가입되어 있지 않다.

        // when & then
        mockMvc.perform(
                        authenticatedGet(
                                "/rooms/{roomId}/budget",
                                room.getId(),
                                outsider
                        )
                )
                .andExpect(status().isForbidden());
    }

    // =========================
    // 예산 변경 이력 조회 API
    // =========================

    @Test
    @DisplayName("예산 변경 이력 조회 성공 - DB에 저장된 이력을 반환한다")
    void getBudgetHistory_success() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(10000L);

        createMembership(actor.user(), room, Authority.OWNER);

        BudgetChange budgetChange = new BudgetChange(
                room,
                actor.user(),
                actor.user().getName(),
                BudgetType.INCREASE,
                "초기 예산 증액",
                2000L
        );

        BudgetChange savedChange =
                budgetChangeRepository.saveAndFlush(budgetChange);

        // when & then
        mockMvc.perform(
                        authenticatedGet(
                                "/rooms/{roomId}/budget/history",
                                room.getId(),
                                actor
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message")
                        .value("예산 변경 목록 조회에 성공하였습니다."))
                .andExpect(jsonPath("$.data.history", hasSize(1)))
                .andExpect(jsonPath("$.data.history[0].id")
                        .value(savedChange.getId()))
                .andExpect(jsonPath("$.data.history[0].changeBudget")
                        .value(2000))
                .andExpect(jsonPath("$.data.history[0].changedBudget")
                        .value(2000))
                .andExpect(jsonPath("$.data.history[0].type")
                        .value("INCREASE"))
                .andExpect(jsonPath("$.data.history[0].userName")
                        .value("테스트 사용자"))
                .andExpect(jsonPath("$.data.history[0].reason")
                        .value("초기 예산 증액"))
                .andExpect(jsonPath("$.data.history[0].processedAt")
                        .exists());
    }

    @Test
    @DisplayName("예산 변경 이력 조회 실패 - 모임에 속하지 않은 사용자는 조회할 수 없다")
    void getBudgetHistory_userNotJoinedRoom() throws Exception {
        // given
        TestActor owner = createAuthenticatedUser();
        TestActor outsider = createAuthenticatedUser();

        Room room = createRoom(10000L);

        createMembership(owner.user(), room, Authority.OWNER);

        // when & then
        mockMvc.perform(
                        authenticatedGet(
                                "/rooms/{roomId}/budget/history",
                                room.getId(),
                                outsider
                        )
                )
                .andExpect(status().isForbidden());
    }

    // =========================
    // 예산 증액 API
    // =========================

    @Test
    @DisplayName("예산 증액 성공 - 예산과 변경 이력이 DB에 저장된다")
    void updateBudget_increase_success() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(10000L);

        createMembership(actor.user(), room, Authority.OWNER);

        String requestBody = """
                {
                    "totalBudget": 5000,
                    "budgetType": "INCREASE",
                    "reason": "지원금 추가"
                }
                """;

        // when
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message")
                        .value("예산 변경에 성공하였습니다."))
                .andExpect(jsonPath("$.data.roomId")
                        .value(room.getId()))
                .andExpect(jsonPath("$.data.totalBudget")
                        .value(15000));

        // then: 실제 DB의 예산 확인
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(15000L, updatedRoom.getTotalBudget());
        assertEquals(15000L, updatedRoom.getAvailableBudget());

        // then: 실제 DB의 변경 이력 확인
        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertEquals(1, histories.size());

        BudgetChange history = histories.get(0);

        assertEquals(actor.user().getId(), history.getUser().getId());
        assertEquals(room.getId(), history.getRoom().getId());
        assertEquals("테스트 사용자", history.getUserName());
        assertEquals(BudgetType.INCREASE, history.getType());
        assertEquals(5000L, history.getChangeBudget());
        assertEquals(5000L, history.getChangedBudget());
        assertEquals("지원금 추가", history.getReason());
        assertNotNull(history.getCreatedAt());
    }

    // =========================
    // 예산 감액 API
    // =========================

    @Test
    @DisplayName("예산 감액 성공 - 예산과 변경 이력이 DB에 저장된다")
    void updateBudget_decrease_success() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(10000L);

        createMembership(actor.user(), room, Authority.OPERATOR);

        String requestBody = """
                {
                    "totalBudget": 4000,
                    "budgetType": "DECREASE",
                    "reason": "비품 구매"
                }
                """;

        // when
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalBudget")
                        .value(6000));

        // then
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(6000L, updatedRoom.getTotalBudget());
        assertEquals(6000L, updatedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertEquals(1, histories.size());

        BudgetChange history = histories.get(0);

        assertEquals(BudgetType.DECREASE, history.getType());
        assertEquals(4000L, history.getChangeBudget());
        assertEquals(4000L, history.getChangedBudget());
        assertEquals("비품 구매", history.getReason());
    }

    @Test
    @DisplayName("예산 감액 성공 - 가용예산보다 1원 적게 감액할 수 있다")
    void updateBudget_decrease_justBelowAvailableBudget()
            throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(5000L);

        createMembership(actor.user(), room, Authority.OWNER);

        String requestBody = """
                {
                    "totalBudget": 4999,
                    "budgetType": "DECREASE",
                    "reason": "경계값 테스트"
                }
                """;

        // when
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalBudget").value(1));

        // then
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

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
    @DisplayName("예산 감액 성공 - 가용예산과 같은 금액을 감액할 수 있다")
    void updateBudget_decrease_exactlyAvailableBudget()
            throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(5000L);

        createMembership(actor.user(), room, Authority.OWNER);

        String requestBody = """
                {
                    "totalBudget": 5000,
                    "budgetType": "DECREASE",
                    "reason": "경계값 테스트"
                }
                """;

        // when
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalBudget").value(0));

        // then
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

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
    // 예산 수정 인가 검증 API
    // =========================

    @Test
    @DisplayName("예산 수정 실패 - MEMBER는 예산을 수정할 수 없다")
    void updateBudget_memberForbidden() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(10000L);

        createMembership(actor.user(), room, Authority.MEMBER);

        String requestBody = """
                {
                    "totalBudget": 5000,
                    "budgetType": "INCREASE",
                    "reason": "권한 없는 증액 시도"
                }
                """;

        // when & then
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isForbidden());

        // 예산이 변경되지 않았는지 확인
        Room unchangedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(10000L, unchangedRoom.getTotalBudget());
        assertEquals(10000L, unchangedRoom.getAvailableBudget());

        // 변경 이력이 저장되지 않았는지 확인
        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertTrue(histories.isEmpty());
    }

    // =========================
    // 예산 경계값 및 예외 처리 API
    // =========================

    @Test
    @DisplayName("예산 수정 실패 - 가용예산 초과 감액 시 예산이 유지된다")
    void updateBudget_exceedAvailableBudget() throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(5000L);

        createMembership(actor.user(), room, Authority.OWNER);

        String requestBody = """
                {
                    "totalBudget": 5001,
                    "budgetType": "DECREASE",
                    "reason": "가용예산 초과"
                }
                """;

        // when & then
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isBadRequest());

        Room unchangedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(5000L, unchangedRoom.getTotalBudget());
        assertEquals(5000L, unchangedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertTrue(histories.isEmpty());
    }

    @Test
    @DisplayName("예산 수정 실패 - 0원 입력 시 400을 반환하고 DB를 변경하지 않는다")
    void updateBudget_invalidAmount_returnsBadRequest()
            throws Exception {
        // given
        TestActor actor = createAuthenticatedUser();
        Room room = createRoom(5000L);

        createMembership(actor.user(), room, Authority.OWNER);

        String requestBody = """
                {
                    "totalBudget": 0,
                    "budgetType": "INCREASE",
                    "reason": "잘못된 금액"
                }
                """;

        // when & then
        mockMvc.perform(
                        authenticatedPatch(
                                room.getId(),
                                actor,
                                requestBody
                        )
                )
                .andExpect(status().isBadRequest());

        Room unchangedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        assertEquals(5000L, unchangedRoom.getTotalBudget());
        assertEquals(5000L, unchangedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository.findAllByRoomIdOrderByCreatedAtDesc(
                        room.getId()
                );

        assertTrue(histories.isEmpty());
    }
}