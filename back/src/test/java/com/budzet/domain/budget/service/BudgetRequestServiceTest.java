package com.budzet.domain.budget.service;

import com.budzet.domain.notification.service.NotificationService;
import com.budzet.domain.budget.dto.BudgetRequestResponse;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.entity.UserRoomConnectionId;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetRequestServiceTest {

    @Mock
    private BudgetRequestRepository budgetRequestRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Mock
    private User user;

    @Mock
    private Room room;

    @Mock
    private BudgetRequest budgetRequest;

    @Mock
    private BudgetRequestResponse response;

    @Mock
    private UserRoomConnection userRoomConnection;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private BudgetRequestService budgetRequestService;

    private final Long roomId = 10L;
    private final Long userId = 1L;
    private final Long requestId = 100L;

    @BeforeEach
    void setUp() {
        lenient().when(roomRepository.findByWithLock(roomId)).thenReturn(room);
        when(user.getId()).thenReturn(userId);

        lenient().when(userRoomConnectionRepository.existsById(
                any(UserRoomConnectionId.class)
        )).thenReturn(true);
    }

    private void mockRoom() {
        when(roomRepository.findByWithLock(roomId))
                .thenReturn(room);
    }

    private void mockRoomWithLock() {
        when(roomRepository.findByWithLock(roomId))
                .thenReturn(room);
    }

    private void mockAuthority(Authority authority) {
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(userRoomConnection);
        when(userRoomConnection.getAuthority()).thenReturn(authority);
    }

    private void mockBudgetRequest(BudgetRequestType status) {
        when(budgetRequestRepository.findByIdAndRoomId(requestId, roomId))
                .thenReturn(Optional.of(budgetRequest));
        when(budgetRequest.getStatus()).thenReturn(status);
    }

    // =========================
    // Budget request registration
    // =========================

    @Test
    @DisplayName("예산 신청 등록에 성공한다")
    void registerBudgetRequest_success() {
        mockRoom();
        when(room.getAvailableBudget()).thenReturn(1000L);

        budgetRequestService.budgetRequestRegistration(
                roomId, user, "테스트 신청", 500L
        );

        verify(budgetRequestRepository).save(any(BudgetRequest.class));
    }

    @Test
    @DisplayName("신청 금액이 가용 예산을 초과하면 예외가 발생한다")
    void registerBudgetRequest_exceedsAvailableBudget() {
        mockRoom();
        when(room.getAvailableBudget()).thenReturn(100L);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.budgetRequestRegistration(
                        roomId, user, "예산 초과", 200L
                )
        );

        verify(budgetRequestRepository, never()).save(any(BudgetRequest.class));
    }

    // =========================
    // Budget request retrieval
    // =========================

    @Test
    @DisplayName("예산 신청 목록을 조회한다")
    void getBudgetRequestList_success() {
        List<BudgetRequestResponse> expected = List.of(response);
        when(budgetRequestRepository.findDtoByRoomId(roomId)).thenReturn(expected);

        List<BudgetRequestResponse> actual =
                budgetRequestService.getBudgetRequestList(roomId, user);

        assertEquals(expected, actual);
        verify(budgetRequestRepository).findDtoByRoomId(roomId);
    }

    @Test
    @DisplayName("예산 신청 상세 정보를 조회한다")
    void getBudgetRequest_success() {
        when(budgetRequestRepository.findDtoById(roomId, requestId))
                .thenReturn(response);

        BudgetRequestResponse actual =
                budgetRequestService.getBudgetRequest(roomId, user, requestId);

        assertEquals(response, actual);
    }

    @Test
    @DisplayName("방에 소속되지 않은 사용자는 예산 신청 목록을 조회할 수 없다")
    void getBudgetRequestList_userNotInRoom() {
        when(userRoomConnectionRepository.existsById(
                any(UserRoomConnectionId.class)
        )).thenReturn(false);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.getBudgetRequestList(roomId, user)
        );

        verify(budgetRequestRepository, never()).findDtoByRoomId(anyLong());
    }

    // =========================
    // Budget request deletion
    // =========================

    @Test
    @DisplayName("예산 신청 삭제에 성공한다")
    void deleteBudgetRequest_success() {
        mockRoom();
        when(budgetRequestRepository.findDtoById(roomId, requestId))
                .thenReturn(response);
        when(response.getUserId()).thenReturn(userId);
        when(response.getStatus()).thenReturn(BudgetRequestType.REQUEST);

        budgetRequestService.deleteBudgetRequest(roomId, user, requestId);

        verify(budgetRequestRepository).deleteById(requestId);
        verify(room, never()).updateAvailableBudget(anyLong());
    }

    @Test
    @DisplayName("다른 사용자의 예산 신청은 삭제할 수 없다")
    void deleteBudgetRequest_notRequester() {
        mockRoom();
        when(budgetRequestRepository.findDtoById(roomId, requestId))
                .thenReturn(response);
        when(response.getUserId()).thenReturn(2L);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.deleteBudgetRequest(roomId, user, requestId)
        );

        verify(budgetRequestRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("승인된 예산 신청을 삭제하면 가용 예산을 복구한다")
    void deleteBudgetRequest_approvedRequest_restoresBudget() {
        mockRoom();
        when(budgetRequestRepository.findDtoById(roomId, requestId))
                .thenReturn(response);
        when(response.getUserId()).thenReturn(userId);
        when(response.getStatus()).thenReturn(BudgetRequestType.APPROVE);
        when(response.getRequestedAmount()).thenReturn(300L);

        budgetRequestService.deleteBudgetRequest(roomId, user, requestId);

        verify(room).updateAvailableBudget(300L);
        verify(budgetRequestRepository).deleteById(requestId);
    }

    @Test
    @DisplayName("존재하지 않는 예산 신청은 삭제할 수 없다")
    void deleteBudgetRequest_notFound() {
        mockRoom();
        when(budgetRequestRepository.findDtoById(roomId, requestId)).thenReturn(null);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.deleteBudgetRequest(roomId, user, requestId)
        );

        verify(budgetRequestRepository, never()).deleteById(anyLong());
    }

    // =========================
    // Budget request approval
    // =========================

    @Test
    @DisplayName("예산 신청 승인에 성공한다")
    void approveBudgetRequest_success() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.REQUEST);
        mockRoomWithLock();
        when(budgetRequest.getRequestedAmount()).thenReturn(200L);
        when(room.getAvailableBudget()).thenReturn(1000L);

        budgetRequestService.approveBudgetRequest(roomId, user, requestId);

        verify(room).updateAvailableBudget(-200L);
        verify(budgetRequest).approveRequest();
    }

    @Test
    @DisplayName("일반 회원은 예산 신청을 승인할 수 없다")
    void approveBudgetRequest_memberForbidden() {
        mockAuthority(Authority.MEMBER);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.approveBudgetRequest(roomId, user, requestId)
        );

        verify(budgetRequestRepository, never())
                .findByIdAndRoomId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("이미 승인된 예산 신청은 다시 승인할 수 없다")
    void approveBudgetRequest_alreadyApproved() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.APPROVE);
        mockRoomWithLock();

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.approveBudgetRequest(roomId, user, requestId)
        );

        verify(budgetRequest, never()).approveRequest();
        verify(room, never()).updateAvailableBudget(anyLong());
    }

    @Test
    @DisplayName("정산된 예산 신청은 승인할 수 없다")
    void approveBudgetRequest_settledRequest() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.SETTLEMENT);
        mockRoomWithLock();

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.approveBudgetRequest(roomId, user, requestId)
        );

        verify(budgetRequest, never()).approveRequest();
        verify(room, never()).updateAvailableBudget(anyLong());
    }

    @Test
    @DisplayName("가용 예산이 부족하면 예산 신청을 승인할 수 없다")
    void approveBudgetRequest_insufficientBudget() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.REQUEST);
        mockRoomWithLock();
        when(budgetRequest.getRequestedAmount()).thenReturn(200L);
        when(room.getAvailableBudget()).thenReturn(100L);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.approveBudgetRequest(roomId, user, requestId)
        );

        verify(budgetRequest, never()).approveRequest();
        verify(room, never()).updateAvailableBudget(anyLong());
    }

    // =========================
    // Budget request rejection
    // =========================

    @Test
    @DisplayName("예산 신청 반려에 성공한다")
    void rejectBudgetRequest_success() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.REQUEST);

        budgetRequestService.rejectBudgetRequest(
                roomId, user, requestId, "사유 불충분"
        );

        verify(budgetRequest).rejectRequest("사유 불충분");
    }

    @Test
    @DisplayName("일반 회원은 예산 신청을 반려할 수 없다")
    void rejectBudgetRequest_memberForbidden() {
        mockAuthority(Authority.MEMBER);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.rejectBudgetRequest(
                        roomId, user, requestId, "반려 사유"
                )
        );

        verify(budgetRequestRepository, never())
                .findByIdAndRoomId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("이미 반려된 예산 신청은 다시 반려할 수 없다")
    void rejectBudgetRequest_alreadyRejected() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.REJECT);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.rejectBudgetRequest(
                        roomId, user, requestId, "반려 사유"
                )
        );

        verify(budgetRequest, never()).rejectRequest(anyString());
    }

    @Test
    @DisplayName("정산된 예산 신청은 반려할 수 없다")
    void rejectBudgetRequest_settledRequest() {
        mockAuthority(Authority.OWNER);
        mockBudgetRequest(BudgetRequestType.SETTLEMENT);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.rejectBudgetRequest(
                        roomId, user, requestId, "반려 사유"
                )
        );

        verify(budgetRequest, never()).rejectRequest(anyString());
    }

    // =========================
    // Budget request modification
    // =========================

    @Test
    @DisplayName("예산 신청 수정에 성공한다")
    void modifyBudgetRequest_success() {
        mockBudgetRequest(BudgetRequestType.REQUEST);
        mockRoom();
        when(budgetRequest.getRequestedAmount()).thenReturn(100L);
        when(budgetRequest.getUser()).thenReturn(user);
        when(room.getAvailableBudget()).thenReturn(500L);

        budgetRequestService.modifyBudgetRequest(
                roomId, user, requestId, "수정된 사유", 200L
        );

        verify(budgetRequest).modifyRequest("수정된 사유", 200L);
    }

    @Test
    @DisplayName("다른 사용자의 예산 신청은 수정할 수 없다")
    void modifyBudgetRequest_notRequester() {
        mockBudgetRequest(BudgetRequestType.REQUEST);
        mockRoom();

        User otherUser = mock(User.class);
        when(otherUser.getId()).thenReturn(2L);
        when(budgetRequest.getRequestedAmount()).thenReturn(100L);
        when(budgetRequest.getUser()).thenReturn(otherUser);
        when(room.getAvailableBudget()).thenReturn(500L);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.modifyBudgetRequest(
                        roomId, user, requestId, "수정 사유", 200L
                )
        );

        verify(budgetRequest, never()).modifyRequest(anyString(), anyLong());
    }

    @Test
    @DisplayName("정산된 예산 신청은 수정할 수 없다")
    void modifyBudgetRequest_settledRequest() {
        mockBudgetRequest(BudgetRequestType.SETTLEMENT);
        mockRoom();

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.modifyBudgetRequest(
                        roomId, user, requestId, "수정 사유", 200L
                )
        );

        verify(budgetRequest, never()).modifyRequest(anyString(), anyLong());
    }

    @Test
    @DisplayName("신청 금액이 가용 예산을 초과하면 수정할 수 없다")
    void modifyBudgetRequest_exceedsAvailableBudget() {
        mockBudgetRequest(BudgetRequestType.REQUEST);
        mockRoom();

        when(budgetRequest.getRequestedAmount()).thenReturn(100L);
        when(room.getAvailableBudget()).thenReturn(50L);

        assertThrows(
                BusinessException.class,
                () -> budgetRequestService.modifyBudgetRequest(
                        roomId, user, requestId, "수정 사유", 200L
                )
        );

        verify(budgetRequest, never()).modifyRequest(anyString(), anyLong());
    }

    @Test
    @DisplayName("승인된 예산 신청의 금액을 수정하면 가용 예산을 재계산한다")
    void modifyBudgetRequest_approvedRequest_updatesAvailableBudget() {
        mockBudgetRequest(BudgetRequestType.APPROVE);
        mockRoom();
        when(budgetRequest.getRequestedAmount()).thenReturn(100L);
        when(budgetRequest.getUser()).thenReturn(user);
        when(room.getAvailableBudget()).thenReturn(500L);

        budgetRequestService.modifyBudgetRequest(
                roomId, user, requestId, "금액 변경", 200L
        );

        // 신청 금액이 100에서 200으로 증가하므로 가용 예산을 100 감소시킨다.
        verify(room).updateAvailableBudget(-100L);
        verify(budgetRequest).modifyRequest("금액 변경", 200L);
    }
}
