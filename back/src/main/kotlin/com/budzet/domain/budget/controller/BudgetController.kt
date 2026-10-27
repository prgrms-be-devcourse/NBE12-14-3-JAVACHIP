package com.budzet.domain.budget.controller

import com.budzet.domain.budget.dto.BudgetHistoryResponse
import com.budzet.domain.budget.dto.BudgetResponse
import com.budzet.domain.budget.dto.BudgetUpdateRequest
import com.budzet.domain.budget.dto.BudgetUpdateResponse
import com.budzet.domain.budget.service.BudgetService
import com.budzet.global.api.ApiResponse
import com.budzet.global.rq.Rq
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "03 - 1. 예산 관리", description = "모임방 예산 조회, 변경 이력 조회 및 예산 변경 API")
@RestController
@RequestMapping("/rooms")
class BudgetController(
    private val budgetService: BudgetService,
    private val rq: Rq,
) {
    @Operation(
        summary = "예산 조회",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{roomId}/budget")
    fun getBudget(
        @PathVariable roomId: Long,
    ): ResponseEntity<ApiResponse<BudgetResponse>> {
        val user = rq.getActor()

        val budgetResponse = budgetService.getBudget(roomId, checkNotNull(user.id))
        return ApiResponse.response(
            HttpStatus.OK,
            "예산 조회에 성공하였습니다.",
            budgetResponse)
    }

    @Operation(
        summary = "예산 변경 이력 조회",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @GetMapping("/{roomId}/budget/history")
    fun getBudgetHistory(
        @PathVariable roomId: Long
    ): ResponseEntity<ApiResponse<BudgetHistoryResponse>> {
        val user = rq.getActor()

        val budgetHistoryResponse = budgetService.getBudgetHistory(roomId, checkNotNull(user.id))
        return ApiResponse.response(
            HttpStatus.OK,
            "예산 변경 목록 조회에 성공하였습니다.",
            budgetHistoryResponse)
    }

    @Operation(
        summary = "예산 수정",
        description = "모임장, 운영자 권한 필요",
        security = [SecurityRequirement(name = "BearerAuth")]
    )
    @PatchMapping("/{roomId}/budget")
    fun updateBudget(
        @PathVariable roomId: Long,
        @RequestBody @Valid budgetUpdateRequest: BudgetUpdateRequest
    ): ResponseEntity<ApiResponse<BudgetUpdateResponse>> {
        val user = rq.getActor()

        val budgetUpdateResponse = budgetService.updateBudget(
            roomId, checkNotNull(user.id),
            budgetUpdateRequest)

        return ApiResponse.response(
            HttpStatus.OK,
            "예산 변경에 성공하였습니다.",
            budgetUpdateResponse)
    }
}
