package com.budzet.domain.budget.entity

import com.budzet.domain.room.entity.Room
import com.budzet.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@EntityListeners(AuditingEntityListener::class)
open class BudgetChange @JvmOverloads constructor(
    room: Room,
    user: User,
    userName: String,
    type: BudgetType,
    reason: String?,
    changeBudget: Long,
    changedBudget: Long = changeBudget,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "room_id", nullable = false)
    open var room: Room = room
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "user_id", nullable = false)
    open var user: User = user
        protected set

    @field:Column(length = 50, nullable = false)
    open var userName: String = userName
        protected set

    @field:Column(nullable = false)
    @field:Enumerated(EnumType.STRING)
    open var type: BudgetType = type
        protected set

    @field:Column(length = 20)
    open var reason: String? = reason
        protected set

    @field:Column(nullable = false)
    open var changeBudget: Long = changeBudget
        protected set

    @field:Column(nullable = false)
    open var changedBudget: Long = changedBudget
        protected set

    @field:CreatedDate
    open var createdAt: LocalDateTime? = null
        protected set

    @field:OneToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "request_id", unique = true)
    open var request: BudgetRequest? = null
        protected set

    fun addBudgetRequest(budgetRequest: BudgetRequest) {
        request = budgetRequest
    }

    fun updateChange(
        userName: String,
        changeBudget: Long,
        changedBudget: Long,
        reason: String?,
        user: User,
    ) {
        this.user = user
        this.userName = userName
        this.changeBudget = changeBudget
        this.changedBudget = changedBudget
        this.reason = reason
    }
}
