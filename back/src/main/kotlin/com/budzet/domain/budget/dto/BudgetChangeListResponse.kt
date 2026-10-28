package com.budzet.domain.budget.dto

import com.budzet.domain.budget.entity.BudgetChange
import com.budzet.domain.budget.entity.BudgetType
import java.time.LocalDateTime

data class BudgetChangeListResponse(val changes: List<ListItem>) {
    companion object {
        @JvmStatic
        fun from(budgetChanges: List<BudgetChange>): BudgetChangeListResponse =
            BudgetChangeListResponse(budgetChanges.map(ListItem::from))
    }

    data class ListItem(
        val id: Long?,
        val changeBudget: Long,
        val changedBudget: Long,
        val type: BudgetType,
        val userName: String,
        val reason: String?,
        val processedAt: LocalDateTime?,
    ) {
        companion object {
            @JvmStatic
            fun from(budgetChange: BudgetChange): ListItem = ListItem(
                id = budgetChange.id,
                changeBudget = budgetChange.changeBudget,
                changedBudget = budgetChange.changedBudget,
                type = budgetChange.type,
                userName = budgetChange.userName,
                reason = budgetChange.reason,
                processedAt = budgetChange.createdAt,
            )
        }
    }
}
