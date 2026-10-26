package com.budzet.domain.invite.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InviteService {

    private final InviteRepository inviteRepository;
    private final RoomRepository roomRepository;
    private final UserRoomConnectionRepository userRoomConnectionRepository;
    private final UserRepository userRepository;

    @Transactional
    public InviteResponse createInvite(Long roomId, Long userId) {

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        UserRoomConnection connection =
                userRoomConnectionRepository
                        .findByUser_IdAndRoom_Id(userId, roomId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_JOINED_ROOM));

        if (connection.getAuthority() != Authority.OWNER) {
            throw new BusinessException(ErrorCode.OWNER_REQUIRED);
        }

        String code = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 10);

        LocalDateTime expireAt = LocalDateTime.now()
                .plusHours(1);

        Invite invite = new Invite(
                code,
                room,
                expireAt
        );

        inviteRepository.save(invite);

        return new InviteResponse(
                invite.getCode(),
                invite.getExpireAt()
        );
    }

    @Transactional(readOnly = true)
    public InviteResponse verifyInvite(String token) {

        Invite invite = inviteRepository.findById(token)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.NOT_FOUND)
                );

        if (!invite.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        return new InviteResponse(
                invite.getCode(),
                invite.getExpireAt()
        );
    }

    @Transactional(readOnly = true)
    public List<InviteResponse> getInvites(Long roomId, Long actorId) {

        UserRoomConnection connection = userRoomConnectionRepository
                .findByUser_IdAndRoom_Id(actorId, roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_JOINED_ROOM));
        if (connection.getAuthority() != Authority.OWNER) {
            throw new BusinessException(ErrorCode.OWNER_REQUIRED);
        }

        List<Invite> invites = inviteRepository.findAllByRoom_Id(roomId);

        return invites.stream()
                .map(invite -> new InviteResponse(
                        invite.getCode(),
                        invite.getExpireAt()
                ))
                .toList();
    }

    @Retryable(
            retryFor = { PessimisticLockingFailureException.class,
                    jakarta.persistence.LockTimeoutException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    @Transactional
    public InviteJoinResponse joinRoom(String code, Long userId) {
        // 초대 코드 조회
        Invite invite = inviteRepository.findById(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_NOT_FOUND));

        // 대상 Room을 잠근 상태로 조회
        Long roomId = invite.getRoom().getId();
        Room room = roomRepository.findByWithLock(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        // 잠금 획득 후 만료일이 유효한지 확인
        if (!invite.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVITE_EXPIRED);
        }

        // 이미 참여한 상태인지 확인
        if (userRoomConnectionRepository.findByUser_IdAndRoom_Id(userId, roomId).isPresent()) {
            throw new BusinessException(ErrorCode.USER_ALREADY_JOINED_ROOM);
        }

        // 멤버 참여 처리하기(UserRoomConnection 생성)
        User user = userRepository.getReferenceById(userId);
        UserRoomConnection connection = UserRoomConnection.createMember(user, room);
        UserRoomConnection savedConnection = userRoomConnectionRepository.save(connection);

        return InviteJoinResponse.from(savedConnection);
    }
}
