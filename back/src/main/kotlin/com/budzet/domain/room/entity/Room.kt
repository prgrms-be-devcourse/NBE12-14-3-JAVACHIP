package com.budzet.domain.room.entity

import com.budzet.domain.budget.entity.BudgetChange
import com.budzet.domain.budget.entity.BudgetRequest
import com.budzet.domain.budget.entity.BudgetType
import com.budzet.domain.invite.entity.Invite
import com.budzet.global.exception.BusinessException
import com.budzet.global.exception.ErrorCode
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@EntityListeners(AuditingEntityListener::class)
open class Room private constructor(name: String, totalBudget: Long, currency: Currency) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null
        protected set

    @field:Column(length = 20)
    open var name: String = name
        protected set

    open var totalBudget: Long? = totalBudget
        protected set

    open var availableBudget: Long? = totalBudget
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 3, nullable = false)
    open var currency: Currency = currency
        protected set

    @field:CreatedDate
    open var createdAt: LocalDateTime? = null
        protected set

    @field:OneToMany(mappedBy = "room", cascade = [CascadeType.REMOVE])
    open var userConnections: MutableList<UserRoomConnection> = mutableListOf()
        protected set

    @field:OneToMany(mappedBy = "room", cascade = [CascadeType.REMOVE])
    open var budgetRequests: MutableList<BudgetRequest> = mutableListOf()
        protected set

    @field:OneToMany(mappedBy = "room", cascade = [CascadeType.REMOVE])
    open var budgetChanges: MutableList<BudgetChange> = mutableListOf()
        protected set

    @field:OneToMany(mappedBy = "room", cascade = [CascadeType.REMOVE])
    open var invites: MutableList<Invite> = mutableListOf()
        protected set

    fun changeName(name: String) {
        this.name = name
    }

    fun addBudgetChange(budgetChange: BudgetChange) {
        budgetChanges.add(budgetChange)
    }

    fun updateTotalBudget(changedBudget: Long?, type: BudgetType) {
        if (changedBudget == null || changedBudget <= 0) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "입력된 금액이 올바르지 않습니다.")
        }
        if (type == BudgetType.INCREASE) {
            totalBudget = requireNotNull(totalBudget) + changedBudget
            availableBudget = requireNotNull(availableBudget) + changedBudget
        } else if (type == BudgetType.DECREASE) {
            if (requireNotNull(availableBudget) < changedBudget) {
                throw BusinessException(ErrorCode.BUDGET_EXCEEDED)
            }
            totalBudget = requireNotNull(totalBudget) - changedBudget
            availableBudget = requireNotNull(availableBudget) - changedBudget
        }
    }

    fun settleBudget(requestedAmount: Long, changedBudget: Long?) {
        if (changedBudget == null || changedBudget <= 0) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "입력된 금액이 올바르지 않습니다.")
        }
        if (requireNotNull(totalBudget) < changedBudget) {
            throw BusinessException(ErrorCode.BUDGET_EXCEEDED, "실제예산을 초과하여 정산할 수 없습니다..")
        }
        // 승인 금액 이상으로 정산 처리할 경우 예외처리
        if (changedBudget > requestedAmount) {
            throw BusinessException(ErrorCode.SETTLEMENT_AMOUNT_EXCEEDS_APPROVED)
        }
        // 잔액 = 신청 승인된 금액 - 실제 사용 금액
        val balanceBudget = requestedAmount - changedBudget
        totalBudget = requireNotNull(totalBudget) - changedBudget
        availableBudget = requireNotNull(availableBudget) + balanceBudget
    }

    fun updateTotalBudget(updateChanged: Long?) {
        if (updateChanged == null) {
            throw BusinessException(ErrorCode.BAD_REQUEST, "입력된 금액이 올바르지 않습니다.")
        }
        // 정산 수정 차액을 반영한 가용예산이 음수가 될 수 없다.
        if (requireNotNull(availableBudget) + updateChanged < 0) {
            throw BusinessException(ErrorCode.BUDGET_EXCEEDED, "가용예산을 초과하여 정산할 수 없습니다..")
        }
        // 승인·정산 시 이미 반영된 금액의 수정 차액을 두 예산에 함께 반영한다.
        totalBudget = requireNotNull(totalBudget) + updateChanged
        availableBudget = requireNotNull(availableBudget) + updateChanged
    }

    fun updateAvailableBudget(changeBudget: Long) {
        availableBudget = requireNotNull(availableBudget) + changeBudget
    }

    companion object {
        @JvmStatic
        fun create(name: String, totalBudget: Long, currency: Currency): Room =
            Room(name, totalBudget, currency)
    }
}
