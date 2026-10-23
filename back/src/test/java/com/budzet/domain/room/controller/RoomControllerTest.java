package com.budzet.domain.room.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budzet.domain.room.dto.RoomCreateRequest;
import com.budzet.domain.room.dto.RoomCreateResponse;
import com.budzet.domain.room.dto.RoomDetailResponse;
import com.budzet.domain.room.dto.RoomListResponse;
import com.budzet.domain.room.dto.RoomUpdateRequest;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.service.RoomService;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.service.UserService;
import com.budzet.global.rq.Rq;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(RoomController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private Rq rq;

    @Test
    @DisplayName("모임 생성 요청 시 실제 HTTP 상태 201과 ApiResponse를 반환한다.")
    void createRoom_returnsCreatedResponse() throws Exception {
        User actor = actor(1L);
        RoomCreateRequest request = new RoomCreateRequest("사진동아리", 100_000L, Currency.KRW);
        RoomCreateResponse response = new RoomCreateResponse(
                10L, "사진동아리", 100_000L, 100_000L, "KRW", LocalDateTime.now()
        );
        when(rq.getActor()).thenReturn(actor);
        when(roomService.createRoom(eq(1L), eq(request))).thenReturn(response);

        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultCode").value(201))
                .andExpect(jsonPath("$.message").value("모임이 생성되었습니다."))
                .andExpect(jsonPath("$.data.id").value(10));

        verify(roomService).createRoom(1L, request);
    }

    @Test
    @DisplayName("인증 사용자의 모임 목록을 조회한다.")
    void getRooms_usesAuthenticatedUserId() throws Exception {
        User actor = actor(1L);
        RoomListResponse response = new RoomListResponse(List.of());
        when(rq.getActor()).thenReturn(actor);
        when(roomService.getRooms(1L)).thenReturn(response);

        mockMvc.perform(get("/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.data.rooms").isEmpty());

        verify(roomService).getRooms(1L);
    }

    @Test
    @DisplayName("인증 사용자의 모임 상세 정보를 조회한다.")
    void getRoom_usesAuthenticatedUserId() throws Exception {
        User actor = actor(1L);
        RoomDetailResponse response = roomDetailResponse();
        when(rq.getActor()).thenReturn(actor);
        when(roomService.getRoom(1L, 10L)).thenReturn(response);

        mockMvc.perform(get("/rooms/{roomId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.data.id").value(10));

        verify(roomService).getRoom(1L, 10L);
    }

    @Test
    @DisplayName("모임 이름 수정 시 인증 사용자의 ID를 전달한다.")
    void updateRoom_usesAuthenticatedUserId() throws Exception {
        User actor = actor(1L);
        RoomUpdateRequest request = new RoomUpdateRequest("수정된 이름");
        RoomDetailResponse response = roomDetailResponse();
        when(rq.getActor()).thenReturn(actor);
        when(roomService.updateRoom(eq(1L), eq(10L), eq(request))).thenReturn(response);

        mockMvc.perform(patch("/rooms/{roomId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200));

        verify(roomService).updateRoom(1L, 10L, request);
    }

    @Test
    @DisplayName("모임 삭제 시 인증 사용자의 ID를 전달하고 공통 응답을 반환한다.")
    void deleteRoom_usesAuthenticatedUserId() throws Exception {
        User actor = actor(1L);
        when(rq.getActor()).thenReturn(actor);

        mockMvc.perform(delete("/rooms/{roomId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message").value("모임을 삭제했습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(roomService).deleteRoom(1L, 10L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateRequests")
    @DisplayName("모임 생성 시 잘못된 필드 입력을 거부하고 서비스를 호출하지 않는다.")
    void createRoom_rejectsInvalidFields(
            String scenario, String json, String field, String message
    ) throws Exception {
        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data[*].field").value(hasItem(field)))
                .andExpect(jsonPath("$.data[0].message").value(message));

        verifyNoInteractions(roomService);
    }

    private static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                Arguments.of("이름 누락", "{\"totalBudget\":100000,\"currency\":\"KRW\"}",
                        "name", "모임 이름은 필수입니다."),
                Arguments.of("이름 null", "{\"name\":null,\"totalBudget\":100000,\"currency\":\"KRW\"}",
                        "name", "모임 이름은 필수입니다."),
                Arguments.of("빈 이름", "{\"name\":\"\",\"totalBudget\":100000,\"currency\":\"KRW\"}",
                        "name", "모임 이름은 필수입니다."),
                Arguments.of("공백 이름", "{\"name\":\"   \",\"totalBudget\":100000,\"currency\":\"KRW\"}",
                        "name", "모임 이름은 필수입니다."),
                Arguments.of("21자 이름", "{\"name\":\"" + "가".repeat(21) + "\",\"totalBudget\":100000,\"currency\":\"KRW\"}",
                        "name", "모임 이름은 20자 이하여야 합니다."),
                Arguments.of("예산 누락", "{\"name\":\"사진동아리\",\"currency\":\"KRW\"}",
                        "totalBudget", "총 예산은 필수입니다."),
                Arguments.of("예산 null", "{\"name\":\"사진동아리\",\"totalBudget\":null,\"currency\":\"KRW\"}",
                        "totalBudget", "총 예산은 필수입니다."),
                Arguments.of("음수 예산", "{\"name\":\"사진동아리\",\"totalBudget\":-1,\"currency\":\"KRW\"}",
                        "totalBudget", "총 예산은 음수가 될 수 없습니다."),
                Arguments.of("통화 누락", "{\"name\":\"사진동아리\",\"totalBudget\":100000}",
                        "currency", "통화는 필수입니다."),
                Arguments.of("통화 null", "{\"name\":\"사진동아리\",\"totalBudget\":100000,\"currency\":null}",
                        "currency", "통화는 필수입니다.")
        );
    }

    @Test
    @DisplayName("정의되지 않은 통화로 모임 생성 시 공통 요청 오류를 반환한다.")
    void createRoom_rejectsUnknownCurrency() throws Exception {
        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"사진동아리","totalBudget":100000,"currency":"INVALID"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(roomService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validBoundaryCreateRequests")
    @DisplayName("모임 생성 시 이름 20자와 예산 0의 경계값을 허용한다.")
    void createRoom_acceptsBoundaryValues(String scenario, RoomCreateRequest request) throws Exception {
        User actor = actor(1L);
        RoomCreateResponse response = new RoomCreateResponse(
                10L, request.name(), request.totalBudget(), request.totalBudget(),
                request.currency().name(), LocalDateTime.now()
        );
        when(rq.getActor()).thenReturn(actor);
        when(roomService.createRoom(1L, request)).thenReturn(response);

        mockMvc.perform(post("/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultCode").value(201))
                .andExpect(jsonPath("$.data.name").value(request.name()))
                .andExpect(jsonPath("$.data.totalBudget").value(request.totalBudget()))
                .andExpect(jsonPath("$.data.availableBudget").value(request.totalBudget()));

        verify(roomService).createRoom(1L, request);
    }

    private static Stream<Arguments> validBoundaryCreateRequests() {
        return Stream.of(
                Arguments.of("20자 이름", new RoomCreateRequest("가".repeat(20), 100_000L, Currency.KRW)),
                Arguments.of("예산 0", new RoomCreateRequest("사진동아리", 0L, Currency.KRW))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUpdateRequests")
    @DisplayName("모임 수정 시 잘못된 이름을 거부하고 서비스를 호출하지 않는다.")
    void updateRoom_rejectsInvalidName(String scenario, String json, String message) throws Exception {
        mockMvc.perform(patch("/rooms/{roomId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resultCode").value(400))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data[0].field").value("name"))
                .andExpect(jsonPath("$.data[0].message").value(message));

        verifyNoInteractions(roomService);
    }

    private static Stream<Arguments> invalidUpdateRequests() {
        return Stream.of(
                Arguments.of("이름 누락", "{}", "모임 이름은 필수입니다."),
                Arguments.of("이름 null", "{\"name\":null}", "모임 이름은 필수입니다."),
                Arguments.of("빈 이름", "{\"name\":\"\"}", "모임 이름은 필수입니다."),
                Arguments.of("공백 이름", "{\"name\":\"   \"}", "모임 이름은 필수입니다."),
                Arguments.of("21자 이름", "{\"name\":\"" + "가".repeat(21) + "\"}",
                        "모임 이름은 20자 이하여야 합니다.")
        );
    }

    @Test
    @DisplayName("모임 이름을 20자로 수정할 수 있다.")
    void updateRoom_acceptsTwentyCharacterName() throws Exception {
        User actor = actor(1L);
        RoomUpdateRequest request = new RoomUpdateRequest("가".repeat(20));
        RoomDetailResponse response = new RoomDetailResponse(
                10L, request.name(), 100_000L, 100_000L, "KRW", LocalDateTime.now()
        );
        when(rq.getActor()).thenReturn(actor);
        when(roomService.updateRoom(1L, 10L, request)).thenReturn(response);

        mockMvc.perform(patch("/rooms/{roomId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.data.name").value(request.name()));

        verify(roomService).updateRoom(1L, 10L, request);
    }

    private User actor(Long userId) {
        User actor = org.mockito.Mockito.mock(User.class);
        when(actor.getId()).thenReturn(userId);
        return actor;
    }

    private RoomDetailResponse roomDetailResponse() {
        return new RoomDetailResponse(
                10L, "사진동아리", 100_000L, 100_000L, "KRW", LocalDateTime.now()
        );
    }
}
