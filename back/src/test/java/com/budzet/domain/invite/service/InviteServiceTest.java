package com.budzet.domain.invite.service;

import com.budzet.domain.notification.service.NotificationService;
import com.budzet.domain.invite.dto.InviteResponse;
import com.budzet.domain.invite.dto.InviteJoinResponse;
import com.budzet.domain.invite.entity.Invite;
import com.budzet.domain.invite.repository.InviteRepository;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private InviteRepository inviteRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private InviteService inviteService;

    @Test
    void createInviteByOwner() {
        // given
        Long roomId = 1L;
        Long userId = 1L;

        Room room = mock(Room.class);

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        // when
        InviteResponse response =
                inviteService.createInvite(roomId, userId);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getCode()).isNotNull();
        assertThat(response.getCode()).hasSize(10);
        assertThat(response.getExpireAt()).isNotNull();

        verify(inviteRepository)
                .save(any(Invite.class));
    }

    @Test
    void createInviteByMember() {
        // given
        Long roomId = 1L;
        Long userId = 2L;

        Room room = mock(Room.class);

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(roomRepository.findById(roomId))
                .thenReturn(Optional.of(room));

        when(userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.MEMBER);

        // when & then
        assertThatThrownBy(() ->
                inviteService.createInvite(roomId, userId)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.OWNER_REQUIRED
                );

        verify(inviteRepository, never())
                .save(any(Invite.class));
    }

    @Test
    void verifyValidInvite() {
        // given
        String token = "ABC1234567";

        Invite invite = mock(Invite.class);

        when(inviteRepository.findById(token))
                .thenReturn(Optional.of(invite));

        when(invite.getExpireAt())
                .thenReturn(LocalDateTime.now().plusDays(1));

        when(invite.getCode())
                .thenReturn(token);

        // when
        InviteResponse response =
                inviteService.verifyInvite(token);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getCode())
                .isEqualTo(token);
        assertThat(response.getExpireAt())
                .isNotNull();

        verify(inviteRepository)
                .findById(token);
    }

    @Test
    void verifyNonExistentInvite() {
        // given
        String token = "NOTFOUND";

        when(inviteRepository.findById(token))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                inviteService.verifyInvite(token)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.NOT_FOUND
                );

        verify(inviteRepository)
                .findById(token);
    }

    @Test
    void verifyExpiredInvite() {
        // given
        String token = "EXPIRED123";

        Invite invite = mock(Invite.class);

        when(inviteRepository.findById(token))
                .thenReturn(Optional.of(invite));

        when(invite.getExpireAt())
                .thenReturn(LocalDateTime.now().minusHours(1));

        // when & then
        assertThatThrownBy(() ->
                inviteService.verifyInvite(token)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.CONFLICT
                );

        verify(inviteRepository)
                .findById(token);
    }

    @Test
    @DisplayName("유효한 초대 코드로 모임에 참여할 수 있다.")
    void joinRoom() {
        String code = "ABC1234567";
        Long userId = 1L;
        Long roomId = 2L;

        Invite invite = mock(Invite.class);
        Room room = mock(Room.class);
        User user = mock(User.class);

        when(inviteRepository.findById(code)).thenReturn(Optional.of(invite));
        when(invite.getExpireAt()).thenReturn(LocalDateTime.now().plusHours(1));
        when(invite.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);
        when(roomRepository.findByWithLock(roomId)).thenReturn(room);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(null);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(userRoomConnectionRepository.save(any(UserRoomConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InviteJoinResponse response = inviteService.joinRoom(code, userId);

        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getRoomId()).isEqualTo(roomId);
        assertThat(response.getAuthority()).isEqualTo(Authority.MEMBER.name());
        assertThat(response.getJoined()).isTrue();
        verify(roomRepository).findByWithLock(roomId);
        verify(userRoomConnectionRepository).save(any(UserRoomConnection.class));
    }

    @Test
    @DisplayName("존재하지 않는 초대 코드 사용 시, 예외 발생")
    void joinRoomWithNonExistentInvite() {
        String code = "NOTFOUND";

        when(inviteRepository.findById(code)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteService.joinRoom(code, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVITE_NOT_FOUND);

        verify(userRoomConnectionRepository, never()).save(any(UserRoomConnection.class));
    }

    @Test
    @DisplayName("만료된 초대 코드 사용 시, 예외 발생")
    void joinRoomWithExpiredInvite() {
        String code = "EXPIRED123";
        Long roomId = 2L;
        Invite invite = mock(Invite.class);
        Room room = mock(Room.class);

        when(inviteRepository.findById(code)).thenReturn(Optional.of(invite));
        when(invite.getExpireAt()).thenReturn(LocalDateTime.now().minusSeconds(1));
        when(invite.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);
        when(roomRepository.findByWithLock(roomId)).thenReturn(room);

        assertThatThrownBy(() -> inviteService.joinRoom(code, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVITE_EXPIRED);

        verify(userRoomConnectionRepository, never()).save(any(UserRoomConnection.class));
    }

    @Test
    @DisplayName("이미 참여한 멤버가 중복 참여 요청 시, 예외 발생")
    void joinRoomWhenAlreadyJoined() {
        String code = "ABC1234567";
        Long userId = 1L;
        Long roomId = 2L;
        Invite invite = mock(Invite.class);
        Room room = mock(Room.class);

        when(inviteRepository.findById(code)).thenReturn(Optional.of(invite));
        when(invite.getExpireAt()).thenReturn(LocalDateTime.now().plusHours(1));
        when(invite.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);
        when(roomRepository.findByWithLock(roomId)).thenReturn(room);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(mock(UserRoomConnection.class));

        assertThatThrownBy(() -> inviteService.joinRoom(code, userId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_ALREADY_JOINED_ROOM);

        verify(roomRepository).findByWithLock(roomId);
        verify(userRoomConnectionRepository, never()).save(any(UserRoomConnection.class));
    }

    @Test
    void getInvites() {
        Long roomId = 1L;
        mockInviteOwner(roomId);

        Invite invite1 = mock(Invite.class);
        Invite invite2 = mock(Invite.class);

        LocalDateTime expireAt1 = LocalDateTime.now().plusHours(1);
        LocalDateTime expireAt2 = LocalDateTime.now().plusHours(2);

        when(inviteRepository.findAllByRoom_Id(roomId))
                .thenReturn(List.of(invite1, invite2));

        when(invite1.getCode()).thenReturn("ABC1234567");
        when(invite1.getExpireAt()).thenReturn(expireAt1);

        when(invite2.getCode()).thenReturn("DEF1234567");
        when(invite2.getExpireAt()).thenReturn(expireAt2);

        List<InviteResponse> result =
                inviteService.getInvites(roomId, 1L);

        assertThat(result).hasSize(2);

        assertThat(result.get(0).getCode())
                .isEqualTo("ABC1234567");
        assertThat(result.get(0).getExpireAt())
                .isEqualTo(expireAt1);

        assertThat(result.get(1).getCode())
                .isEqualTo("DEF1234567");
        assertThat(result.get(1).getExpireAt())
                .isEqualTo(expireAt2);

        verify(inviteRepository).findAllByRoom_Id(roomId);
    }

    @Test
    void getInvitesWhenEmpty() {
        Long roomId = 1L;
        mockInviteOwner(roomId);

        when(inviteRepository.findAllByRoom_Id(roomId))
                .thenReturn(List.of());

        List<InviteResponse> result =
                inviteService.getInvites(roomId, 1L);

        assertThat(result).isEmpty();

        verify(inviteRepository).findAllByRoom_Id(roomId);
    }
    private void mockInviteOwner(Long roomId) {
        UserRoomConnection connection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(1L, roomId))
                .thenReturn(connection);
        when(connection.getAuthority()).thenReturn(Authority.OWNER);
    }
}
