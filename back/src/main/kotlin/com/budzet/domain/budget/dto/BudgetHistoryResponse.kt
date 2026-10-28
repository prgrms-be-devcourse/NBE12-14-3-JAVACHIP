package com.budzet.domain.budget.dto

import com.budzet.domain.budget.entity.BudgetChange
import java.time.LocalDateTime

data class BudgetHistoryResponse(val history: List<HistoryItem>) {
    companion object {
        @JvmStatic
        fun from(budgetChanges: List<BudgetChange>): BudgetHistoryResponse =
            BudgetHistoryResponse(budgetChanges.map(HistoryItem::from))
    }

    data class HistoryItem(
        val id: Long?,
        val changeBudget: Long,
        val changedBudget: Long,
        val type: String,
        val userName: String,
        val reason: String?,
        val processedAt: LocalDateTime?,
    ) {
        companion object {
            @JvmStatic
            fun from(budgetChange: BudgetChange): HistoryItem = HistoryItem(
                id = budgetChange.id,
                changeBudget = budgetChange.changeBudget,
                changedBudget = budgetChange.changedBudget,
                type = budgetChange.type.name,
                userName = budgetChange.userName,
                reason = budgetChange.reason,
                processedAt = budgetChange.createdAt,
            )
        }
    }
}
