package com.budzet.domain.budget.dto

import com.budzet.domain.room.entity.Room

data class BudgetUpdateResponse(val roomId: Long, val totalBudget: Long) {
    companion object {
        @JvmStatic
        fun from(room: Room): BudgetUpdateResponse = BudgetUpdateResponse(roomId = requireNotNull(room.id),
            totalBudget = requireNotNull(room.totalBudget),
        )
    }
}
