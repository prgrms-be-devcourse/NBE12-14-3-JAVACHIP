package com.budzet.domain.budget.dto

import com.budzet.domain.budget.entity.BudgetChange
import com.budzet.domain.budget.entity.BudgetType
import java.time.LocalDateTime

data class BudgetChangeDetailResponse(
    val id: Long?,
    val roomId: Long?,
    val userId: Long?,
    val requestId: Long?,
    val changeBudget: Long,
    val changedBudget: Long,
    val budgetType: BudgetType,
    val processedAt: LocalDateTime?,
    val userName: String,
    val userEmail: String,
    val reason: String?,
    val requestCreatedAt: LocalDateTime?,
    val requestUpdatedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(budgetChange: BudgetChange): BudgetChangeDetailResponse {
            // 관리자 예산 변경 건에는 신청 연결이 없으므로 관련 응답 필드는 null이다.
            val request = budgetChange.request
            return BudgetChangeDetailResponse(
                id = budgetChange.id,
                roomId = budgetChange.room.id,
                userId = budgetChange.user.id,
                requestId = request?.id,
                changeBudget = budgetChange.changeBudget,
                changedBudget = budgetChange.changedBudget,
                budgetType = budgetChange.type,
                processedAt = budgetChange.createdAt,
                userName = budgetChange.userName,
                userEmail = budgetChange.user.email,
                reason = budgetChange.reason,
                requestCreatedAt = request?.createdAt,
                requestUpdatedAt = request?.updatedAt,
            )
        }
    }
}
