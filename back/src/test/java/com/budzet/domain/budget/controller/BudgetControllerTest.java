package com.budzet.domain.budget.controller;

import com.budzet.domain.budget.dto.BudgetHistoryResponse;
import com.budzet.domain.budget.dto.BudgetResponse;
import com.budzet.domain.budget.dto.BudgetUpdateRequest;
import com.budzet.domain.budget.dto.BudgetUpdateResponse;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.service.BudgetService;
import com.budzet.domain.room.entity.Currency;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BudgetController.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class BudgetControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BudgetService budgetService;

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
    // 예산 조회
    // =========================

    @Test
    @DisplayName("예산 조회 성공")
    void getBudget_success() throws Exception {

        // given
        Long roomId = 1L;

        Room room = Room.create("모임방", 1000L, Currency.KRW);
        ReflectionTestUtils.setField(room, "id", roomId);

        BudgetResponse response = BudgetResponse.from(room);

        given(budgetService.getBudget(roomId, testUserId))
                .willReturn(response);

        // when & then
        mvc.perform(get("/rooms/{roomId}/budget", roomId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message")
                        .value("예산 조회에 성공하였습니다."))
                .andExpect(jsonPath("$.data.roomId").value(1L))
                .andExpect(jsonPath("$.data.roomName").value("모임방"))
                .andExpect(jsonPath("$.data.totalBudget").value(1000L))
                .andExpect(jsonPath("$.data.availableBudget").value(1000L))
                .andExpect(jsonPath("$.data.reserveBudget").value(0))
                .andExpect(jsonPath("$.data.currency").value("KRW"));

        verify(budgetService).getBudget(roomId, testUserId);
    }


    // =========================
    // 예산 변경 이력 조회
    // =========================

    @Test
    @DisplayName("예산 변경 이력 조회 성공")
    void getBudgetHistory_success() throws Exception {

        // given
        Long roomId = 1L;
        LocalDateTime now = LocalDateTime.now();

        BudgetChange budgetChange =
                org.mockito.Mockito.mock(BudgetChange.class);

        given(budgetChange.getId()).willReturn(10L);
        given(budgetChange.getChangedBudget()).willReturn(8000L);
        given(budgetChange.getType()).willReturn(BudgetType.SETTLEMENT);
        given(budgetChange.getUserName()).willReturn("홍길동");
        given(budgetChange.getReason()).willReturn("장비 대여");
        given(budgetChange.getCreatedAt()).willReturn(now);

        BudgetHistoryResponse response =
                BudgetHistoryResponse.from(List.of(budgetChange));

        given(budgetService.getBudgetHistory(roomId, testUserId))
                .willReturn(response);

        // when & then
        mvc.perform(get("/rooms/{roomId}/budget/history", roomId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message")
                        .value("예산 변경 목록 조회에 성공하였습니다."))
                .andExpect(jsonPath("$.data.history[0].id").value(10L))
                .andExpect(jsonPath("$.data.history[0].changedBudget")
                        .value(8000L))
                .andExpect(jsonPath("$.data.history[0].type")
                        .value("SETTLEMENT"))
                .andExpect(jsonPath("$.data.history[0].userName")
                        .value("홍길동"))
                .andExpect(jsonPath("$.data.history[0].reason")
                        .value("장비 대여"))
                .andExpect(jsonPath("$.data.history[0].processedAt")
                        .exists());

        verify(budgetService).getBudgetHistory(roomId, testUserId);
    }


    // =========================
    // 예산 수정
    // =========================

    @Test
    @DisplayName("예산 수정 성공")
    void updateBudget_success() throws Exception {

        // given
        Long roomId = 1L;

        BudgetUpdateRequest request =
                new BudgetUpdateRequest(
                        5000L,
                        BudgetType.INCREASE,
                        "지원금 추가"
                );

        BudgetUpdateResponse response =
                new BudgetUpdateResponse(roomId, 15000L);

        given(budgetService.updateBudget(
                eq(roomId),
                eq(testUserId),
                any(BudgetUpdateRequest.class)
        )).willReturn(response);

        // when & then
        mvc.perform(patch("/rooms/{roomId}/budget", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").value(200))
                .andExpect(jsonPath("$.message")
                        .value("예산 변경에 성공하였습니다."))
                .andExpect(jsonPath("$.data.roomId").value(1L))
                .andExpect(jsonPath("$.data.totalBudget").value(15000L));

        verify(budgetService).updateBudget(
                eq(roomId),
                eq(testUserId),
                any(BudgetUpdateRequest.class)
        );
    }

    @Test
    @DisplayName("예산 수정 실패 - 금액이 0 이하일 때")
    void updateBudget_validationError_invalidAmount() throws Exception {

        // given
        Long roomId = 1L;

        BudgetUpdateRequest invalidRequest =
                new BudgetUpdateRequest(
                        -1000L,
                        BudgetType.INCREASE,
                        "잘못된 금액"
                );

        // when & then
        mvc.perform(patch("/rooms/{roomId}/budget", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        // 유효성 검사 실패 시 서비스 호출은 없어야 함
        org.mockito.Mockito.verifyNoInteractions(budgetService);
    }

    @Test
    @DisplayName("예산 수정 실패 - 변동 유형이 null일 때")
    void updateBudget_validationError_nullType() throws Exception {

        // given
        Long roomId = 1L;

        BudgetUpdateRequest invalidRequest =
                new BudgetUpdateRequest(
                        1000L,
                        null,
                        "타입 없음"
                );

        // when & then
        mvc.perform(patch("/rooms/{roomId}/budget", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(budgetService);
    }
}