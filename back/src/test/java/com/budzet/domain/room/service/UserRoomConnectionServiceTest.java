package com.budzet.domain.room.service;

import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.room.dto.MemberResponse;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.entity.UserRoomConnectionId;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.global.exception.BusinessException;
import com.budzet.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRoomConnectionServiceTest {

    // 가짜 Repository 생성
    @Mock
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private BudgetRequestRepository budgetRequestRepository;

    @Mock
    private Room room;

    @BeforeEach
    void setUp() {
        lenient().when(roomRepository.findByWithLock(1L)).thenReturn(room);
        lenient().when(room.getId()).thenReturn(1L);
    }

    // Mockito가 가짜 Repository를 Service에 주입
    @InjectMocks
    private UserRoomConnectionService userRoomConnectionService;

    @Test
    @DisplayName("멤버 연결 조회 성공")
    void getConnection() {
        mockMembership();

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(connection);

        UserRoomConnection result =
                userRoomConnectionService.getConnection(1L, 2L, 1L);

        assertSame(connection, result);
    }

    @Test
    @DisplayName("존재하지 않는 멤버 연결 조회 시 예외 발생")
    void getConnection_memberNotFound() {
        mockMembership();

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userRoomConnectionService.getConnection(1L, 2L, 1L)
        );

        assertThat(exception.getErrorCode().getMessage())
                .isEqualTo("멤버를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("모임 멤버 목록 조회 성공")
    void getMembers_success() {
        mockMembership();

        UserRoomConnection connection1 =
                mock(UserRoomConnection.class);

        UserRoomConnection connection2 =
                mock(UserRoomConnection.class);

        when(connection1.getUser())
                .thenReturn(mock(com.budzet.domain.user.entity.User.class));

        when(connection2.getUser())
                .thenReturn(mock(com.budzet.domain.user.entity.User.class));

        when(connection1.getUser().getId())
                .thenReturn(1L);

        when(connection1.getUser().getName())
                .thenReturn("홍길동");

        when(connection1.getAuthority())
                .thenReturn(Authority.OWNER);

        when(connection2.getUser().getId())
                .thenReturn(2L);

        when(connection2.getUser().getName())
                .thenReturn("김철수");

        when(connection2.getAuthority())
                .thenReturn(Authority.MEMBER);

        when(userRoomConnectionRepository.findAllByRoom_Id(1L))
                .thenReturn(List.of(connection1, connection2));

        List<MemberResponse> result =
                userRoomConnectionService.getMembers(1L, 2L);

        assertEquals(2, result.size());

        assertEquals(1L, result.get(0).getUserId());
        assertEquals("홍길동", result.get(0).getName());
        assertEquals(Authority.OWNER, result.get(0).getAuthority());

        assertEquals(2L, result.get(1).getUserId());
        assertEquals("김철수", result.get(1).getName());
        assertEquals(Authority.MEMBER, result.get(1).getAuthority());
    }

    @Test
    @DisplayName("OWNER가 멤버를 강퇴할 수 있다")
    void kickMember_owner_success() {

        UserRoomConnection actorConnection =
                mock(UserRoomConnection.class);

        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(actorConnection);

        when(actorConnection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(targetConnection);

        userRoomConnectionService.kickMember(
                1L,
                1L,
                2L
        );

        verify(userRoomConnectionRepository)
                .delete(targetConnection);
    }

    @Test
    @DisplayName("OPERATOR가 멤버를 강퇴할 수 있다")
    void kickMember_operator_success() {

        UserRoomConnection actorConnection =
                mock(UserRoomConnection.class);

        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(actorConnection);

        when(actorConnection.getAuthority())
                .thenReturn(Authority.OPERATOR);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(targetConnection);

        userRoomConnectionService.kickMember(
                1L,
                1L,
                2L
        );

        verify(userRoomConnectionRepository)
                .delete(targetConnection);
    }

    @Test
    @DisplayName("MEMBER는 멤버를 강퇴할 수 없다")
    void kickMember_member_forbidden() {

        UserRoomConnection actorConnection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(actorConnection);

        when(actorConnection.getAuthority())
                .thenReturn(Authority.MEMBER);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userRoomConnectionService.kickMember(
                        1L,
                        1L,
                        2L
                )
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.ROOM_MANAGER_REQUIRED);
    }

    @Test
    @DisplayName("OWNER는 강퇴할 수 없다")
    void kickMember_ownerTarget_forbidden() {

        UserRoomConnection actorConnection =
                mock(UserRoomConnection.class);

        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(actorConnection);

        when(actorConnection.getAuthority())
                .thenReturn(Authority.OPERATOR);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(targetConnection);

        when(targetConnection.getAuthority())
                .thenReturn(Authority.OWNER);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userRoomConnectionService.kickMember(
                        1L,
                        1L,
                        2L
                )
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.INVALID_AUTHORITY);
    }

    @Test
    @DisplayName("존재하지 않는 멤버 강퇴 시 예외 발생")
    void kickMember_memberNotFound() {

        UserRoomConnection actorConnection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(1L, 1L))
                .thenReturn(actorConnection);

        when(actorConnection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userRoomConnectionService.kickMember(
                        1L,
                        1L,
                        2L
                )
        );

        assertThat(exception.getErrorCode().getMessage())
                .isEqualTo("멤버를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("모임 탈퇴 성공")
    void leaveRoom_success() {

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(connection);

        userRoomConnectionService.leaveRoom(1L, 2L);

        verify(userRoomConnectionRepository)
                .delete(connection);
    }

    @Test
    @DisplayName("존재하지 않는 멤버가 모임 탈퇴 시 예외 발생")
    void leaveRoom_memberNotFound() {

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userRoomConnectionService.leaveRoom(1L, 2L)
        );

        assertThat(exception.getErrorCode().getMessage())
                .isEqualTo("멤버를 찾을 수 없습니다.");
    }


    @Test
    @DisplayName("탈퇴 시 승인 신청의 예약 금액만 반환하고 미정산 신청을 삭제한다")
    void leaveRoom_restoresApprovedReservations() {
        UserRoomConnection connection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(connection);
        BudgetRequest pending = new BudgetRequest(room, new User("a", "p", "n"), "대기", 50L);
        BudgetRequest approved = new BudgetRequest(room, pending.getUser(), "승인", 100L);
        approved.approveRequest();
        BudgetRequest rejected = new BudgetRequest(room, pending.getUser(), "반려", 70L);
        rejected.rejectRequest("반려");
        List<BudgetRequest> requests = List.of(pending, approved, rejected);
        when(budgetRequestRepository.findUnsettledByMemberWithLock(1L, 2L,
                List.of(BudgetRequestType.REQUEST, BudgetRequestType.REJECT, BudgetRequestType.APPROVE)))
                .thenReturn(requests);

        userRoomConnectionService.leaveRoom(1L, 2L);

        verify(room).updateAvailableBudget(100L);
        verify(budgetRequestRepository).deleteAll(requests);
        verify(userRoomConnectionRepository).delete(connection);
    }

    @Test
    @DisplayName("방장 탈퇴 실패 시 예산과 신청을 변경하지 않는다")
    void leaveRoom_ownerForbidden() {
        UserRoomConnection connection = mock(UserRoomConnection.class);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(2L, 1L))
                .thenReturn(connection);

        assertThrows(BusinessException.class, () -> userRoomConnectionService.leaveRoom(1L, 2L));

        verifyNoInteractions(budgetRequestRepository);
        verify(room, never()).updateAvailableBudget(anyLong());
        verify(userRoomConnectionRepository, never()).delete(any());
    }

    private void mockMembership() {
        when(userRoomConnectionRepository.existsById(new UserRoomConnectionId(2L, 1L)))
                .thenReturn(true);
    }
}
