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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BudgetChangeService {

    private final BudgetChangeRepository budgetChangeRepository;
    private final BudgetRequestRepository budgetRequestRepository;
    private final BudgetService budgetService;
    private final UserRepository userRepository;

    @Retryable(
            retryFor = { PessimisticLockingFailureException.class,
                    jakarta.persistence.LockTimeoutException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    @Transactional
    public BudgetChangeCreateResponse createBudgetChange(
            Long roomId,
            Long userId,
            Long requestId,
            BudgetChangeCreateRequest createRequest){

        UserRoomConnection connection = budgetService.validateRoomMember(roomId, userId);
        BudgetRequest budgetRequest = budgetRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUDGET_REQUEST_NOT_FOUND));

        if(!budgetRequest.getRoom().getId().equals(roomId)){
            throw new BusinessException(ErrorCode.BAD_REQUEST,"해당 모임의 신청건이 아닙니다.");
        }

        //해당 신청의 신청자인지 확인
        if(!budgetRequest.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorCode.FORBIDDEN_REQUEST_WRITER);
        }

        //승인된 요청이 아닐 때 예외처리
        if(!BudgetRequestType.APPROVE.name().equals(budgetRequest.getStatus())){
            throw new BusinessException(ErrorCode.BUDGET_REQUEST_NOT_APPROVED);
        }

        Room room = budgetService.findByRoomIdWithLock(roomId);

        //변경 내역 생성
        BudgetType type = BudgetType.SETTLEMENT;

        BudgetChange budgetChange = new BudgetChange(
                room,
                connection.getUser(),
                connection.getUser().getName(),
                type,
                createRequest.reason(),
                budgetRequest.getRequestedAmount(),
                createRequest.changedBudget()
        );
        budgetChange.addBudgetRequest(budgetRequest);

        //신청 상태 변경 승인 -> 정산
        budgetRequest.changeToSettlement();

        //실제 예산 변경처리
        room.settleBudget(budgetChange.getChangeBudget(), budgetChange.getChangedBudget());

        //정산 후 저장
        room.addBudgetChange(budgetChange);
        budgetChangeRepository.save(budgetChange);

        return BudgetChangeCreateResponse.from(budgetChange);
    }

    @Transactional(readOnly = true)
    public BudgetChangeListResponse budgetChangeList(Long roomId, Long userId){

        budgetService.validateRoomMember(roomId,userId);

        List<BudgetChange> budgetChanges =
                budgetChangeRepository.findAllByRoomIdAndTypeOrderByCreatedAtDesc(roomId, BudgetType.SETTLEMENT);

        return BudgetChangeListResponse.from(budgetChanges);
    }

    @Transactional(readOnly = true)
    public BudgetChangeDetailResponse budgetChangeDetail(Long roomId, Long userId, Long changeId){

        budgetService.validateRoomMember(roomId, userId);

        BudgetChange budgetChange = budgetChangeRepository.findById(changeId)
                .orElseThrow(()-> new BusinessException(ErrorCode.BUDGET_CHANGE_NOT_FOUND));

        if(!budgetChange.getRoom().getId().equals(roomId)){
            throw new BusinessException(ErrorCode.BAD_REQUEST,"해당 모임의 정산건이 아닙니다.");
        }

        return BudgetChangeDetailResponse.from(budgetChange);
    }

    @Retryable(
            retryFor = { PessimisticLockingFailureException.class,
                    jakarta.persistence.LockTimeoutException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500)
    )
    @Transactional
    public BudgetChangeUpdateResponse updateBudgetChange(
            Long roomId, Long userId, Long changeId, BudgetChangeUpdateRequest updateRequest){

        //유저권한이 OWNER인지 확인
        UserRoomConnection connection = budgetService.validateRoomMember(roomId, userId);
        if(connection.getAuthority() != Authority.OWNER){
            throw new BusinessException(ErrorCode.FORBIDDEN_ACCESS);
        }

        //입력 받은 유저이름과 이메일로 유저를 찾아 가져옴
        User user = userRepository.findByNameAndEmail(updateRequest.userName(),updateRequest.userEmail())
                .orElseThrow(()-> new BusinessException(ErrorCode.BAD_REQUEST,"유저 이름과 이메일이 일치하지 않습니다."));

        //입력 받은 유저의 아이디가 모임에 존재하는지 확인
        budgetService.validateRoomMember(roomId, user.getId());

        BudgetChange budgetChange = budgetChangeRepository.findById(changeId)
                .orElseThrow(()-> new BusinessException(ErrorCode.BUDGET_CHANGE_NOT_FOUND));

        //수정하려는 정산건의 타입이 SETTLEMENT인지 확인
        if(!BudgetType.SETTLEMENT.equals(budgetChange.getType())){
            throw new BusinessException(ErrorCode.BAD_REQUEST,"정산(SETTLEMENT) 타입의 내역만 수정할 수 있습니다.");
        }

        if(!budgetChange.getRoom().getId().equals(roomId)){
            throw new BusinessException(ErrorCode.BAD_REQUEST,"유효한 정산내역이 아닙니다.");
        }

        Room room = budgetService.findByRoomIdWithLock(roomId);

        // 변경하려는 기존 승인된 신청금액이 현재 가용예산을 초과하면 안되기 때문에 예외 검사
        /* 예) 초기 방예산 5000설정       현재 실제예산 =1000 가용예산 = 1000
        *       정산이 한개만 존재할때 -> 신청금액 4000,  정산금액 4000
        * 이때 신청금액을 6000으로 변경시 애초에 초기 5000원에서 6000원 승인이 안되기 때문*/
        long resultAmount = updateRequest.changeBudget() - budgetChange.getChangeBudget();
        if (resultAmount > 0 && room.getAvailableBudget() < resultAmount) {
            throw new BusinessException(ErrorCode.REQUEST_AMOUNT_OVER_BUDGET);
        }


        //방 예산도 수정
        /* 수정 전 금액 - 수정 후 금액 계산 그다음 현재 실제, 가용예산예 더하기 처리
            기존 실제 예산 2000 일때
            정산 금액이 1000(수정전) - 800(수정후) = 200  -> 실제예산 + 200
            정산 금액이 1000(수정전) - 1200(수정후) = -200 -> 실제예산 +(-200) */

        //차액 계산
        Long resultChanged = budgetChange.getChangedBudget() - updateRequest.changedBudget();


        room.updateTotalBudget(resultChanged);


        //정산 내역 수정
        budgetChange.updateChange(updateRequest,user);

        //신청 내역이 존재하면 같이 수정
        if(budgetChange.getRequest() != null){
            budgetChange.getRequest().updateRequest(budgetChange,user);
        }

        return BudgetChangeUpdateResponse.from(budgetChange);
    }
}
