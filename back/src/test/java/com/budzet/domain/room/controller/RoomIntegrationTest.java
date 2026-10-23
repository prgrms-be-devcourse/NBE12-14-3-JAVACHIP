package com.budzet.domain.room.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budzet.domain.room.dto.RoomCreateRequest;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.dto.UserLoginRequest;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRoomConnectionRepository connectionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> roomIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 요청의 트랜잭션이 종료된 뒤, 이 테스트에서 만든 데이터만 정리한다.
        for (Long roomId : roomIds) {
            roomRepository.deleteById(roomId);
        }
        for (Long userId : userIds) {
            userRepository.deleteById(userId);
        }
    }

    @ParameterizedTest(name = "초기 예산 {0}")
    @ValueSource(longs = {0L, 100_000L})
    @DisplayName("모임 생성 시 Room과 생성자의 OWNER 연결을 실제 DB에 저장한다.")
    void createRoom_persistsRoomAndOwnerConnection(long totalBudget) throws Exception {
        User user = createUser();
        Cookie accessCookie = login(user);
        RoomCreateRequest request = new RoomCreateRequest("사진동아리", totalBudget, Currency.KRW);

        MvcResult result = mockMvc.perform(post("/rooms")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        long roomId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        roomIds.add(roomId);

        // 테스트 자체에는 트랜잭션을 걸지 않아 요청 종료 후의 저장 결과를 확인한다.
        Room savedRoom = roomRepository.findById(roomId).orElseThrow();
        assertThat(savedRoom.getName()).isEqualTo(request.name());
        assertThat(savedRoom.getTotalBudget()).isEqualTo(totalBudget);
        assertThat(savedRoom.getAvailableBudget()).isEqualTo(totalBudget);
        assertThat(savedRoom.getCurrency()).isEqualTo(Currency.KRW);
        assertThat(savedRoom.getCreatedAt()).isNotNull();

        UserRoomConnection connection = connectionRepository
                .findByUser_IdAndRoom_Id(user.getId(), roomId).orElseThrow();
        assertThat(connection.getUser().getId()).isEqualTo(user.getId());
        assertThat(connection.getRoom().getId()).isEqualTo(roomId);
        assertThat(connection.getAuthority()).isEqualTo(Authority.OWNER);
        assertThat(connection.isJoined()).isTrue();
        assertThat(connection.getCreatedAt()).isNotNull();
        assertThat(connectionRepository.findAllByRoom_Id(roomId)).hasSize(1);

        jsonPath("$.resultCode").value(201).match(result);
        jsonPath("$.message").value("모임이 생성되었습니다.").match(result);
        jsonPath("$.data.name").value(request.name()).match(result);
        jsonPath("$.data.totalBudget").value(totalBudget).match(result);
        jsonPath("$.data.availableBudget").value(totalBudget).match(result);
        jsonPath("$.data.currency").value("KRW").match(result);
        jsonPath("$.data.createdAt").isNotEmpty().match(result);
    }

    @Test
    @DisplayName("참여한 모임만 생성일 내림차순으로 조회한다.")
    void getRooms_returnsOnlyJoinedRoomsInNewestFirstOrder() throws Exception {
        User user = createUser();
        User other = createUser();
        Room newer = createRoom(user, "내가 만든 모임", 100_000L, Currency.KRW);
        Room older = createRoom(other, "참여한 모임", 50_000L, Currency.USD);
        connectionRepository.save(UserRoomConnection.createMember(user, older));
        Room unrelated = createRoom(other, "참여하지 않은 모임", 30_000L, Currency.JPY);

        // 시간 대기 없이, ID 순서와 반대인 생성일을 만들어 정렬 기준을 검증한다.
        LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0);
        setCreatedAt(older, baseTime.plusDays(2));
        setCreatedAt(newer, baseTime.plusDays(3));
        setCreatedAt(unrelated, baseTime.plusDays(4));

        mockMvc.perform(get("/rooms").cookie(login(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.data.rooms.length()").value(2))
                .andExpect(jsonPath("$.data.rooms[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.data.rooms[0].name").value("내가 만든 모임"))
                .andExpect(jsonPath("$.data.rooms[1].id").value(older.getId()))
                .andExpect(jsonPath("$.data.rooms[1].name").value("참여한 모임"))
                .andExpect(jsonPath("$.data.rooms[1].currency").value("USD"));
    }

    @Test
    @DisplayName("참여한 모임이 없으면 다른 모임이 있어도 빈 목록을 반환한다.")
    void getRooms_returnsEmptyListForUserWithoutRooms() throws Exception {
        User user = createUser();
        createRoom(createUser(), "다른 사람의 모임", 100_000L, Currency.KRW);

        mockMvc.perform(get("/rooms").cookie(login(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.data.rooms").isEmpty());
    }

    @ParameterizedTest(name = "{0} 권한으로 상세 조회")
    @EnumSource(Authority.class)
    @DisplayName("OWNER, OPERATOR, MEMBER 모두 참여한 모임 상세를 조회한다.")
    void getRoom_returnsDetailForEveryMemberAuthority(Authority authority) throws Exception {
        User owner = createUser();
        Room room = createRoom(owner, "사진동아리", 100_000L, Currency.KRW);
        User actor = owner;
        if (authority != Authority.OWNER) {
            actor = createUser();
            UserRoomConnection connection = UserRoomConnection.createMember(actor, room);
            connection.changeAuthority(authority);
            connectionRepository.save(connection);
        }

        mockMvc.perform(get("/rooms/{roomId}", room.getId()).cookie(login(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.data.id").value(room.getId()))
                .andExpect(jsonPath("$.data.name").value("사진동아리"))
                .andExpect(jsonPath("$.data.totalBudget").value(100_000))
                .andExpect(jsonPath("$.data.availableBudget").value(100_000))
                .andExpect(jsonPath("$.data.currency").value("KRW"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("미참여 모임의 상세 조회는 404로 거부한다.")
    void getRoom_rejectsNonMember() throws Exception {
        User user = createUser();
        Room room = createRoom(createUser(), "비공개 모임", 100_000L, Currency.KRW);

        mockMvc.perform(get("/rooms/{roomId}", room.getId()).cookie(login(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value(404))
                .andExpect(jsonPath("$.message").value("해당 모임이 존재하지 않습니다."));
    }

    @Test
    @DisplayName("존재하지 않는 모임의 상세 조회는 404로 거부한다.")
    void getRoom_rejectsMissingRoom() throws Exception {
        User user = createUser();
        Room room = createRoom(user, "삭제할 모임", 100_000L, Currency.KRW);
        roomRepository.deleteById(room.getId());
        roomIds.remove(room.getId());

        mockMvc.perform(get("/rooms/{roomId}", room.getId()).cookie(login(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resultCode").value(404))
                .andExpect(jsonPath("$.message").value("해당 모임이 존재하지 않습니다."));
    }

    @ParameterizedTest(name = "인증 없는 {0} 요청")
    @ValueSource(strings = {"생성", "목록", "상세"})
    @DisplayName("로그인하지 않은 생성·조회 요청은 401로 거부한다.")
    void roomEndpoints_rejectUnauthenticatedRequests(String endpoint) throws Exception {
        User owner = createUser();
        Room room = createRoom(owner, "인증이 필요한 모임", 100_000L, Currency.KRW);
        long roomCount = roomRepository.count();
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case "생성" -> post("/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            new RoomCreateRequest("새 모임", 100_000L, Currency.KRW)));
            case "목록" -> get("/rooms");
            case "상세" -> get("/rooms/{roomId}", room.getId());
            default -> throw new IllegalArgumentException(endpoint);
        };

        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인 후 이용해주세요."))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(roomRepository.count()).isEqualTo(roomCount);
    }

    private User createUser() {
        User user = userRepository.save(new User(
                UUID.randomUUID() + "@test.com", passwordEncoder.encode(PASSWORD), "테스트 회원"
        ));
        userIds.add(user.getId());
        return user;
    }

    private Cookie login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserLoginRequest(user.getEmail(), PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie accessCookie = result.getResponse().getCookie("accessToken");
        assertThat(accessCookie).isNotNull();
        return accessCookie;
    }

    private Room createRoom(User owner, String name, long totalBudget, Currency currency) {
        Room room = roomRepository.save(Room.create(name, totalBudget, currency));
        roomIds.add(room.getId());
        connectionRepository.save(UserRoomConnection.createOwner(owner, room));
        return room;
    }

    private void setCreatedAt(Room room, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE room SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(createdAt), room.getId());
    }
}
