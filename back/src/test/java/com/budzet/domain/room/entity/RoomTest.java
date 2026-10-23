package com.budzet.domain.room.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.global.exception.BusinessException;
import com.budzet.global.exception.ErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoomTest {

    @ParameterizedTest(name = "{0}: 금액 {1}")
    @MethodSource("invalidBudgetChanges")
    @DisplayName("증액·감액 금액이 null·0·음수이면 거부하고 두 예산을 보존한다.")
    void updateTotalBudget_rejectsInvalidAmountAndPreservesBudgets(BudgetType type, Long amount) {
        Room room = Room.create("예산 검증 모임", 10_000L, Currency.KRW);
        room.updateAvailableBudget(-6_000L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> room.updateTotalBudget(amount, type));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(room.getTotalBudget()).isEqualTo(10_000L);
        assertThat(room.getAvailableBudget()).isEqualTo(4_000L);
    }

    private static Stream<Arguments> invalidBudgetChanges() {
        return Stream.of(BudgetType.INCREASE, BudgetType.DECREASE)
                .flatMap(type -> Stream.of(
                        Arguments.of(type, null),
                        Arguments.of(type, 0L),
                        Arguments.of(type, -1L)
                ));
    }

    @Test
    @DisplayName("총예산이 충분해도 가용예산보다 많이 감액할 수 없으며 두 예산은 유지된다.")
    void updateTotalBudget_rejectsDecreaseBeyondAvailableBudgetAndPreservesBudgets() {
        Room room = Room.create("예산 검증 모임", 10_000L, Currency.KRW);
        room.updateAvailableBudget(-6_000L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> room.updateTotalBudget(4_001L, BudgetType.DECREASE));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(room.getTotalBudget()).isEqualTo(10_000L);
        assertThat(room.getAvailableBudget()).isEqualTo(4_000L);
    }

    @ParameterizedTest(name = "정산액 {0}")
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("정산액이 null·0·음수이면 거부하고 두 예산을 보존한다.")
    void settleBudget_rejectsInvalidAmountAndPreservesBudgets(Long amount) {
        Room room = Room.create("정산 검증 모임", 10_000L, Currency.KRW);
        room.updateAvailableBudget(-6_000L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> room.settleBudget(6_000L, amount));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(room.getTotalBudget()).isEqualTo(10_000L);
        assertThat(room.getAvailableBudget()).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("총예산을 초과한 정산액은 예산 초과 오류로 거부하고 두 예산을 보존한다.")
    void settleBudget_rejectsAmountBeyondTotalBudgetAndPreservesBudgets() {
        Room room = Room.create("정산 검증 모임", 10_000L, Currency.KRW);
        room.updateAvailableBudget(-6_000L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> room.settleBudget(6_000L, 10_001L));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(room.getTotalBudget()).isEqualTo(10_000L);
        assertThat(room.getAvailableBudget()).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("정산 수정 차액이 null이면 거부하고 두 예산을 보존한다.")
    void updateTotalBudget_rejectsNullDifferenceAndPreservesBudgets() {
        Room room = Room.create("정산 수정 모임", 10_000L, Currency.KRW);
        room.updateAvailableBudget(-6_000L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> room.updateTotalBudget(null));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(room.getTotalBudget()).isEqualTo(10_000L);
        assertThat(room.getAvailableBudget()).isEqualTo(4_000L);
    }

    @ParameterizedTest(name = "차액 {0}: 총예산 {1}, 가용예산 {2}")
    @CsvSource({"1000, 8000, 5000", "0, 7000, 4000"})
    @DisplayName("정산 수정의 양수 차액은 두 예산에 반환하고 차액 0은 두 예산을 유지한다.")
    void updateTotalBudget_appliesRefundOrZeroDifference(
            long difference, long expectedTotal, long expectedAvailable
    ) {
        Room room = Room.create("정산 수정 모임", 10_000L, Currency.KRW);
        // 6,000원과 3,000원을 각각 승인한 뒤 첫 신청을 3,000원으로 정산한다.
        room.updateAvailableBudget(-9_000L);
        room.settleBudget(6_000L, 3_000L);

        room.updateTotalBudget(difference);

        assertThat(room.getTotalBudget()).isEqualTo(expectedTotal);
        assertThat(room.getAvailableBudget()).isEqualTo(expectedAvailable);
    }

    @ParameterizedTest(name = "가용예산 차액 {0}: 결과 {1}")
    @CsvSource({"-1000, 3000", "1000, 5000", "0, 4000"})
    @DisplayName("가용예산의 차감·반환·0 반영은 총예산을 변경하지 않는다.")
    void updateAvailableBudget_changesOnlyAvailableBudget(long difference, long expectedAvailable) {
        Room room = Room.create("가용예산 검증 모임", 10_000L, Currency.KRW);
        room.updateAvailableBudget(-6_000L);

        room.updateAvailableBudget(difference);

        assertThat(room.getTotalBudget()).isEqualTo(10_000L);
        assertThat(room.getAvailableBudget()).isEqualTo(expectedAvailable);
    }
}
