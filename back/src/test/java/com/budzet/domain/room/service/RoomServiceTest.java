package com.budzet.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.budzet.domain.room.dto.RoomCreateRequest;
import com.budzet.domain.room.dto.RoomCreateResponse;
import com.budzet.domain.room.dto.RoomDetailResponse;
import com.budzet.domain.room.dto.RoomListResponse;
import com.budzet.domain.room.dto.RoomUpdateRequest;
import com.budzet.domain.room.dto.AuthorityChangeRequest;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RoomService roomService;

    @Test
    @DisplayName("모임 생성 시, 설정한 초기 자금은 총/가용 예산 둘 다 반영된다.")
    void createRoom_initializesAvailableBudgetWithTotalBudget() {
        Long userId = 1L;
        User user = mock(User.class);
        RoomCreateRequest request = new RoomCreateRequest(
                "한양대 사진동아리 렌즈",
                100_000L,
                Currency.KRW
        );
        when(roomRepository.save(any(Room.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        RoomCreateResponse response = roomService.createRoom(userId, request);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(roomCaptor.capture());

        Room savedRoom = roomCaptor.getValue();

        assertThat(savedRoom.getName()).isEqualTo("한양대 사진동아리 렌즈");
        assertThat(savedRoom.getTotalBudget()).isEqualTo(100_000L);
        assertThat(savedRoom.getAvailableBudget()).isEqualTo(100_000L);
        assertThat(savedRoom.getCurrency()).isEqualTo(Currency.KRW);
        assertThat(response.availableBudget()).isEqualTo(100_000L);

        ArgumentCaptor<UserRoomConnection> connectionCaptor =
                ArgumentCaptor.forClass(UserRoomConnection.class);
        verify(userRoomConnectionRepository).save(connectionCaptor.capture());

        UserRoomConnection ownerConnection = connectionCaptor.getValue();
        verify(userRepository).getReferenceById(userId);
        assertThat(ownerConnection.getUser()).isSameAs(user);
        assertThat(ownerConnection.getRoom()).isSameAs(savedRoom);
        assertThat(ownerConnection.getAuthority()).isEqualTo(Authority.OWNER);
        assertThat(ownerConnection.isJoined()).isTrue();
    }

    @Test
    @DisplayName("내가 참여한 모임 목록을 조회할 수 있다.")
    void getRooms_returnsJoinedRoomList() {
        Long userId = 1L;
        Room firstRoom = Room.create("사진동아리", 100_000L, Currency.KRW);
        Room secondRoom = Room.create("해외여행", 500L, Currency.USD);
        when(roomRepository.findAllJoinedRoomsByUserId(userId))
                .thenReturn(List.of(firstRoom, secondRoom));

        RoomListResponse response = roomService.getRooms(userId);

        verify(roomRepository).findAllJoinedRoomsByUserId(userId);
        assertThat(response.rooms().size()).isEqualTo(2);

        assertThat(response.rooms().getFirst().name()).isEqualTo("사진동아리");
        assertThat(response.rooms().getFirst().availableBudget()).isEqualTo(100_000L);
        assertThat(response.rooms().getFirst().currency()).isEqualTo(Currency.KRW.toString());

        assertThat(response.rooms().get(1).name()).isEqualTo("해외여행");
        assertThat(response.rooms().get(1).availableBudget()).isEqualTo(500L);
        assertThat(response.rooms().get(1).currency()).isEqualTo(Currency.USD.toString());
    }

    @Test
    @DisplayName("참여한 모임이 없다면, 결과는 빈 목록이다.")
    void getRooms_returnsEmptyListWhenNoJoinedRoomsExist() {
        Long userId = 1L;
        when(roomRepository.findAllJoinedRoomsByUserId(userId))
                .thenReturn(List.of());

        RoomListResponse response = roomService.getRooms(userId);

        verify(roomRepository).findAllJoinedRoomsByUserId(userId);
        assertTrue(response.rooms().isEmpty());
    }

    @Test
    @DisplayName("내가 참여한 특정 모임의 상세 정보를 조회할 수 있다.")
    void getRoom_returnsJoinedRoomDetail() {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("사진동아리", 100_000L, Currency.KRW);
        when(roomRepository.findJoinedRoomByIdAndUserId(roomId, userId))
                .thenReturn(Optional.of(room));

        RoomDetailResponse response = roomService.getRoom(userId, roomId);

        verify(roomRepository).findJoinedRoomByIdAndUserId(roomId, userId);

        assertThat(response.name()).isEqualTo("사진동아리");
        assertThat(response.totalBudget()).isEqualTo(100_000L);
        assertThat(response.availableBudget()).isEqualTo(100_000L);
        assertThat(response.currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("내가 참여하지 않은 모임의 상세 정보 조회 시, 에러 발생")
    void getRoom_throwsNotFoundExceptionWhenRoomIsNotJoinedByUser() {
        Long userId = 1L;
        Long roomId = 10L;
        when(roomRepository.findJoinedRoomByIdAndUserId(roomId, userId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.getRoom(userId, roomId)
        );

        verify(roomRepository).findJoinedRoomByIdAndUserId(roomId, userId);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("해당 모임이 존재하지 않습니다.");
    }

    @Test
    @DisplayName("모임장은 모임 이름을 수정할 수 있다.")
    void updateRoom_updatesNameWhenUserIsOwner() {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("기존 이름", 100_000L, Currency.KRW);
        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(Optional.of(connection));
        when(connection.getAuthority()).thenReturn(Authority.OWNER);

        RoomDetailResponse response = roomService.updateRoom(
                userId,
                roomId,
                new RoomUpdateRequest("수정된 이름")
        );

        verify(roomRepository).findById(roomId);
        verify(userRoomConnectionRepository).findByUser_IdAndRoom_Id(userId, roomId);
        assertThat(room.getName()).isEqualTo("수정된 이름");
        assertThat(response.name()).isEqualTo("수정된 이름");
        assertThat(response.totalBudget()).isEqualTo(100_000L);
        assertThat(response.availableBudget()).isEqualTo(100_000L);
        assertThat(response.currency()).isEqualTo("KRW");
    }

    @ParameterizedTest(name = "{0} 권한은 모임 이름을 수정할 수 없다")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("모임장이 아닌 사용자는 모임 이름을 수정할 수 없다.")
    void updateRoom_throwsForbiddenExceptionWhenUserIsNotOwner(Authority authority) {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("기존 이름", 100_000L, Currency.KRW);
        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(Optional.of(connection));
        when(connection.getAuthority()).thenReturn(authority);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.updateRoom(
                        userId,
                        roomId,
                        new RoomUpdateRequest("수정된 이름")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OWNER_REQUIRED);
        assertThat(exception.getMessage()).isEqualTo("권한이 없습니다.");
        assertThat(room.getName()).isEqualTo("기존 이름");
    }

    @Test
    @DisplayName("존재하지 않는 모임 수정 시, 에러 발생")
    void updateRoom_throwsNotFoundExceptionWhenRoomDoesNotExist() {
        Long userId = 1L;
        Long roomId = 10L;
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.updateRoom(
                        userId,
                        roomId,
                        new RoomUpdateRequest("수정된 이름")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("해당 모임이 존재하지 않습니다.");
    }

    @Test
    @DisplayName("모임 연결 정보가 없으면 모임 수정 시, 에러 발생")
    void updateRoom_throwsNotFoundExceptionWhenUserHasNoRoomConnection() {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("기존 이름", 100_000L, Currency.KRW);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.updateRoom(
                        userId,
                        roomId,
                        new RoomUpdateRequest("수정된 이름")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("멤버를 찾을 수 없습니다.");
        assertThat(room.getName()).isEqualTo("기존 이름");
    }

    @Test
    @DisplayName("모임장은 모임을 삭제할 수 있다.")
    void deleteRoom_deletesRoomWhenUserIsOwner() {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("사진동아리", 100_000L, Currency.KRW);
        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(Optional.of(connection));
        when(connection.getAuthority()).thenReturn(Authority.OWNER);

        roomService.deleteRoom(userId, roomId);

        verify(roomRepository).delete(room);
    }

    @Test
    @DisplayName("존재하지 않는 모임 삭제 시, 에러 발생")
    void deleteRoom_throwsNotFoundExceptionWhenRoomDoesNotExist() {
        Long userId = 1L;
        Long roomId = 10L;
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.deleteRoom(userId, roomId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
        verify(roomRepository, never()).delete(any(Room.class));
    }

    @Test
    @DisplayName("모임 연결 정보가 없으면 모임 삭제 시, 에러 발생")
    void deleteRoom_throwsNotFoundExceptionWhenUserHasNoRoomConnection() {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("사진동아리", 100_000L, Currency.KRW);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.deleteRoom(userId, roomId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        verify(roomRepository, never()).delete(any(Room.class));
    }

    @ParameterizedTest(name = "{0} 권한은 모임을 삭제할 수 없다")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("모임장이 아닌 사용자는 모임을 삭제할 수 없다.")
    void deleteRoom_throwsForbiddenExceptionWhenUserIsNotOwner(Authority authority) {
        Long userId = 1L;
        Long roomId = 10L;
        Room room = Room.create("사진동아리", 100_000L, Currency.KRW);
        UserRoomConnection connection = mock(UserRoomConnection.class);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId))
                .thenReturn(Optional.of(connection));
        when(connection.getAuthority()).thenReturn(authority);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.deleteRoom(userId, roomId)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OWNER_REQUIRED);
        verify(roomRepository, never()).delete(any(Room.class));
    }

    @ParameterizedTest(name = "{0} 권한을 부여한다")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("모임장은 MEMBER를 OPERATOR로 지정하거나 OPERATOR를 MEMBER로 해제할 수 있다.")
    void changeAuthority_changesTargetAuthority(Authority requestedAuthority) {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class);
        Authority previousAuthority = requestedAuthority == Authority.OPERATOR
                ? Authority.MEMBER : Authority.OPERATOR;

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.of(actorConnection));
        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId))
                .thenReturn(Optional.of(targetConnection));
        when(targetConnection.getAuthority()).thenReturn(previousAuthority);

        roomService.changeAuthority(actorId, roomId, targetUserId,
                new AuthorityChangeRequest(requestedAuthority));

        verify(targetConnection).changeAuthority(requestedAuthority);
        verify(actorConnection, never()).changeAuthority(any(Authority.class));
    }

    @ParameterizedTest(name = "{0} 권한은 다른 멤버의 권한을 변경할 수 없다")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("모임장이 아닌 사용자의 권한 변경 요청은 거부한다.")
    void changeAuthority_rejectsNonOwner(Authority authority) {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.of(actorConnection));
        when(actorConnection.getAuthority()).thenReturn(authority);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.changeAuthority(actorId, roomId, targetUserId,
                        new AuthorityChangeRequest(Authority.OPERATOR)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OWNER_REQUIRED);
        verify(actorConnection, never()).changeAuthority(any(Authority.class));
        verify(userRoomConnectionRepository, never()).findByUser_IdAndRoom_Id(targetUserId, roomId);
    }

    @Test
    @DisplayName("모임에 참여하지 않은 사용자의 권한 변경 요청은 거부한다.")
    void changeAuthority_rejectsActorWithoutRoomConnection() {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.changeAuthority(actorId, roomId, targetUserId,
                        new AuthorityChangeRequest(Authority.OPERATOR)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        verify(userRoomConnectionRepository, never()).findByUser_IdAndRoom_Id(targetUserId, roomId);
    }

    @Test
    @DisplayName("권한 변경 대상이 모임에 참여하지 않았으면 거부한다.")
    void changeAuthority_rejectsTargetWithoutRoomConnection() {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.of(actorConnection));
        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.changeAuthority(actorId, roomId, targetUserId,
                        new AuthorityChangeRequest(Authority.OPERATOR)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        verify(actorConnection, never()).changeAuthority(any(Authority.class));
    }

    @Test
    @DisplayName("일반 권한 변경으로 기존 OWNER의 권한을 변경할 수 없다.")
    void changeAuthority_rejectsOwnerTarget() {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.of(actorConnection));
        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId))
                .thenReturn(Optional.of(targetConnection));
        when(targetConnection.getAuthority()).thenReturn(Authority.OWNER);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.changeAuthority(actorId, roomId, targetUserId,
                        new AuthorityChangeRequest(Authority.MEMBER)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OWNER_REQUIRED);
        verify(actorConnection, never()).changeAuthority(any(Authority.class));
        verify(targetConnection, never()).changeAuthority(any(Authority.class));
    }

    @ParameterizedTest(name = "{0}에게 OWNER 부여 거부")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("일반 권한 변경으로 OWNER를 부여할 수 없다.")
    void changeAuthority_rejectsGrantingOwner(Authority targetAuthority) {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.of(actorConnection));
        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(targetUserId, roomId))
                .thenReturn(Optional.of(targetConnection));
        when(targetConnection.getAuthority()).thenReturn(targetAuthority);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.changeAuthority(actorId, roomId, targetUserId,
                        new AuthorityChangeRequest(Authority.OWNER)));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_AUTHORITY);
        verify(actorConnection, never()).changeAuthority(any(Authority.class));
        verify(targetConnection, never()).changeAuthority(any(Authority.class));
    }

    @ParameterizedTest(name = "{0}에게 모임장 위임")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("모임장은 다른 모임원에게 모임장 권한을 위임할 수 있다.")
    void delegateOwner_changesOwnerToMemberAndTargetToOwner(Authority targetAuthority) {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;

        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                actorId, roomId
        )).thenReturn(Optional.of(actorConnection));

        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                targetUserId, roomId
        )).thenReturn(Optional.of(targetConnection));

        when(targetConnection.getAuthority()).thenReturn(targetAuthority);

        roomService.delegateOwner(
                actorId,
                roomId,
                targetUserId
        );

        verify(actorConnection).changeAuthority(Authority.MEMBER);
        verify(targetConnection).changeAuthority(Authority.OWNER);
    }

    @ParameterizedTest(name = "{0} 권한은 모임장을 위임할 수 없다")
    @EnumSource(value = Authority.class, names = {"MEMBER", "OPERATOR"})
    @DisplayName("모임장이 아닌 사용자가 모임장 권한을 위임하려 하면, 에러 발생")
    void delegateOwner_throwsForbiddenExceptionWhenUserIsNotOwner(Authority authority) {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;

        UserRoomConnection actorConnection = mock(UserRoomConnection.class);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                actorId, roomId
        )).thenReturn(Optional.of(actorConnection));

        when(actorConnection.getAuthority()).thenReturn(authority);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.delegateOwner(
                        actorId,
                        roomId,
                        targetUserId
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(
                ErrorCode.OWNER_REQUIRED
        );

        verify(actorConnection, never()).changeAuthority(any(Authority.class));
        verify(userRoomConnectionRepository, never())
                .findByUser_IdAndRoom_Id(targetUserId, roomId);
    }

    @Test
    @DisplayName("모임에 참여하지 않은 사용자의 모임장 위임 요청은 거부한다.")
    void delegateOwner_rejectsActorWithoutRoomConnection() {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.delegateOwner(actorId, roomId, targetUserId));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        verify(userRoomConnectionRepository, never()).findByUser_IdAndRoom_Id(targetUserId, roomId);
    }

    @Test
    @DisplayName("모임장은 자기 자신에게 모임장 권한을 위임할 수 없다.")
    void delegateOwner_rejectsSelfDelegation() {
        Long actorId = 1L;
        Long roomId = 10L;
        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(actorId, roomId))
                .thenReturn(Optional.of(actorConnection));
        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> roomService.delegateOwner(actorId, roomId, actorId));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OWNER_REQUIRED);
        verify(actorConnection, never()).changeAuthority(any(Authority.class));
    }

    @Test
    @DisplayName("존재하지 않는 멤버에게 모임장 권한을 위임하려 하면, 에러 발생")
    void delegateOwner_throwsNotFoundExceptionWhenTargetMemberDoesNotExist() {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;

        UserRoomConnection actorConnection = mock(UserRoomConnection.class);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                actorId, roomId
        )).thenReturn(Optional.of(actorConnection));

        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                targetUserId, roomId
        )).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.delegateOwner(
                        actorId,
                        roomId,
                        targetUserId
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(
                ErrorCode.MEMBER_NOT_FOUND
        );

        verify(actorConnection, never())
                .changeAuthority(any(Authority.class));
    }

    @Test
    @DisplayName("이미 모임장인 멤버에게 모임장 권한을 위임하려 하면, 에러 발생")
    void delegateOwner_throwsExceptionWhenTargetIsAlreadyOwner() {
        Long actorId = 1L;
        Long roomId = 10L;
        Long targetUserId = 2L;

        UserRoomConnection actorConnection = mock(UserRoomConnection.class);
        UserRoomConnection targetConnection = mock(UserRoomConnection.class);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                actorId, roomId
        )).thenReturn(Optional.of(actorConnection));

        when(actorConnection.getAuthority()).thenReturn(Authority.OWNER);

        when(userRoomConnectionRepository.findByUser_IdAndRoom_Id(
                targetUserId, roomId
        )).thenReturn(Optional.of(targetConnection));

        when(targetConnection.getAuthority()).thenReturn(Authority.OWNER);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> roomService.delegateOwner(
                        actorId,
                        roomId,
                        targetUserId
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(
                ErrorCode.OWNER_REQUIRED
        );

        verify(actorConnection, never())
                .changeAuthority(any(Authority.class));
        verify(targetConnection, never())
                .changeAuthority(any(Authority.class));
    }
}
