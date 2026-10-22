package com.budzet.domain.budget.service;

import com.budzet.domain.budget.dto.*;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import com.budzet.global.exception.BusinessException;
import com.budzet.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BudgetChangeServiceTest {

    @Mock
    private BudgetChangeRepository budgetChangeRepository;

    @Mock
    private BudgetRequestRepository budgetRequestRepository;

    @Mock
    private BudgetService budgetService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BudgetChangeService budgetChangeService;

    @Test
    @DisplayName("정산 등록 성공 - 정상적으로 예산이 차감되고 변경 이력이 저장됨")
    void createBudgetChange_success() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request = new BudgetChangeCreateRequest(8000L, "실제 장비 구매 영수증 첨부");

        User user = mock(User.class);
        UserRoomConnection connection = mock(UserRoomConnection.class);
        BudgetRequest budgetRequest = mock(BudgetRequest.class);
        Room room = mock(Room.class);

        when(room.getId()).thenReturn(roomId);
        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getUser()).thenReturn(user);

        when(budgetRequestRepository.findById(requestId)).thenReturn(Optional.of(budgetRequest));
        when(budgetRequest.getRoom()).thenReturn(room);
        when(budgetRequest.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(budgetRequest.getStatus()).thenReturn("APPROVE");
        when(budgetRequest.getRequestedAmount()).thenReturn(10000L);

        when(budgetService.findByRoomIdWithLock(roomId)).thenReturn(room);

        when(budgetChangeRepository.save(any(BudgetChange.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        BudgetChangeCreateResponse response = budgetChangeService.createBudgetChange(roomId, userId, requestId, request);

        // then
        assertNotNull(response);
        assertEquals(10000L, response.changeBudget());
        assertEquals(8000L, response.changedBudget());
        assertEquals(BudgetType.SETTLEMENT, response.budgetType());

        verify(room, times(1)).settleBudget(10000L, 8000L);
        verify(budgetChangeRepository, times(1)).save(any(BudgetChange.class));
    }

    @Test
    @DisplayName("정산 등록 실패 - 신청서가 존재하지 않을 때")
    void createBudgetChange_requestNotFound() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;
        BudgetChangeCreateRequest request = new BudgetChangeCreateRequest(8000L, "정산 사유");

        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetRequestRepository.findById(requestId)).thenReturn(Optional.empty());

        // when & then
        assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(roomId, userId, requestId, request)
        );

        verify(budgetService, never()).findByRoomIdWithLock(any());
        verify(budgetChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 신청서의 작성자가 아닐 때")
    void createBudgetChange_forbiddenRequestWriter() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long otherUserId = 200L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request = new BudgetChangeCreateRequest(8000L, "정산 사유");

        UserRoomConnection connection = mock(UserRoomConnection.class);
        BudgetRequest budgetRequest = mock(BudgetRequest.class);
        User writerUser = mock(User.class);
        Room room = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetRequestRepository.findById(requestId)).thenReturn(Optional.of(budgetRequest));

        when(budgetRequest.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        when(budgetRequest.getUser()).thenReturn(writerUser);
        when(writerUser.getId()).thenReturn(otherUserId);

        // when & then
        assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(roomId, userId, requestId, request)
        );

        verify(budgetChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 승인(APPROVED)된 요청이 아닐 때")
    void createBudgetChange_notApproved() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request = new BudgetChangeCreateRequest(8000L, "정산 사유");

        User user = mock(User.class);
        UserRoomConnection connection = mock(UserRoomConnection.class);
        BudgetRequest budgetRequest = mock(BudgetRequest.class);
        Room room = mock(Room.class);

        when(budgetRequest.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetRequestRepository.findById(requestId)).thenReturn(Optional.of(budgetRequest));

        when(budgetRequest.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(budgetRequest.getStatus()).thenReturn("PENDING");

        // when & then
        assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(roomId, userId, requestId, request)
        );

        verify(budgetChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 방 멤버가 아닐 때")
    void createBudgetChange_userNotJoinedRoom() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;
        BudgetChangeCreateRequest request = new BudgetChangeCreateRequest(8000L, "정산 사유");

        when(budgetService.validateRoomMember(roomId, userId))
                .thenThrow(new BusinessException(null, "사용자가 방에 속해있지 않습니다."));

        // when & then
        assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(roomId, userId, requestId, request)
        );

        verify(budgetRequestRepository, never()).findById(any());
        verify(budgetChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("정산 내역 목록 조회 성공 - SETTLEMENT 타입의 내역만 최신순 반환")
    void budgetChangeList_success() {
        // given
        Long roomId = 1L;
        Long userId = 100L;

        UserRoomConnection connection = mock(UserRoomConnection.class);
        BudgetChange settlementChange1 = mock(BudgetChange.class);
        BudgetChange settlementChange2 = mock(BudgetChange.class);
        List<BudgetChange> settlementChanges = List.of(settlementChange1, settlementChange2);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetChangeRepository.findAllByRoomIdAndTypeOrderByCreatedAtDesc(roomId, BudgetType.SETTLEMENT))
                .thenReturn(settlementChanges);

        // when
        BudgetChangeListResponse response = budgetChangeService.budgetChangeList(roomId, userId);

        // then
        assertNotNull(response);

        // 검증 1: 방 멤버 검증 호출 확인
        verify(budgetService, times(1)).validateRoomMember(roomId, userId);

        // 검증 2: roomId와 BudgetType.SETTLEMENT 조건으로 조회가 발생했는지 확인
        verify(budgetChangeRepository, times(1))
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(eq(roomId), eq(BudgetType.SETTLEMENT));
    }

    @Test
    @DisplayName("정산 내역 목록 조회 실패 - 방 멤버가 아닐 때")
    void budgetChangeList_userNotJoinedRoom() {
        // given
        Long roomId = 1L;
        Long userId = 100L;

        // 멤버 검증 시 예외 발생 모킹
        when(budgetService.validateRoomMember(roomId, userId))
                .thenThrow(new BusinessException(null, "사용자가 방에 속해있지 않습니다."));

        // when & then
        assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeList(roomId, userId)
        );

        // 검증: 멤버 검증 실패 시 DB 조회가 실행되지 않아야 함
        verify(budgetChangeRepository, never())
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(anyLong(), any());
    }

    @Test
    @DisplayName("정산 내역 목록 조회 성공 - 정산 내역이 없는 경우 빈 리스트 반환")
    void budgetChangeList_emptyList() {
        // given
        Long roomId = 1L;
        Long userId = 100L;

        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetChangeRepository.findAllByRoomIdAndTypeOrderByCreatedAtDesc(roomId, BudgetType.SETTLEMENT))
                .thenReturn(Collections.emptyList());

        // when
        BudgetChangeListResponse response = budgetChangeService.budgetChangeList(roomId, userId);

        // then
        assertNotNull(response);

        verify(budgetService, times(1)).validateRoomMember(roomId, userId);
        verify(budgetChangeRepository, times(1))
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(eq(roomId), eq(BudgetType.SETTLEMENT));
    }

    @Test
    @DisplayName("예산 변경 내역 상세 조회 성공")
    void budgetChangeDetail_success() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 50L;
        Long requestId = 30L;

        UserRoomConnection connection = mock(UserRoomConnection.class);
        BudgetChange budgetChange = mock(BudgetChange.class);
        Room room = mock(Room.class);
        User user = mock(User.class);
        BudgetRequest request = mock(BudgetRequest.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetChangeRepository.findById(changeId)).thenReturn(Optional.of(budgetChange));
        when(budgetChange.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);
        when(budgetChange.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(budgetChange.getRequest()).thenReturn(request);
        when(request.getId()).thenReturn(requestId);

        // when
        BudgetChangeDetailResponse response = budgetChangeService.budgetChangeDetail(roomId, userId, changeId);

        // then
        assertNotNull(response);

        verify(budgetService, times(1)).validateRoomMember(roomId, userId);
        verify(budgetChangeRepository, times(1)).findById(changeId);
    }

    @Test
    @DisplayName("예산 변경 내역 상세 조회 실패 - 해당 내역이 존재하지 않을 때")
    void budgetChangeDetail_notFound() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 999L;

        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(budgetChangeRepository.findById(changeId)).thenReturn(Optional.empty());

        // when & then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeDetail(roomId, userId, changeId)
        );

        assertEquals(ErrorCode.BUDGET_CHANGE_NOT_FOUND, exception.getErrorCode());

        verify(budgetService, times(1)).validateRoomMember(roomId, userId);
        verify(budgetChangeRepository, times(1)).findById(changeId);
    }

    @Test
    @DisplayName("예산 변경 내역 상세 조회 실패 - 방 멤버가 아닐 때")
    void budgetChangeDetail_userNotJoinedRoom() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 50L;

        when(budgetService.validateRoomMember(roomId, userId))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_JOINED_ROOM));

        // when & then
        assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeDetail(roomId, userId, changeId)
        );

        verify(budgetChangeRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("정산 수정 성공 - 신청 금액 및 정산 금액이 정상 수정되고 예산이 반영됨")
    void updateBudgetChange_success() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;

        // DTO 파라미터 순서: (changeBudget, changedBudget, userName, userEmail, reason)
        BudgetChangeUpdateRequest updateRequest = new BudgetChangeUpdateRequest(
                6000L, 4000L, "홍길동", "test@test.com", "수정된 사유"
        );

        User user = mock(User.class);
        UserRoomConnection connection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class); // 대상 유저 멤버 검증용
        BudgetChange budgetChange = mock(BudgetChange.class);
        BudgetRequest budgetRequest = mock(BudgetRequest.class);
        Room room = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail("홍길동", "test@test.com")).thenReturn(Optional.of(user));

        // 새로 추가된 대상 유저의 방 멤버 검증 모킹
        when(budgetService.validateRoomMember(roomId, user.getId())).thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId)).thenReturn(Optional.of(budgetChange));

        // 새로 추가된 정산 타입 검증 모킹 (SETTLEMENT 리턴)
        when(budgetChange.getType()).thenReturn(BudgetType.SETTLEMENT);

        when(budgetChange.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);
        when(budgetChange.getUser()).thenReturn(user);

        // 락을 걸고 Room 조회
        when(budgetService.findByRoomIdWithLock(roomId)).thenReturn(room);

        // 기존 값: 신청금액 5000원 -> 수정 요청: 6000원 (+1000원)
        when(budgetChange.getChangeBudget()).thenReturn(5000L);
        // 가용예산 2000원 남아있어 1000원 증액 가능
        when(room.getAvailableBudget()).thenReturn(2000L);

        // 기존 값: 정산금액 3000원 -> 수정 요청: 4000원 (차액: -1000원)
        when(budgetChange.getChangedBudget()).thenReturn(3000L);

        when(budgetChange.getRequest()).thenReturn(budgetRequest);

        // when
        BudgetChangeUpdateResponse response = budgetChangeService.updateBudgetChange(roomId, userId, changeId, updateRequest);

        // then
        assertNotNull(response);

        // 1. 락 조회 호출 검증
        verify(budgetService, times(1)).findByRoomIdWithLock(roomId);

        // 2. 예산 차액 반영 호출 검증 (3000L - 4000L = -1000L)
        verify(room, times(1)).updateTotalBudget(-1000L);

        // 3. Entity 수정 메서드 호출 검증
        verify(budgetChange, times(1)).updateChange(updateRequest, user);
        verify(budgetRequest, times(1)).updateRequest(budgetChange, user);
    }

    @Test
    @DisplayName("정산 수정 실패 - 방장(OWNER) 권한이 아닐 때")
    void updateBudgetChange_forbiddenNotOwner() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;
        BudgetChangeUpdateRequest updateRequest = new BudgetChangeUpdateRequest(
                6000L, 4000L, "홍길동", "test@test.com", "수정 사유"
        );

        UserRoomConnection connection = mock(UserRoomConnection.class);
        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.MEMBER); // 일반 멤버

        // when & then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(roomId, userId, changeId, updateRequest)
        );

        assertEquals(ErrorCode.FORBIDDEN_ACCESS, exception.getErrorCode());
        verify(budgetService, never()).findByRoomIdWithLock(any());
    }

    @Test
    @DisplayName("정산 수정 실패 - 유저 이름과 이메일이 일치하지 않을 때")
    void updateBudgetChange_userNotFound() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;
        BudgetChangeUpdateRequest updateRequest = new BudgetChangeUpdateRequest(
                6000L, 4000L, "없는유저", "wrong@test.com", "수정 사유"
        );

        UserRoomConnection connection = mock(UserRoomConnection.class);
        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail("없는유저", "wrong@test.com")).thenReturn(Optional.empty());

        // when & then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(roomId, userId, changeId, updateRequest)
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verify(budgetService, never()).findByRoomIdWithLock(any());
    }

    @Test
    @DisplayName("정산 수정 실패 - 수정할 정산 내역이 존재하지 않을 때")
    void updateBudgetChange_budgetChangeNotFound() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;
        BudgetChangeUpdateRequest updateRequest = new BudgetChangeUpdateRequest(
                6000L, 4000L, "홍길동", "test@test.com", "수정 사유"
        );

        User user = mock(User.class);
        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRepository.findByNameAndEmail(any(), any())).thenReturn(Optional.of(user));
        when(budgetChangeRepository.findById(changeId)).thenReturn(Optional.empty());

        // when & then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(roomId, userId, changeId, updateRequest)
        );

        assertEquals(ErrorCode.BUDGET_CHANGE_NOT_FOUND, exception.getErrorCode());
        verify(budgetService, never()).findByRoomIdWithLock(any());
    }

    @Test
    @DisplayName("정산 수정 실패 - 유효하지 않은 방의 정산건인 경우")
    void updateBudgetChange_invalidRoomId() {
        // given
        Long roomId = 1L;
        Long otherRoomId = 2L;
        Long userId = 100L;
        Long changeId = 10L;
        BudgetChangeUpdateRequest updateRequest = new BudgetChangeUpdateRequest(
                6000L, 4000L, "홍길동", "test@test.com", "수정 사유"
        );

        User user = mock(User.class);
        UserRoomConnection connection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class);
        BudgetChange budgetChange = mock(BudgetChange.class);
        Room otherRoom = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRepository.findByNameAndEmail(any(), any())).thenReturn(Optional.of(user));

        // 대상 유저 멤버 검증 모킹 추가
        when(budgetService.validateRoomMember(roomId, user.getId())).thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId)).thenReturn(Optional.of(budgetChange));

        // 정산 타입 검증 모킹 추가
        when(budgetChange.getType()).thenReturn(BudgetType.SETTLEMENT);

        when(budgetChange.getRoom()).thenReturn(otherRoom);
        when(otherRoom.getId()).thenReturn(otherRoomId); // 다른 방 ID

        // when & then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(roomId, userId, changeId, updateRequest)
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        verify(budgetService, never()).findByRoomIdWithLock(any());
    }

    @Test
    @DisplayName("정산 수정 실패 - 신청 금액 증액분이 현재 가용 예산을 초과할 때")
    void updateBudgetChange_requestAmountOverBudget() {
        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;

        // 기존 신청금액 5000원 -> 수정 요청: 8000원 (3000원 더 신청)
        BudgetChangeUpdateRequest updateRequest = new BudgetChangeUpdateRequest(
                8000L, 4000L, "홍길동", "test@test.com", "수정 사유"
        );

        User user = mock(User.class);
        UserRoomConnection connection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class); // 대상 유저 멤버 검증용
        BudgetChange budgetChange = mock(BudgetChange.class);
        Room room = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId)).thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRepository.findByNameAndEmail(any(), any())).thenReturn(Optional.of(user));

        // 새로 추가된 대상 유저의 방 멤버 검증 모킹
        when(budgetService.validateRoomMember(roomId, user.getId())).thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId)).thenReturn(Optional.of(budgetChange));

        // 새로 추가된 정산 타입 검증 모킹 (SETTLEMENT 리턴)
        when(budgetChange.getType()).thenReturn(BudgetType.SETTLEMENT);

        when(budgetChange.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        // 락 조회
        when(budgetService.findByRoomIdWithLock(roomId)).thenReturn(room);

        when(budgetChange.getChangeBudget()).thenReturn(5000L);
        // 가용예산은 1000원만 남아있어 3000원 증액 불가능!
        when(room.getAvailableBudget()).thenReturn(1000L);

        // when & then
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(roomId, userId, changeId, updateRequest)
        );

        assertEquals(ErrorCode.REQUEST_AMOUNT_OVER_BUDGET, exception.getErrorCode());

        // 예산 초과로 인해서 뒤쪽 예산 업데이트 및 엔티티 수정이 실행되지 않았는지 검증
        verify(room, never()).updateTotalBudget(any());
        verify(budgetChange, never()).updateChange(any(), any());
    }
}