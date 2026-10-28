package com.budzet.domain.budget.service;

import com.budzet.domain.budget.dto.*;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetChangeServiceTest {

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


    // =========================
    // 정산 등록
    // =========================

    @Test
    @DisplayName("정산 등록 성공 - 예산 차감 및 변경 이력 저장")
    void createBudgetChange_success() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(
                        8000L,
                        "실제 장비 구매 영수증 첨부"
                );

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        BudgetRequest budgetRequest =
                mock(BudgetRequest.class);
        Room room = mock(Room.class);

        when(room.getId()).thenReturn(roomId);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(user.getName()).thenReturn("홍길동");

        when(budgetRequestRepository.findById(requestId))
                .thenReturn(Optional.of(budgetRequest));

        when(budgetRequest.getId()).thenReturn(requestId);
        when(budgetRequest.getRoom()).thenReturn(room);
        when(budgetRequest.getUser()).thenReturn(user);
        when(budgetRequest.getStatus()).thenReturn(BudgetRequestType.valueOf("APPROVE"));
        when(budgetRequest.getRequestedAmount()).thenReturn(10000L);

        when(budgetService.findByRoomIdWithLock(roomId))
                .thenReturn(room);

        when(budgetChangeRepository.save(any(BudgetChange.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        BudgetChangeCreateResponse response =
                budgetChangeService.createBudgetChange(
                        roomId, userId, requestId, request
                );

        // then
        assertNotNull(response);
        assertEquals(requestId, response.getRequestId());
        assertEquals(userId, response.getUserId());
        assertEquals(10000L, response.getChangeBudget());
        assertEquals(8000L, response.getChangedBudget());
        assertEquals(BudgetType.SETTLEMENT, response.getBudgetType());
        assertEquals("홍길동", response.getUserName());
        assertEquals("실제 장비 구매 영수증 첨부", response.getReason());

        // 예산 정산 메서드 호출 검증
        verify(room).settleBudget(10000L, 8000L);

        // 신청 상태 변경 메서드 호출 검증
        verify(budgetRequest).changeToSettlement();

        // 모임과 정산 내역의 연결 메서드 호출 검증
        verify(room).addBudgetChange(any(BudgetChange.class));

        // 정산 내역 저장 메서드 호출 검증
        verify(budgetChangeRepository).save(any(BudgetChange.class));
    }

    @Test
    @DisplayName("정산 등록 실패 - 신청서가 존재하지 않을 때")
    void createBudgetChange_requestNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(8000L, "정산 사유");

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetRequestRepository.findById(requestId))
                .thenReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        roomId, userId, requestId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_REQUEST_NOT_FOUND,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 예산신청이 존재하지 않습니다.",
                exception.getMessage()
        );

        verify(budgetService)
                .findByRoomIdWithLock(anyLong());

        verify(budgetChangeRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 다른 모임의 신청서를 전달했을 때")
    void createBudgetChange_requestFromDifferentRoom() {

        // given
        Long roomId = 1L;
        Long otherRoomId = 2L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(8000L, "정산 사유");

        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        BudgetRequest budgetRequest =
                mock(BudgetRequest.class);
        Room otherRoom = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetRequestRepository.findById(requestId))
                .thenReturn(Optional.of(budgetRequest));

        when(budgetRequest.getRoom()).thenReturn(otherRoom);
        when(otherRoom.getId()).thenReturn(otherRoomId);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        roomId, userId, requestId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.BAD_REQUEST,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 모임의 신청건이 아닙니다.",
                exception.getMessage()
        );

        // 모임 잠금은 먼저 획득하지만, 모임이 일치하지 않으면 저장하지 않는다.
        verify(budgetService)
                .findByRoomIdWithLock(anyLong());

        verify(budgetChangeRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 신청서의 작성자가 아닐 때")
    void createBudgetChange_forbiddenRequestWriter() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long otherUserId = 200L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(8000L, "정산 사유");

        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        BudgetRequest budgetRequest =
                mock(BudgetRequest.class);
        User writerUser = mock(User.class);
        Room room = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetRequestRepository.findById(requestId))
                .thenReturn(Optional.of(budgetRequest));

        when(budgetRequest.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        when(budgetRequest.getUser()).thenReturn(writerUser);
        when(writerUser.getId()).thenReturn(otherUserId);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        roomId, userId, requestId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.FORBIDDEN_REQUEST_WRITER,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 신청의 신청자만 정산할 수 있습니다.",
                exception.getMessage()
        );

        verify(budgetService)
                .findByRoomIdWithLock(anyLong());

        verify(budgetChangeRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 승인된 신청이 아닐 때")
    void createBudgetChange_notApproved() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(8000L, "정산 사유");

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        BudgetRequest budgetRequest =
                mock(BudgetRequest.class);
        Room room = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetRequestRepository.findById(requestId))
                .thenReturn(Optional.of(budgetRequest));

        when(budgetRequest.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        when(budgetRequest.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(budgetRequest.getStatus()).thenReturn(BudgetRequestType.valueOf("REQUEST"));

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        roomId, userId, requestId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_REQUEST_NOT_APPROVED,
                exception.getErrorCode()
        );
        assertEquals(
                "승인된 예산 신청 건이 아닙니다.",
                exception.getMessage()
        );

        verify(budgetService)
                .findByRoomIdWithLock(anyLong());

        verify(budgetChangeRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("정산 등록 실패 - 모임 멤버가 아닐 때")
    void createBudgetChange_userNotJoinedRoom() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long requestId = 10L;

        BudgetChangeCreateRequest request =
                new BudgetChangeCreateRequest(8000L, "정산 사유");

        when(budgetService.validateRoomMember(roomId, userId))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.USER_NOT_JOINED_ROOM
                        )
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.createBudgetChange(
                        roomId, userId, requestId, request
                )
        );

        // then
        assertEquals(
                ErrorCode.USER_NOT_JOINED_ROOM,
                exception.getErrorCode()
        );
        assertEquals(
                "사용자가 방에 속해있지 않습니다.",
                exception.getMessage()
        );

        verify(budgetRequestRepository, never())
                .findById(any());

        verify(budgetChangeRepository, never())
                .save(any());
    }


    // =========================
    // 정산 내역 목록 조회
    // =========================

    @Test
    @DisplayName("정산 내역 목록 조회 성공 - 정산 유형 조건으로 조회")
    void budgetChangeList_success() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        BudgetChange settlementChange1 =
                mock(BudgetChange.class);
        BudgetChange settlementChange2 =
                mock(BudgetChange.class);

        List<BudgetChange> settlementChanges =
                List.of(settlementChange1, settlementChange2);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetChangeRepository
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                        roomId, BudgetType.SETTLEMENT
                ))
                .thenReturn(settlementChanges);

        // when
        BudgetChangeListResponse response =
                budgetChangeService.budgetChangeList(roomId, userId);

        // then
        assertNotNull(response);
        assertEquals(2, response.getChanges().size());

        verify(budgetService)
                .validateRoomMember(roomId, userId);

        verify(budgetChangeRepository)
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                        eq(roomId),
                        eq(BudgetType.SETTLEMENT)
                );
    }

    @Test
    @DisplayName("정산 내역 목록 조회 실패 - 모임 멤버가 아닐 때")
    void budgetChangeList_userNotJoinedRoom() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        when(budgetService.validateRoomMember(roomId, userId))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.USER_NOT_JOINED_ROOM
                        )
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeList(
                        roomId, userId
                )
        );

        // then
        assertEquals(
                ErrorCode.USER_NOT_JOINED_ROOM,
                exception.getErrorCode()
        );
        assertEquals(
                "사용자가 방에 속해있지 않습니다.",
                exception.getMessage()
        );

        verify(budgetChangeRepository, never())
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                        anyLong(), any()
                );
    }

    @Test
    @DisplayName("정산 내역 목록 조회 성공 - 내역이 없을 때 빈 목록 반환")
    void budgetChangeList_emptyList() {

        // given
        Long roomId = 1L;
        Long userId = 100L;

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetChangeRepository
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                        roomId, BudgetType.SETTLEMENT
                ))
                .thenReturn(Collections.emptyList());

        // when
        BudgetChangeListResponse response =
                budgetChangeService.budgetChangeList(roomId, userId);

        // then
        assertNotNull(response);
        assertTrue(response.getChanges().isEmpty());

        verify(budgetService)
                .validateRoomMember(roomId, userId);

        verify(budgetChangeRepository)
                .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                        eq(roomId),
                        eq(BudgetType.SETTLEMENT)
                );
    }


    // =========================
    // 정산 내역 상세 조회
    // =========================

    @Test
    @DisplayName("정산 내역 상세 조회 성공")
    void budgetChangeDetail_success() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 50L;
        Long requestId = 30L;

        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        BudgetChange budgetChange =
                mock(BudgetChange.class);
        Room room = mock(Room.class);
        User user = mock(User.class);
        BudgetRequest request =
                mock(BudgetRequest.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.of(budgetChange));

        when(budgetChange.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        when(budgetChange.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(userId);

        when(budgetChange.getRequest()).thenReturn(request);
        when(request.getId()).thenReturn(requestId);

        // when
        BudgetChangeDetailResponse response =
                budgetChangeService.budgetChangeDetail(
                        roomId, userId, changeId
                );

        // then
        assertNotNull(response);

        verify(budgetService)
                .validateRoomMember(roomId, userId);

        verify(budgetChangeRepository)
                .findById(changeId);
    }

    @Test
    @DisplayName("정산 내역 상세 조회 실패 - 해당 내역이 존재하지 않을 때")
    void budgetChangeDetail_notFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 999L;

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeDetail(
                        roomId, userId, changeId
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_CHANGE_NOT_FOUND,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 정산내역이 존재하지 않습니다.",
                exception.getMessage()
        );

        verify(budgetService)
                .validateRoomMember(roomId, userId);

        verify(budgetChangeRepository)
                .findById(changeId);
    }

    @Test
    @DisplayName("정산 내역 상세 조회 실패 - 모임 멤버가 아닐 때")
    void budgetChangeDetail_userNotJoinedRoom() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 50L;

        when(budgetService.validateRoomMember(roomId, userId))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.USER_NOT_JOINED_ROOM
                        )
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeDetail(
                        roomId, userId, changeId
                )
        );

        // then
        assertEquals(
                ErrorCode.USER_NOT_JOINED_ROOM,
                exception.getErrorCode()
        );
        assertEquals(
                "사용자가 방에 속해있지 않습니다.",
                exception.getMessage()
        );

        verify(budgetChangeRepository, never())
                .findById(anyLong());
    }

    @Test
    @DisplayName("정산 내역 상세 조회 실패 - 다른 모임의 정산 내역일 때")
    void budgetChangeDetail_invalidRoomId() {

        // given
        Long roomId = 1L;
        Long otherRoomId = 2L;
        Long userId = 100L;
        Long changeId = 50L;

        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        BudgetChange budgetChange =
                mock(BudgetChange.class);
        Room otherRoom = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.of(budgetChange));

        when(budgetChange.getRoom()).thenReturn(otherRoom);
        when(otherRoom.getId()).thenReturn(otherRoomId);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.budgetChangeDetail(
                        roomId, userId, changeId
                )
        );

        // then
        assertEquals(
                ErrorCode.BAD_REQUEST,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 모임의 정산건이 아닙니다.",
                exception.getMessage()
        );

        verify(budgetChangeRepository)
                .findById(changeId);
    }


    // =========================
    // 정산 수정
    // =========================

    @Test
    @DisplayName("정산 수정 성공 - 수정 금액과 정산 정보가 반영됨")
    void updateBudgetChange_success() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long targetUserId = 200L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정된 사유"
                );

        User previousUser = mock(User.class);
        User user = mock(User.class);

        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);

        Room room = mock(Room.class);
        BudgetRequest budgetRequest =
                mock(BudgetRequest.class);

        // 실제 BudgetChange 엔티티를 사용해 필드 변경과 응답값 검증
        BudgetChange budgetChange = new BudgetChange(
                room,
                previousUser,
                "기존 사용자",
                BudgetType.SETTLEMENT,
                "기존 사유",
                5000L,
                3000L
        );

        // 기존 DB 조회 결과에 해당하는 ID 설정
        ReflectionTestUtils.setField(
                budgetChange, "id", changeId
        );

        // 연관된 신청 내역이 존재하는 경우를 설정
        budgetChange.addBudgetRequest(budgetRequest);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(
                "홍길동", "test@test.com"
        )).thenReturn(user);

        when(user.getId()).thenReturn(targetUserId);
        when(user.getEmail()).thenReturn("test@test.com");

        when(budgetService.validateRoomMember(roomId, targetUserId))
                .thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.of(budgetChange));

        when(room.getId()).thenReturn(roomId);

        when(budgetService.findByRoomIdWithLock(roomId))
                .thenReturn(room);

        // 기존 신청 금액 5,000원에서 6,000원으로 증액
        when(room.getAvailableBudget()).thenReturn(2000L);

        // when
        BudgetChangeUpdateResponse response =
                budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                );

        // then
        assertNotNull(response);

        // 락 조회 호출 검증
        verify(budgetService)
                .findByRoomIdWithLock(roomId);

        // 정산 금액 차액: 기존 3,000원 - 수정 후 4,000원 = -1,000원
        verify(room)
                .updateTotalBudget(-1000L);

        // BudgetChange 엔티티의 실제 필드 변경 검증
        assertEquals(6000L, budgetChange.getChangeBudget());
        assertEquals(4000L, budgetChange.getChangedBudget());
        assertEquals("홍길동", budgetChange.getUserName());
        assertEquals("수정된 사유", budgetChange.getReason());
        assertSame(user, budgetChange.getUser());

        // 수정 결과 DTO 검증
        assertEquals(changeId, response.getId());
        assertEquals(6000L, response.getChangeBudget());
        assertEquals(4000L, response.getChangedBudget());
        assertEquals("홍길동", response.getUserName());
        assertEquals("test@test.com", response.getUserEmail());
        assertEquals("수정된 사유", response.getReason());

        // 연결된 신청 내역의 수정 메서드 호출 검증
        verify(budgetRequest)
                .updateRequest(budgetChange, user);

        verify(budgetService)
                .validateRoomMember(roomId, targetUserId);
    }

    @Test
    @DisplayName("정산 수정 실패 - OWNER 권한이 아닐 때")
    void updateBudgetChange_forbiddenNotOwner() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정 사유"
                );

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.MEMBER);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.FORBIDDEN_ACCESS,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 요청에 대한 권한이 없습니다.",
                exception.getMessage()
        );

        verify(userRepository, never())
                .findByNameAndEmail(anyString(), anyString());

        verify(budgetChangeRepository, never())
                .findById(anyLong());

        verify(budgetService, never())
                .findByRoomIdWithLock(anyLong());
    }

    @Test
    @DisplayName("정산 수정 실패 - 이름과 이메일에 해당하는 사용자가 없을 때")
    void updateBudgetChange_userNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "없는유저",
                        "wrong@test.com",
                        "수정 사유"
                );

        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(
                "없는유저", "wrong@test.com"
        )).thenReturn(null);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.BAD_REQUEST,
                exception.getErrorCode()
        );
        assertEquals(
                "유저 이름과 이메일이 일치하지 않습니다.",
                exception.getMessage()
        );

        verify(budgetService, never())
                .findByRoomIdWithLock(anyLong());

        verify(budgetChangeRepository, never())
                .findById(anyLong());
    }

    @Test
    @DisplayName("정산 수정 실패 - 대상 사용자가 모임 멤버가 아닐 때")
    void updateBudgetChange_targetUserNotJoinedRoom() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long targetUserId = 200L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정 사유"
                );

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(
                "홍길동", "test@test.com"
        )).thenReturn(user);

        when(user.getId()).thenReturn(targetUserId);

        when(budgetService.validateRoomMember(roomId, targetUserId))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.USER_NOT_JOINED_ROOM
                        )
                );

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.USER_NOT_JOINED_ROOM,
                exception.getErrorCode()
        );
        assertEquals(
                "사용자가 방에 속해있지 않습니다.",
                exception.getMessage()
        );

        // 대상 사용자의 멤버십 검증 실패 후에는 정산 조회를 진행하지 않아야 함
        verify(budgetChangeRepository, never())
                .findById(anyLong());

        verify(budgetService, never())
                .findByRoomIdWithLock(anyLong());
    }

    @Test
    @DisplayName("정산 수정 실패 - 수정할 정산 내역이 존재하지 않을 때")
    void updateBudgetChange_budgetChangeNotFound() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long targetUserId = 200L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정 사유"
                );

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(anyString(), anyString()))
                .thenReturn(user);

        when(user.getId()).thenReturn(targetUserId);

        when(budgetService.validateRoomMember(roomId, targetUserId))
                .thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.empty());

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.BUDGET_CHANGE_NOT_FOUND,
                exception.getErrorCode()
        );
        assertEquals(
                "해당 정산내역이 존재하지 않습니다.",
                exception.getMessage()
        );

        verify(budgetService, never())
                .findByRoomIdWithLock(anyLong());
    }

    @Test
    @DisplayName("정산 수정 실패 - 정산 유형이 아닌 내역을 수정할 때")
    void updateBudgetChange_notSettlementType() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long targetUserId = 200L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정 사유"
                );

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);
        BudgetChange budgetChange =
                mock(BudgetChange.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(
                "홍길동", "test@test.com"
        )).thenReturn(user);

        when(user.getId()).thenReturn(targetUserId);

        when(budgetService.validateRoomMember(roomId, targetUserId))
                .thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.of(budgetChange));

        when(budgetChange.getType())
                .thenReturn(BudgetType.INCREASE);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.BAD_REQUEST,
                exception.getErrorCode()
        );
        assertEquals(
                "정산(SETTLEMENT) 타입의 내역만 수정할 수 있습니다.",
                exception.getMessage()
        );

        // 정산 유형이 아니라면 예산 락 획득과 수정이 진행되지 않아야 함
        verify(budgetService, never())
                .findByRoomIdWithLock(anyLong());

        verify(budgetChange, never())
                .updateChange(any(), any());
    }

    @Test
    @DisplayName("정산 수정 실패 - 유효하지 않은 모임의 정산 내역인 경우")
    void updateBudgetChange_invalidRoomId() {

        // given
        Long roomId = 1L;
        Long otherRoomId = 2L;
        Long userId = 100L;
        Long targetUserId = 200L;
        Long changeId = 10L;

        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        6000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정 사유"
                );

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);
        BudgetChange budgetChange =
                mock(BudgetChange.class);
        Room otherRoom = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(anyString(), anyString()))
                .thenReturn(user);

        when(user.getId()).thenReturn(targetUserId);

        when(budgetService.validateRoomMember(roomId, targetUserId))
                .thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.of(budgetChange));

        when(budgetChange.getType())
                .thenReturn(BudgetType.SETTLEMENT);

        when(budgetChange.getRoom()).thenReturn(otherRoom);
        when(otherRoom.getId()).thenReturn(otherRoomId);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.BAD_REQUEST,
                exception.getErrorCode()
        );
        assertEquals(
                "유효한 정산내역이 아닙니다.",
                exception.getMessage()
        );

        verify(budgetService, never())
                .findByRoomIdWithLock(anyLong());
    }

    @Test
    @DisplayName("정산 수정 실패 - 신청 금액 증액분이 가용예산을 초과할 때")
    void updateBudgetChange_requestAmountOverBudget() {

        // given
        Long roomId = 1L;
        Long userId = 100L;
        Long targetUserId = 200L;
        Long changeId = 10L;

        // 기존 신청 금액 5,000원에서 8,000원으로 변경
        // 증액분은 3,000원
        BudgetChangeUpdateRequest updateRequest =
                new BudgetChangeUpdateRequest(
                        8000L,
                        4000L,
                        "홍길동",
                        "test@test.com",
                        "수정 사유"
                );

        User user = mock(User.class);
        UserRoomConnection connection =
                mock(UserRoomConnection.class);
        UserRoomConnection targetConnection =
                mock(UserRoomConnection.class);
        BudgetChange budgetChange =
                mock(BudgetChange.class);
        Room room = mock(Room.class);

        when(budgetService.validateRoomMember(roomId, userId))
                .thenReturn(connection);

        when(connection.getAuthority())
                .thenReturn(Authority.OWNER);

        when(userRepository.findByNameAndEmail(anyString(), anyString()))
                .thenReturn(user);

        when(user.getId()).thenReturn(targetUserId);

        when(budgetService.validateRoomMember(roomId, targetUserId))
                .thenReturn(targetConnection);

        when(budgetChangeRepository.findById(changeId))
                .thenReturn(Optional.of(budgetChange));

        when(budgetChange.getType())
                .thenReturn(BudgetType.SETTLEMENT);

        when(budgetChange.getRoom()).thenReturn(room);
        when(room.getId()).thenReturn(roomId);

        when(budgetService.findByRoomIdWithLock(roomId))
                .thenReturn(room);

        when(budgetChange.getChangeBudget()).thenReturn(5000L);

        // 가용예산 1,000원으로 증액분 3,000원을 감당할 수 없음
        when(room.getAvailableBudget()).thenReturn(1000L);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> budgetChangeService.updateBudgetChange(
                        roomId, userId, changeId, updateRequest
                )
        );

        // then
        assertEquals(
                ErrorCode.REQUEST_AMOUNT_OVER_BUDGET,
                exception.getErrorCode()
        );
        assertEquals(
                "신청예산이 가용예산을 초과했습니다.",
                exception.getMessage()
        );

        // 예산 초과 시 후속 예산 변경과 정산 수정은 실행되지 않아야 함
        verify(room, never())
                .updateTotalBudget(any());

        verify(budgetChange, never())
                .updateChange(any(), any());
    }
}