package com.budzet.domain.budget.integration;

import com.budzet.domain.budget.dto.BudgetChangeCreateRequest;
import com.budzet.domain.budget.dto.BudgetUpdateRequest;
import com.budzet.domain.budget.entity.BudgetChange;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.domain.budget.entity.BudgetType;
import com.budzet.domain.budget.repository.BudgetChangeRepository;
import com.budzet.domain.budget.repository.BudgetRequestRepository;
import com.budzet.domain.budget.service.BudgetChangeService;
import com.budzet.domain.budget.service.BudgetService;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.repository.UserRepository;
import com.budzet.global.exception.BusinessException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BudgetConcurrencyIntegrationTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetChangeService budgetChangeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRoomConnectionRepository userRoomConnectionRepository;

    @Autowired
    private BudgetRequestRepository budgetRequestRepository;

    @Autowired
    private BudgetChangeRepository budgetChangeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================
    // 테스트 데이터 생성
    // =========================

    private User createUser() {
        String email = "c-"
                + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
                + "@test.com";

        User user = new User(
                email,
                "test-password",
                "동시성 테스트 사용자"
        );

        return userRepository.saveAndFlush(user);
    }

    private Room createRoom(Long totalBudget) {
        Room room = Room.create(
                "동시성 테스트",
                totalBudget,
                Currency.KRW
        );

        return roomRepository.saveAndFlush(room);
    }

    private void createOwnerMembership(User user, Room room) {
        UserRoomConnection connection =
                UserRoomConnection.createOwner(user, room);

        userRoomConnectionRepository.saveAndFlush(connection);
    }

    /**
     * 승인된 예산 신청과 예약된 가용예산을 준비한다.
     *
     * 총예산: totalBudget
     * 신청금액: requestedAmount
     * 가용예산: totalBudget - requestedAmount
     */
    private ApprovedRequestFixture createApprovedRequestFixture(
            Long totalBudget,
            Long requestedAmount
    ) {
        User user = createUser();
        Room room = createRoom(totalBudget);

        createOwnerMembership(user, room);

        room.updateAvailableBudget(-requestedAmount);
        roomRepository.saveAndFlush(room);

        BudgetRequest request = new BudgetRequest(
                room,
                user,
                "동시성 테스트 신청",
                requestedAmount
        );

        request.approveRequest();

        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        return new ApprovedRequestFixture(user, room, savedRequest);
    }

    private record ApprovedRequestFixture(
            User user,
            Room room,
            BudgetRequest request
    ) {
    }

    // =========================
    // 두 스레드의 동시 실행을 위한 도우미
    // =========================

    /**
     * 두 작업이 모두 준비되면 같은 시작 신호로 실행한다.
     *
     * 두 서비스 호출은 서로 다른 스레드에서 실행된다.
     * Spring 트랜잭션은 스레드별로 처리된다.
     */
    private <T> List<T> executeConcurrently(
            Callable<T> firstTask,
            Callable<T> secondTask
    ) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<T> firstFuture = executor.submit(
                    waitForStart(firstTask, ready, start)
            );

            Future<T> secondFuture = executor.submit(
                    waitForStart(secondTask, ready, start)
            );

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "두 작업이 실행 준비를 마쳐야 합니다."
            );

            // 두 작업을 같은 출발 신호로 시작한다.
            start.countDown();

            T firstResult = firstFuture.get(30, TimeUnit.SECONDS);
            T secondResult = secondFuture.get(30, TimeUnit.SECONDS);

            return List.of(firstResult, secondResult);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private <T> Callable<T> waitForStart(
            Callable<T> task,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();

            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "동시 실행 시작 신호를 받지 못했습니다."
                );
            }

            return task.call();
        };
    }

    // =========================
    // H2 실제 락 대기 확인
    // =========================

    /**
     * H2 세션 정보를 조회하여 다른 세션에 의해 차단된
     * Room 관련 SQL이 실제로 존재하는지 확인한다.
     *
     * BLOCKER_ID:
     * 현재 세션을 차단하고 있는 세션 ID
     *
     * EXECUTING_STATEMENT:
     * 현재 실행 중인 SQL
     *
     * 제한 시간 안에 차단 세션이 발견되면 관련 정보를 출력한다.
     */
    private void awaitH2RoomLockWait(Duration timeout)
            throws InterruptedException {

        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {

            List<Map<String, Object>> waitingSessions =
                    jdbcTemplate.queryForList(
                            """
                            SELECT
                                SESSION_ID,
                                USER_NAME,
                                SESSION_STATE,
                                BLOCKER_ID,
                                EXECUTING_STATEMENT
                            FROM INFORMATION_SCHEMA.SESSIONS
                            WHERE BLOCKER_ID IS NOT NULL
                              AND UPPER(
                                  COALESCE(EXECUTING_STATEMENT, '')
                              ) LIKE '%ROOM%'
                            """
                    );

            if (!waitingSessions.isEmpty()) {
                System.out.println(
                        "[H2 실제 락 대기 확인] " + waitingSessions
                );

                return;
            }

            // DB 상태를 25ms 간격으로 다시 확인한다.
            Thread.sleep(25);
        }

        fail(
                "제한 시간 내에 Room 관련 SQL의 실제 락 대기를 "
                        + "확인하지 못했습니다."
        );
    }

    // =========================
    // 테스트 1. 같은 모임의 예산 증액 두 건
    // =========================

    @Test
    @DisplayName("동시성 - 예산 증액 두 건이 모두 반영된다")
    void concurrentBudgetIncreases() throws Exception {

        // given
        User user = createUser();
        Room room = createRoom(10000L);

        createOwnerMembership(user, room);

        BudgetUpdateRequest firstRequest =
                new BudgetUpdateRequest(
                        3000L,
                        BudgetType.INCREASE,
                        "동시 증액 1"
                );

        BudgetUpdateRequest secondRequest =
                new BudgetUpdateRequest(
                        5000L,
                        BudgetType.INCREASE,
                        "동시 증액 2"
                );

        // when
        executeConcurrently(
                () -> budgetService.updateBudget(
                        room.getId(),
                        user.getId(),
                        firstRequest
                ),
                () -> budgetService.updateBudget(
                        room.getId(),
                        user.getId(),
                        secondRequest
                )
        );

        // then
        Room updatedRoom = roomRepository.findById(room.getId())
                .orElseThrow();

        // 10,000 + 3,000 + 5,000
        assertEquals(18000L, updatedRoom.getTotalBudget());
        assertEquals(18000L, updatedRoom.getAvailableBudget());

        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdOrderByCreatedAtDesc(room.getId());

        assertEquals(2, histories.size());

        assertEquals(
                2L,
                histories.stream()
                        .filter(change ->
                                change.getType() == BudgetType.INCREASE)
                        .count()
        );

        assertEquals(
                8000L,
                histories.stream()
                        .mapToLong(BudgetChange::getChangeBudget)
                        .sum()
        );
    }

    // =========================
    // 테스트 2. 예산 증액과 정산 등록 동시 실행
    // =========================

    @Test
    @DisplayName(
            "동시성 - 예산 증액과 정산 등록이 함께 실행되어도 "
                    + "예산 정합성이 유지된다"
    )
    void concurrentBudgetIncreaseAndSettlement() throws Exception {

        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 6000L);

        BudgetUpdateRequest increaseRequest =
                new BudgetUpdateRequest(
                        3000L,
                        BudgetType.INCREASE,
                        "정산 중 예산 증액"
                );

        BudgetChangeCreateRequest settlementRequest =
                new BudgetChangeCreateRequest(
                        5000L,
                        "동시 정산"
                );

        // when
        executeConcurrently(
                () -> budgetService.updateBudget(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        increaseRequest
                ),
                () -> budgetChangeService.createBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.request().getId(),
                        settlementRequest
                )
        );

        // then
        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        /*
         * 초기 상태:
         * 총예산 10,000원
         * 가용예산 4,000원 (6,000원 예약)
         *
         * 증액 +3,000원, 정산금액 5,000원.
         * 최종 총예산과 가용예산은 각각 8,000원이어야 한다.
         */
        assertEquals(8000L, updatedRoom.getTotalBudget());
        assertEquals(8000L, updatedRoom.getAvailableBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdOrderByCreatedAtDesc(
                                fixture.room().getId()
                        );

        assertEquals(2, histories.size());

        assertEquals(
                1L,
                histories.stream()
                        .filter(change ->
                                change.getType() == BudgetType.INCREASE)
                        .count()
        );

        assertEquals(
                1L,
                histories.stream()
                        .filter(change ->
                                change.getType() == BudgetType.SETTLEMENT)
                        .count()
        );
    }

    // =========================
    // 테스트 3. 같은 신청 건의 중복 정산 요청
    // =========================

    @Test
    @DisplayName("동시성 - 동일한 신청 건은 한 번만 정산된다")
    void concurrentSettlementOfSameRequest() throws Exception {

        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10000L, 5000L);

        BudgetChangeCreateRequest settlementRequest =
                new BudgetChangeCreateRequest(
                        3000L,
                        "동일 신청 중복 정산 시도"
                );

        Callable<SettlementAttempt> settlementTask = () -> {
            try {
                budgetChangeService.createBudgetChange(
                        fixture.room().getId(),
                        fixture.user().getId(),
                        fixture.request().getId(),
                        settlementRequest
                );

                return new SettlementAttempt(true, null);

            } catch (RuntimeException exception) {
                return new SettlementAttempt(false, exception);
            }
        };

        // when
        List<SettlementAttempt> results =
                executeConcurrently(settlementTask, settlementTask);

        // then: 두 요청 중 정확히 하나만 성공해야 한다.
        long successCount = results.stream()
                .filter(SettlementAttempt::success)
                .count();

        assertEquals(1L, successCount);

        SettlementAttempt failedAttempt = results.stream()
                .filter(result -> !result.success())
                .findFirst()
                .orElseThrow();

        assertNotNull(failedAttempt.exception());

        /*
         * 실패 원인은 실행 타이밍에 따라 달라질 수 있다.
         * - 비즈니스 검증 실패: BusinessException
         * - DB 유일성 제약 위반: DataIntegrityViolationException
         * - DB 락 대기 실패: 락 관련 예외
         */
        RuntimeException failure = failedAttempt.exception();

        assertTrue(
                failure instanceof BusinessException
                        || failure instanceof DataIntegrityViolationException
                        || failure instanceof PessimisticLockingFailureException
                        || failure instanceof jakarta.persistence.LockTimeoutException
                        || failure instanceof TransactionSystemException
                        || failure instanceof JpaSystemException,
                "정산 중복 요청은 비즈니스 검증 또는 DB 제약/락 오류로 "
                        + "거부되어야 합니다. 실제 예외: "
                        + failure.getClass().getName()
        );

        // 실제 DB 상태는 정산 한 건만 반영되어야 한다.
        Room updatedRoom = roomRepository.findById(fixture.room().getId())
                .orElseThrow();

        assertEquals(7000L, updatedRoom.getTotalBudget());
        assertEquals(7000L, updatedRoom.getAvailableBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(fixture.request().getId())
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        List<BudgetChange> histories =
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                fixture.room().getId(),
                                BudgetType.SETTLEMENT
                        );

        assertEquals(1, histories.size());
        assertEquals(3000L, histories.get(0).getChangedBudget());
    }

    private record SettlementAttempt(
            boolean success,
            RuntimeException exception
    ) {
    }

    // =========================
    // 테스트 4. 실제 H2 락 대기 확인
    // =========================

    @Test
    @DisplayName(
            "동시성 - 실제 H2 락 대기를 확인한 후 "
                    + "락 해제 시 예산 변경에 성공한다"
    )
    void budgetUpdate_waitsForRoomLockAndSucceedsAfterRelease()
            throws Exception {

        // given
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(20000L, 10000L);

        Long roomId = fixture.room().getId();
        Long userId = fixture.user().getId();

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch secondTaskStarted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            /*
             * 첫 번째 스레드:
             * 별도 트랜잭션에서 모임 행의 비관적 락을 획득한다.
             * releaseLock 신호가 올 때까지 트랜잭션을 유지한다.
             */
            Future<?> lockHolder = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {

                        roomRepository.findByWithLock(roomId)
                                .orElseThrow();

                        lockAcquired.countDown();

                        try {
                            if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "락 해제 신호를 기다리다가 "
                                                + "시간 초과가 발생했습니다."
                                );
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();

                            throw new IllegalStateException(
                                    "락 보유 스레드가 중단되었습니다.",
                                    e
                            );
                        }
                    })
            );

            assertTrue(
                    lockAcquired.await(5, TimeUnit.SECONDS),
                    "첫 번째 트랜잭션이 모임 행의 락을 획득해야 합니다."
            );

            /*
             * 두 번째 스레드:
             * 첫 번째 트랜잭션이 락을 보유한 상태에서
             * 동일한 모임의 예산 증액 요청을 실행한다.
             */
            BudgetUpdateRequest updateRequest =
                    new BudgetUpdateRequest(
                            1000L,
                            BudgetType.INCREASE,
                            "락 경합 테스트"
                    );

            Future<?> secondRequest = executor.submit(() -> {
                secondTaskStarted.countDown();

                return budgetService.updateBudget(
                        roomId,
                        userId,
                        updateRequest
                );
            });

            assertTrue(
                    secondTaskStarted.await(5, TimeUnit.SECONDS),
                    "두 번째 예산 변경 작업이 시작되어야 합니다."
            );

            try {
                /*
                 * 핵심 검증:
                 * 단순히 응답이 늦는지만 확인하지 않는다.
                 * H2의 SESSIONS에서 BLOCKER_ID가 존재하는
                 * Room 관련 SQL을 실제로 관찰한다.
                 */
                awaitH2RoomLockWait(Duration.ofSeconds(5));

                assertFalse(
                        secondRequest.isDone(),
                        "DB에서 락 대기가 관찰되는 동안 "
                                + "두 번째 요청은 완료되지 않아야 합니다."
                );

            } finally {
                /*
                 * 첫 번째 트랜잭션의 대기를 해제한다.
                 * 트랜잭션 콜백이 종료되면 보유한 락이 해제된다.
                 */
                releaseLock.countDown();
            }

            // 첫 번째 트랜잭션 종료 확인
            lockHolder.get(5, TimeUnit.SECONDS);

            // 락 해제 후 두 번째 예산 변경 요청 완료 확인
            secondRequest.get(15, TimeUnit.SECONDS);

            // then
            Room updatedRoom = roomRepository.findById(roomId)
                    .orElseThrow();

            /*
             * 초기 상태:
             * 총예산 20,000원
             * 신청금액 10,000원 예약
             * 가용예산 10,000원
             *
             * 예산 증액: 1,000원
             */
            assertEquals(21000L, updatedRoom.getTotalBudget());
            assertEquals(11000L, updatedRoom.getAvailableBudget());

            List<BudgetChange> histories =
                    budgetChangeRepository
                            .findAllByRoomIdOrderByCreatedAtDesc(roomId);

            assertEquals(1, histories.size());

            assertEquals(
                    BudgetType.INCREASE,
                    histories.get(0).getType()
            );

            assertEquals(
                    1000L,
                    histories.get(0).getChangeBudget()
            );

        } finally {
            // 실패하더라도 락 보유 스레드가 계속 대기하지 않도록 한다.
            releaseLock.countDown();

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(5, TimeUnit.SECONDS),
                    "테스트용 스레드가 정상적으로 종료되어야 합니다."
            );
        }
    }
}