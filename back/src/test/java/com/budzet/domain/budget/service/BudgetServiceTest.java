package com.budzet.domain.budget.service;

import com.budzet.domain.budget.dto.BudgetHistoryResponse;
import com.budzet.domain.budget.dto.BudgetResponse;
import com.budzet.domain.budget.dto.BudgetUpdateRequest;
import com.budzet.domain.budget.dto.BudgetUpdateResponse;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.global.exception.BusinessException;
import com.budzet.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private BudgetChangeRepository budgetChangeRepository;

    @Mock
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @InjectMocks
    private BudgetService budgetService;


    // =========================
    // 예산 조회
    // =========================

    @Test
    @DisplayName("예산 조회 성공")
    void getBudget_success() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        Room room = mock(Room.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        when(room.getId()).thenReturn(roomId);
        when(room.getName()).thenReturn("동아리");
        when(room.getTotalBudget()).thenReturn(1000L);
        when(room.getAvailableBudget()).thenReturn(800L);
        when(room.getCurrency()).thenReturn(Currency.KRW);

        // when
        BudgetResponse result =
                budgetService.getBudget(roomId, userId);

        // then
        assertNotNull(result);
        assertEquals(roomId, result.getRoomId());
        assertEquals("동아리", result.getRoomName());
        assertEquals(1000L, result.getTotalBudget());
        assertEquals(800L, result.getAvailableBudget());
        assertEquals(200L, result.getReserveBudget());
        assertEquals("KRW", result.getCurrency());
    }

    @Test
    @DisplayName("예산 조회 실패 - 모임이 존재하지 않을 때")
    void getBudget_roomNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.getBudget(roomId, userId)
        );

        // then
        assertEquals(
                ErrorCode.ROOM_NOT_FOUND,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("예산 조회 실패 - 실제 예산 정보가 없을 때")
    void getBudget_budgetNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        Room room = mock(Room.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        when(room.getTotalBudget()).thenReturn(null);
        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.getBudget(roomId, userId)
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_NOT_FOUND,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("예산 조회 실패 - 가용예산 정보가 없을 때")
    void getBudget_availableBudgetNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        Room room = mock(Room.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        // 실제 예산은 정상값
        when(room.getTotalBudget()).thenReturn(1000L);

        // 가용예산만 null로 설정
        when(room.getAvailableBudget()).thenReturn(null);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.getBudget(roomId, userId)
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_NOT_FOUND,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("예산 조회 실패 - 모임 멤버가 아닐 때")
    void getBudget_userNotJoinedRoom() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(null);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.getBudget(roomId, userId)
        );

        // then
        assertEquals(
                ErrorCode.USER_NOT_JOINED_ROOM,
                exception.getErrorCode()
        );

        // 멤버 검증 실패 시 모임 조회는 실행되지 않아야 함
        verify(roomRepository, never()).findById(any());
    }


    // =========================
    // 예산 변경 내역 조회
    // =========================

    @Test
    @DisplayName("예산 변경 내역 조회 성공")
    void getBudgetHistory_success() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        BudgetChange budgetChange = mock(BudgetChange.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        Room room = mock(Room.class);

        LocalDateTime now = LocalDateTime.now();

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(budgetChangeRepository
                .findAllByRoomIdOrderByCreatedAtDesc(roomId))
                .thenReturn(List.of(budgetChange));

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        when(budgetChange.getId()).thenReturn(10L);
        when(budgetChange.getChangedBudget()).thenReturn(8000L);
        when(budgetChange.getType()).thenReturn(BudgetType.INCREASE);
        when(budgetChange.getUserName()).thenReturn("홍길동");
        when(budgetChange.getReason()).thenReturn("장비대여");
        when(budgetChange.getCreatedAt()).thenReturn(now);

        // when
        BudgetHistoryResponse result =
                budgetService.getBudgetHistory(roomId, userId);

        // then
        assertNotNull(result);
        assertEquals(1, result.getHistory().size());

        BudgetHistoryResponse.HistoryItem historyItem =
                result.getHistory().getFirst();

        assertEquals(10L, historyItem.getId());
        assertEquals(8000L, historyItem.getChangedBudget());
        assertEquals("INCREASE", historyItem.getType());
        assertEquals("홍길동", historyItem.getUserName());
        assertEquals("장비대여", historyItem.getReason());
        assertEquals(now, historyItem.getProcessedAt());
    }

    @Test
    @DisplayName("예산 변경 내역 조회 성공 - 변경 내역이 없을 때 빈 목록 반환")
    void getBudgetHistory_emptyHistory() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        Room room = mock(Room.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(budgetChangeRepository
                .findAllByRoomIdOrderByCreatedAtDesc(roomId))
                .thenReturn(Collections.emptyList());

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        // when
        BudgetHistoryResponse result =
                budgetService.getBudgetHistory(roomId, userId);

        // then
        assertNotNull(result);
        assertTrue(result.getHistory().isEmpty());
    }

    @Test
    @DisplayName("예산 변경 내역 조회 실패 - 모임 멤버가 아닐 때")
    void getBudgetHistory_userNotJoinedRoom() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(null);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.getBudgetHistory(roomId, userId)
        );

        // then
        assertEquals(
                ErrorCode.USER_NOT_JOINED_ROOM,
                exception.getErrorCode()
        );

        // 멤버 검증 실패 시 후속 조회는 실행되지 않아야 함
        verify(roomRepository, never()).findById(any());
        verify(budgetChangeRepository, never())
                .findAllByRoomIdOrderByCreatedAtDesc(anyLong());
    }


    // =========================
    // 예산 수정
    // =========================

    @Test
    @DisplayName("예산 수정 성공 - OWNER 권한으로 증액")
    void updateBudget_success_owner() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        BudgetUpdateRequest request =
                new BudgetUpdateRequest(
                        5000L,
                        BudgetType.INCREASE,
                        "지원금 추가"
                );

        User user = mock(User.class);
        when(user.getName()).thenReturn("테스트 사용자");
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        Room room = mock(Room.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(connection.getUser()).thenReturn(user);

        when(roomRepository.findByWithLock(roomId))
                .thenReturn(room);

        when(room.getId()).thenReturn(roomId);
        when(room.getTotalBudget()).thenReturn(15000L);

        // when
        BudgetUpdateResponse response =
                budgetService.updateBudget(roomId, userId, request);

        // then
        assertNotNull(response);
        assertEquals(roomId, response.getRoomId());
        assertEquals(15000L, response.getTotalBudget());

        // 예산 변경 메서드 호출 검증
        verify(room).updateTotalBudget(
                5000L,
                BudgetType.INCREASE
        );

        // 변경 이력 저장 메서드 호출 검증
        verify(budgetChangeRepository, times(1))
                .save(any(BudgetChange.class));
    }

    @Test
    @DisplayName("예산 수정 성공 - OPERATOR 권한으로 차감")
    void updateBudget_success_operator() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        BudgetUpdateRequest request =
                new BudgetUpdateRequest(
                        2000L,
                        BudgetType.DECREASE,
                        "비품 구매"
                );

        User user = mock(User.class);
        when(user.getName()).thenReturn("테스트 사용자");
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        Room room = mock(Room.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OPERATOR);

        when(connection.getUser()).thenReturn(user);

        when(roomRepository.findByWithLock(roomId))
                .thenReturn(room);

        when(room.getId()).thenReturn(roomId);
        when(room.getTotalBudget()).thenReturn(8000L);

        // when
        BudgetUpdateResponse response =
                budgetService.updateBudget(roomId, userId, request);

        // then
        assertNotNull(response);

        verify(room).updateTotalBudget(
                2000L,
                BudgetType.DECREASE
        );

        verify(budgetChangeRepository, times(1))
                .save(any(BudgetChange.class));
    }

    @Test
    @DisplayName("예산 수정 실패 - 권한 없음 (일반 MEMBER)")
    void updateBudget_forbiddenAccess() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        BudgetUpdateRequest request =
                new BudgetUpdateRequest(
                        1000L,
                        BudgetType.INCREASE,
                        "시도"
                );

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.MEMBER);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.updateBudget(
                        roomId, userId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.FORBIDDEN_ACCESS,
                exception.getErrorCode()
        );

        // 권한이 없으면 락 조회와 변경 이력 저장이 실행되지 않아야 함
        verify(roomRepository, never()).findByWithLock(anyLong());
        verify(budgetChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("예산 수정 실패 - 모임이 존재하지 않을 때")
    void updateBudget_roomNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        BudgetUpdateRequest request =
                new BudgetUpdateRequest(
                        1000L,
                        BudgetType.INCREASE,
                        "시도"
                );

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(roomRepository.findByWithLock(roomId))
                .thenReturn(null);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.updateBudget(
                        roomId, userId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.ROOM_NOT_FOUND,
                exception.getErrorCode()
        );

        verify(budgetChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("예산 수정 실패 - 가용예산 부족으로 차감 예외 발생 시 변경 이력이 저장되지 않음")
    void updateBudget_exceedAvailableBudget() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        BudgetUpdateRequest request =
                new BudgetUpdateRequest(
                        50000L,
                        BudgetType.DECREASE,
                        "과도한 차감"
                );

        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        Room room = mock(Room.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(roomRepository.findByWithLock(roomId))
                .thenReturn(room);

        // 실제 예산 차감 메서드에서 가용예산 부족 예외가 발생하는 상황을 모의
        doThrow(new BusinessException(
                ErrorCode.BAD_REQUEST,
                "가용예산이 부족합니다."
        ))
                .when(room)
                .updateTotalBudget(
                        50000L,
                        BudgetType.DECREASE
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetService.updateBudget(
                        roomId, userId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.BAD_REQUEST,
                exception.getErrorCode()
        );

        // 예외 발생 후 변경 이력이 저장되지 않아야 함
        verify(budgetChangeRepository, never()).save(any());
    }
}
