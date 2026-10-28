package com.budzet.domain.budget.controller;

import com.budzet.domain.budget.dto.*;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.service.BudgetChangeService;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.user.entity.User;
import com.budzet.global.rq.Rq;
import com.budzet.global.security.CustomAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BudgetChangeController.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class BudgetChangeControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BudgetChangeService budgetChangeService;

    @MockitoBean
    private Rq rq;

    @MockitoBean
    private CustomAuthenticationFilter customAuthenticationFilter;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMappingContext;

    private final Long testUserId = 100L;

    @BeforeEach
    void setUp() {
        User mockUser = org.mockito.Mockito.mock(User.class);

        given(mockUser.getId()).willReturn(testUserId);
        given(rq.getActor()).willReturn(mockUser);
    }


    // =========================
    // 정산 등록
    // =========================

    @Test
    @DisplayName("정산 등록 성공 - 201 CREATED")
    void createBudgetChange_success() throws Exception {

        // given
        Long roomId = 1L;
        Long requestId = 10L;
        Long changeId = 50L;
        LocalDateTime now = LocalDateTime.now();

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(
                        5000L,
                        "회식비 정산"
                );

        BudgetChange budgetChange =
                org.mockito.Mockito.mock(BudgetChange.class);
        BudgetRequest budgetRequest =
                org.mockito.Mockito.mock(BudgetRequest.class);
        User user = org.mockito.Mockito.mock(User.class);

        given(budgetChange.getId()).willReturn(changeId);
        given(budgetChange.getRequest()).willReturn(budgetRequest);
        given(budgetRequest.getId()).willReturn(requestId);
        given(budgetChange.getUser()).willReturn(user);
        given(user.getId()).willReturn(testUserId);

        given(budgetChange.getChangeBudget()).willReturn(9500L);
        given(budgetChange.getChangedBudget()).willReturn(5000L);
        given(budgetChange.getType()).willReturn(BudgetType.SETTLEMENT);
        given(budgetChange.getUserName()).willReturn("홍길동");
        given(budgetChange.getCreatedAt()).willReturn(now);
        given(budgetChange.getReason()).willReturn("팀 회식 신청");

        BudgetChangeCreateResponse response =
                BudgetChangeCreateResponse.from(budgetChange);

        given(budgetChangeService.createBudgetChange(
                eq(roomId),
                eq(testUserId),
                eq(requestId),
                any(BudgetChangeCreateRequest.class)
        )).willReturn(response);

        // when & then
        mvc.perform(post(
                        "/rooms/{roomId}/budget/changes/{requestId}",
                        roomId, requestId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultCode").value(201))
                .andExpect(jsonPath("$.message").value("정산 처리 성공"))
                .andExpect(jsonPath("$.data.id").value(changeId))
                .andExpect(jsonPath("$.data.requestId").value(requestId))
                .andExpect(jsonPath("$.data.userId").value(testUserId))
                .andExpect(jsonPath("$.data.changeBudget").value(9500L))
                .andExpect(jsonPath("$.data.changedBudget").value(5000L))
                .andExpect(jsonPath("$.data.budgetType").value("SETTLEMENT"))
                .andExpect(jsonPath("$.data.userName").value("홍길동"))
                .andExpect(jsonPath("$.data.reason").value("팀 회식 신청"));

        verify(budgetChangeService).createBudgetChange(
                eq(roomId),
                eq(testUserId),
                eq(requestId),
                any(BudgetChangeCreateRequest.class)
        );
    }

    @Test
    @DisplayName("정산 등록 실패 - 정산 금액이 0 이하일 때")
    void createBudgetChange_validationError_negativeAmount() throws Exception {

        // given
        Long roomId = 1L;
        Long requestId = 10L;

        BudgetChangeCreateRequest invalidRequest =
                new BudgetChangeCreateRequest(
                        -1000L,
                        "잘못된 정산 금액"
                );

        // when & then
        mvc.perform(post(
                        "/rooms/{roomId}/budget/changes/{requestId}",
                        roomId, requestId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(budgetChangeService);
    }


    // =========================
    // 정산 내역 목록 조회
    // =========================

    @Test
    @DisplayName("정산 내역 목록 조회 성공 - 200 OK")
    void budgetChangeList_success() throws Exception {

        // given
        Long roomId = 1L;
        LocalDateTime now = LocalDateTime.now();

        BudgetChange budgetChange =
                org.mockito.Mockito.mock(BudgetChange.class);

        given(budgetChange.getId()).willReturn(50L);
        given(budgetChange.getChangedBudget()).willReturn(9500L);
        given(budgetChange.getType()).willReturn(BudgetType.SETTLEMENT);
        given(budgetChange.getUserName()).willReturn("홍길동");
        given(budgetChange.getCreatedAt()).willReturn(now);
        given(budgetChange.getReason()).willReturn("팀 회식 신청");

        BudgetChangeListResponse response =
                BudgetChangeListResponse.from(List.of(budgetChange));

        given(budgetChangeService.budgetChangeList(roomId, testUserId))
                .willReturn(response);

        // when & then
        mvc.perform(get(
                        "/rooms/{roomId}/budget/changes/settlement",
                        roomId
                )
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message").value("정산 내역 조회 성공"))
                .andExpect(jsonPath("$.data.changes[0].id").value(50L))
                .andExpect(jsonPath("$.data.changes[0].changedBudget").value(9500L))
                .andExpect(jsonPath("$.data.changes[0].type").value("SETTLEMENT"))
                .andExpect(jsonPath("$.data.changes[0].userName").value("홍길동"))
                .andExpect(jsonPath("$.data.changes[0].processedAt").exists())
                .andExpect(jsonPath("$.data.changes[0].reason").value("팀 회식 신청"));

        verify(budgetChangeService).budgetChangeList(roomId, testUserId);
    }

    @Test
    @DisplayName("정산 내역 목록 조회 성공 - 내역이 없으면 빈 배열 반환")
    void budgetChangeList_emptyList() throws Exception {

        // given
        Long roomId = 1L;

        BudgetChangeListResponse response =
                BudgetChangeListResponse.from(List.of());

        given(budgetChangeService.budgetChangeList(roomId, testUserId))
                .willReturn(response);

        // when & then
        mvc.perform(get(
                        "/rooms/{roomId}/budget/changes/settlement",
                        roomId
                )
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message").value("정산 내역 조회 성공"))
                .andExpect(jsonPath("$.data.changes").isEmpty());

        verify(budgetChangeService).budgetChangeList(roomId, testUserId);
    }


    // =========================
    // 정산 내역 상세 조회
    // =========================

    @Test
    @DisplayName("정산 내역 상세 조회 성공 - 200 OK")
    void budgetChangeDetail_success() throws Exception {

        // given
        Long roomId = 1L;
        Long changeId = 50L;
        Long requestId = 10L;
        LocalDateTime now = LocalDateTime.now();

        BudgetChange budgetChange =
                org.mockito.Mockito.mock(BudgetChange.class);
        BudgetRequest budgetRequest =
                org.mockito.Mockito.mock(BudgetRequest.class);
        User user = org.mockito.Mockito.mock(User.class);
        Room room = org.mockito.Mockito.mock(Room.class);

        given(budgetChange.getRoom()).willReturn(room);
        given(room.getId()).willReturn(roomId);

        given(budgetChange.getId()).willReturn(changeId);
        given(budgetChange.getRequest()).willReturn(budgetRequest);
        given(budgetRequest.getId()).willReturn(requestId);

        given(budgetChange.getUser()).willReturn(user);
        given(user.getId()).willReturn(testUserId);
        given(user.getEmail()).willReturn("user1@example.com");

        given(budgetChange.getChangeBudget()).willReturn(5000L);
        given(budgetChange.getChangedBudget()).willReturn(9500L);
        given(budgetChange.getType()).willReturn(BudgetType.SETTLEMENT);
        given(budgetChange.getUserName()).willReturn("홍길동");
        given(budgetChange.getCreatedAt()).willReturn(now);
        given(budgetChange.getReason()).willReturn("팀 회식 신청");

        BudgetChangeDetailResponse response =
                BudgetChangeDetailResponse.from(budgetChange);

        given(budgetChangeService.budgetChangeDetail(
                roomId, testUserId, changeId
        )).willReturn(response);

        // when & then
        mvc.perform(get(
                        "/rooms/{roomId}/budget/changes/{changeId}",
                        roomId, changeId
                )
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message").value("정산 내역 상세조회 성공"))
                .andExpect(jsonPath("$.data.id").value(changeId));

        verify(budgetChangeService).budgetChangeDetail(
                roomId, testUserId, changeId
        );
    }


    // =========================
    // 정산 내역 수정
    // =========================

    @Test
    @DisplayName("정산 내역 수정 성공 - 200 OK")
    void updateBudgetChange_success() throws Exception {

        // given
        Long roomId = 1L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest request =
                new BudgetChangeUpdateRequest(
                        10000L,
                        8000L,
                        "홍길동",
                        "hong@test.com",
                        "장비 구매 영수증 수정"
                );

        BudgetChangeUpdateResponse response =
                new BudgetChangeUpdateResponse(
                        changeId,
                        10000L,
                        8000L,
                        "홍길동",
                        "hong@test.com",
                        "장비 구매 영수증 수정"
                );

        given(budgetChangeService.updateBudgetChange(
                eq(roomId),
                eq(testUserId),
                eq(changeId),
                any(BudgetChangeUpdateRequest.class)
        )).willReturn(response);

        // when & then
        mvc.perform(put(
                        "/rooms/{roomId}/budget/changes/{changeId}",
                        roomId, changeId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message").value("정산 내역 수정 성공"))
                .andExpect(jsonPath("$.data.id").value(changeId))
                .andExpect(jsonPath("$.data.changeBudget").value(10000L))
                .andExpect(jsonPath("$.data.changedBudget").value(8000L))
                .andExpect(jsonPath("$.data.userName").value("홍길동"))
                .andExpect(jsonPath("$.data.userEmail").value("hong@test.com"))
                .andExpect(jsonPath("$.data.reason").value("장비 구매 영수증 수정"));

        verify(budgetChangeService).updateBudgetChange(
                eq(roomId),
                eq(testUserId),
                eq(changeId),
                any(BudgetChangeUpdateRequest.class)
        );
    }

    @Test
    @DisplayName("정산 내역 수정 실패 - 정산 금액이 음수일 때")
    void updateBudgetChange_validationFailure_negativeAmount() throws Exception {

        // given
        Long roomId = 1L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest invalidRequest =
                new BudgetChangeUpdateRequest(
                        10000L,
                        -5000L,
                        "홍길동",
                        "hong@test.com",
                        "잘못된 금액"
                );

        // when & then
        mvc.perform(put(
                        "/rooms/{roomId}/budget/changes/{changeId}",
                        roomId, changeId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(budgetChangeService);
    }

    @Test
    @DisplayName("정산 내역 수정 실패 - 이메일 형식이 잘못되었을 때")
    void updateBudgetChange_validationFailure_invalidEmail() throws Exception {

        // given
        Long roomId = 1L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest invalidRequest =
                new BudgetChangeUpdateRequest(
                        10000L,
                        8000L,
                        "홍길동",
                        "invalid-email",
                        "이메일 오류"
                );

        // when & then
        mvc.perform(put(
                        "/rooms/{roomId}/budget/changes/{changeId}",
                        roomId, changeId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(budgetChangeService);
    }
}