
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
import com.budzet.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class BudgetConcurrencyIntegrationTest {

    /*
     * 이 동시성 테스트 클래스 전용 MySQL 컨테이너입니다.
     * application-test.yml의 H2 설정은 수정하지 않습니다.
     */
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("budzet_concurrency_test")
                    .withUsername("budzet_test")
                    .withPassword("budzet_test");

    /*
     * 이 테스트 클래스의 데이터소스만 MySQL로 덮어씁니다.
     */
    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "spring.datasource.driver-class-name",
                MYSQL::getDriverClassName
        );

        registry.add(
                "spring.jpa.hibernate.ddl-auto",
                () -> "create-drop"
        );

        registry.add(
                "spring.jpa.database-platform",
                () -> "org.hibernate.dialect.MySQLDialect"
        );
    }

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

    /*
     * MySQL의 실제 행 락 대기 상태를 조회할 수 있도록
     * 테스트 컨테이너의 DB 사용자에게 필요한 조회 권한을 부여합니다.
     *
     * PROCESS 권한은 사용하지 않습니다.
     */
    @BeforeEach
    void grantPrivilegesForLockInspection()
            throws IOException, InterruptedException {

        var result = MYSQL.execInContainer(
                "mysql",
                "-uroot",
                "-p" + MYSQL.getPassword(),
                "-e",
                """
                GRANT SELECT ON performance_schema.data_lock_waits
                    TO 'budzet_test'@'%';
                GRANT SELECT ON performance_schema.data_locks
                    TO 'budzet_test'@'%';
                """
        );

        assertEquals(
                0,
                result.getExitCode(),
                "MySQL 락 조회 권한 부여 실패: " + result.getStderr()
        );
    }

    // =========================================================
    // 테스트 1: 동시에 발생한 예산 증가 두 건
    // =========================================================

    @Test
    void concurrentBudgetIncreases() throws Exception {
        User user = createUser();
        Room room = createRoom(10_000L);
        createOwnerMembership(user, room);

        Long roomId = room.getId();
        Long userId = user.getId();

        executeConcurrently(
                () -> {
                    budgetService.updateBudget(
                            roomId,
                            userId,
                            new BudgetUpdateRequest(
                                    3_000L,
                                    BudgetType.INCREASE,
                                    "동시성 테스트 예산 증가 1"
                            )
                    );
                    return null;
                },
                () -> {
                    budgetService.updateBudget(
                            roomId,
                            userId,
                            new BudgetUpdateRequest(
                                    5_000L,
                                    BudgetType.INCREASE,
                                    "동시성 테스트 예산 증가 2"
                            )
                    );
                    return null;
                }
        );

        Room updatedRoom = roomRepository.findById(roomId)
                .orElseThrow();

        assertEquals(18_000L, updatedRoom.getTotalBudget());
        assertEquals(18_000L, updatedRoom.getAvailableBudget());

        List<BudgetChange> changes =
                budgetChangeRepository
                        .findAllByRoomIdOrderByCreatedAtDesc(roomId);

        assertEquals(2, changes.size());

        long totalChangedBudget = changes.stream()
                .mapToLong(BudgetChange::getChangedBudget)
                .sum();

        assertEquals(8_000L, totalChangedBudget);

        assertEquals(
                2L,
                changes.stream()
                        .filter(change ->
                                change.getType() == BudgetType.INCREASE)
                        .count()
        );
    }

    // =========================================================
    // 테스트 2: 예산 증가와 정산의 동시 실행
    // =========================================================

    @Test
    void concurrentBudgetIncreaseAndSettlement() throws Exception {
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10_000L, 6_000L);

        Long roomId = fixture.room().getId();
        Long userId = fixture.user().getId();
        Long requestId = fixture.request().getId();

        executeConcurrently(
                () -> {
                    budgetService.updateBudget(
                            roomId,
                            userId,
                            new BudgetUpdateRequest(
                                    3_000L,
                                    BudgetType.INCREASE,
                                    "정산과 동시 예산 증가"
                            )
                    );
                    return null;
                },
                () -> {
                    budgetChangeService.createBudgetChange(
                            roomId,
                            userId,
                            requestId,
                            new BudgetChangeCreateRequest(
                                    5_000L,
                                    "동시성 테스트 정산"
                            )
                    );
                    return null;
                }
        );

        Room updatedRoom = roomRepository.findById(roomId)
                .orElseThrow();

        // 10,000 + 3,000 - 5,000 = 8,000
        assertEquals(8_000L, updatedRoom.getTotalBudget());
        assertEquals(8_000L, updatedRoom.getAvailableBudget());

        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(requestId)
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        List<BudgetChange> changes =
                budgetChangeRepository
                        .findAllByRoomIdOrderByCreatedAtDesc(roomId);

        assertEquals(2, changes.size());

        assertEquals(
                1L,
                changes.stream()
                        .filter(change ->
                                change.getType() == BudgetType.INCREASE)
                        .count()
        );

        assertEquals(
                1L,
                changes.stream()
                        .filter(change ->
                                change.getType() == BudgetType.SETTLEMENT)
                        .count()
        );
    }

    // =========================================================
    // 테스트 3: 동일한 신청의 동시 정산
    //
    // 성공은 한 번만 허용합니다.
    // 실패는 다음 두 경우만 허용합니다.
    //
    // 1. BUDGET_REQUEST_NOT_APPROVED 비즈니스 예외
    // 2. request_id 고유 제약조건 위반
    // =========================================================

    @Test
    void concurrentSettlementOfSameRequest() throws Exception {
        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(10_000L, 5_000L);

        Long roomId = fixture.room().getId();
        Long userId = fixture.user().getId();
        Long requestId = fixture.request().getId();

        List<Object> results = executeConcurrently(
                () -> {
                    try {
                        budgetChangeService.createBudgetChange(
                                roomId,
                                userId,
                                requestId,
                                new BudgetChangeCreateRequest(
                                        3_000L,
                                        "동일 요청 동시 정산 1"
                                )
                        );

                        return null;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                },
                () -> {
                    try {
                        budgetChangeService.createBudgetChange(
                                roomId,
                                userId,
                                requestId,
                                new BudgetChangeCreateRequest(
                                        3_000L,
                                        "동일 요청 동시 정산 2"
                                )
                        );

                        return null;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }
        );

        // 성공은 정확히 한 번이어야 합니다.
        long successCount = results.stream()
                .filter(Objects::isNull)
                .count();

        assertEquals(
                1L,
                successCount,
                "동일 신청의 정산은 정확히 한 번만 성공해야 합니다."
        );

        // 실패도 정확히 한 건이어야 합니다.
        List<RuntimeException> failures = results.stream()
                .filter(RuntimeException.class::isInstance)
                .map(RuntimeException.class::cast)
                .toList();

        assertEquals(
                1,
                failures.size(),
                "동일 신청의 정산 실패는 정확히 한 건이어야 합니다."
        );

        RuntimeException failure = failures.get(0);

        // 실패 원인이 의도한 비즈니스 오류 또는 DB 제약조건인지 검증합니다.
        assertExpectedDuplicateSettlementFailure(failure);

        // 최종 예산이 한 번만 반영됐는지 확인합니다.
        Room updatedRoom = roomRepository.findById(roomId)
                .orElseThrow();

        assertEquals(7_000L, updatedRoom.getTotalBudget());
        assertEquals(7_000L, updatedRoom.getAvailableBudget());

        // 신청 상태가 정산 완료인지 확인합니다.
        BudgetRequest updatedRequest =
                budgetRequestRepository.findById(requestId)
                        .orElseThrow();

        assertEquals(
                BudgetRequestType.SETTLEMENT.name(),
                updatedRequest.getStatus()
        );

        // 정산 내역도 한 건만 생성됐는지 확인합니다.
        List<BudgetChange> settlementChanges =
                budgetChangeRepository
                        .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                roomId,
                                BudgetType.SETTLEMENT
                        );

        assertEquals(
                1,
                settlementChanges.size(),
                "정산 내역은 한 건만 생성되어야 합니다."
        );

        assertEquals(
                3_000L,
                settlementChanges.get(0).getChangedBudget()
        );
    }

    // =========================================================
    // 테스트 4: MySQL Room 행 락 대기
    // =========================================================

    @Test
    void budgetUpdate_waitsForRoomLockAndSucceedsAfterRelease()
            throws Exception {

        ApprovedRequestFixture fixture =
                createApprovedRequestFixture(20_000L, 10_000L);

        Long roomId = fixture.room().getId();
        Long userId = fixture.user().getId();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        Future<?> lockHolderFuture = null;

        try {
            // 첫 번째 트랜잭션이 Room 행 락을 획득하고 유지합니다.
            lockHolderFuture = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        roomRepository.findByWithLock(roomId)
                                .orElseThrow();

                        lockAcquired.countDown();

                        try {
                            boolean released = releaseLock.await(
                                    15,
                                    TimeUnit.SECONDS
                            );

                            if (!released) {
                                throw new IllegalStateException(
                                        "Room 락 해제 신호를 받지 못했습니다."
                                );
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();

                            throw new IllegalStateException(
                                    "Room 락 대기 중 인터럽트가 발생했습니다.",
                                    exception
                            );
                        }
                    })
            );

            assertTrue(
                    lockAcquired.await(10, TimeUnit.SECONDS),
                    "첫 번째 트랜잭션이 Room 락을 획득하지 못했습니다."
            );

            // 두 번째 트랜잭션이 같은 Room의 예산을 수정합니다.
            Future<?> updateFuture = executor.submit(() ->
                    budgetService.updateBudget(
                            roomId,
                            userId,
                            new BudgetUpdateRequest(
                                    1_000L,
                                    BudgetType.INCREASE,
                                    "락 해제 후 예산 증가"
                            )
                    )
            );

            // MySQL performance_schema에서 실제 락 대기를 확인합니다.
            awaitMySqlRoomLockWait(Duration.ofSeconds(5));

            assertFalse(
                    updateFuture.isDone(),
                    "Room 행 락이 유지되는 동안 예산 수정이 완료되면 안 됩니다."
            );

            // 첫 번째 트랜잭션을 종료하여 락을 해제합니다.
            releaseLock.countDown();

            lockHolderFuture.get(10, TimeUnit.SECONDS);
            updateFuture.get(10, TimeUnit.SECONDS);

            Room updatedRoom = roomRepository.findById(roomId)
                    .orElseThrow();

            assertEquals(21_000L, updatedRoom.getTotalBudget());
            assertEquals(11_000L, updatedRoom.getAvailableBudget());

            List<BudgetChange> changes =
                    budgetChangeRepository
                            .findAllByRoomIdAndTypeOrderByCreatedAtDesc(
                                    roomId,
                                    BudgetType.INCREASE
                            );

            assertEquals(1, changes.size());

            assertEquals(
                    1_000L,
                    changes.get(0).getChangedBudget()
            );
        } finally {
            // 테스트 실패 시에도 락을 해제합니다.
            releaseLock.countDown();

            if (lockHolderFuture != null) {
                try {
                    lockHolderFuture.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 원래 테스트 실패를 정리 예외로 덮어쓰지 않습니다.
                }
            }

            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // =========================================================
    // 중복 정산 실패 원인 검증
    // =========================================================

    private void assertExpectedDuplicateSettlementFailure(
            RuntimeException failure
    ) {
        String exceptionChain = describeCauseChain(failure);

        /*
         * 경우 1: 서비스가 비즈니스 오류로 정산을 거부한 경우
         */
        BusinessException businessException =
                findCause(failure, BusinessException.class);

        if (businessException != null) {
            ErrorCode actualErrorCode =
                    extractBusinessErrorCode(businessException);

            assertEquals(
                    ErrorCode.BUDGET_REQUEST_NOT_APPROVED,
                    actualErrorCode,
                    "이미 정산된 신청의 오류 코드가 올바르지 않습니다. "
                            + exceptionChain
            );

            return;
        }

        /*
         * 경우 2: DB 고유 제약조건이 중복 정산을 거부한 경우
         */
        DataIntegrityViolationException integrityException =
                findCause(
                        failure,
                        DataIntegrityViolationException.class
                );

        assertNotNull(
                integrityException,
                () -> "실패 원인이 비즈니스 예외도, DB 무결성 오류도 아닙니다. "
                        + exceptionChain
        );

        assertTrue(
                exceptionChain.contains("Duplicate entry"),
                "중복 키 오류가 아닙니다. " + exceptionChain
        );

        /*
         * 오류 메시지에서 위반한 인덱스 이름을 추출합니다.
         *
         * 예:
         * budget_change.UKqqwfymyfh4i6ukmc1u4m469vb
         */
        Matcher matcher = Pattern
                .compile("key 'budget_change\\.([^']+)'")
                .matcher(exceptionChain);

        assertTrue(
                matcher.find(),
                "위반한 budget_change 인덱스 이름을 찾을 수 없습니다. "
                        + exceptionChain
        );

        String indexName = matcher.group(1);

        /*
         * 인덱스 이름 자체를 하드코딩하지 않고,
         * MySQL 메타데이터에서 실제 인덱스 컬럼을 확인합니다.
         */
        List<String> uniqueIndexColumns = jdbcTemplate.queryForList(
                """
                SELECT COLUMN_NAME
                FROM information_schema.statistics
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'budget_change'
                  AND INDEX_NAME = ?
                  AND NON_UNIQUE = 0
                ORDER BY SEQ_IN_INDEX
                """,
                String.class,
                indexName
        );

        assertEquals(
                List.of("request_id"),
                uniqueIndexColumns,
                "실패 원인이 request_id 중복 방지 제약조건이 아닙니다. "
                        + "인덱스: " + indexName
                        + ", 실제 컬럼: " + uniqueIndexColumns
        );
    }

    // =========================================================
    // 테스트 데이터 생성
    // =========================================================

    private User createUser() {
        String email =
                "c-" + UUID.randomUUID()
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

    private Room createRoom(long totalBudget) {
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

    private ApprovedRequestFixture createApprovedRequestFixture(
            long totalBudget,
            long requestedAmount
    ) {
        User user = createUser();
        Room room = createRoom(totalBudget);

        createOwnerMembership(user, room);

        // 신청 금액만큼 가용 예산을 차감한 상태로 구성합니다.
        room.updateAvailableBudget(-requestedAmount);
        roomRepository.saveAndFlush(room);

        BudgetRequest request = new BudgetRequest(
                room,
                user,
                user.getName(),
                requestedAmount
        );

        request.approveRequest();

        BudgetRequest savedRequest =
                budgetRequestRepository.saveAndFlush(request);

        return new ApprovedRequestFixture(
                user,
                room,
                savedRequest
        );
    }

    private record ApprovedRequestFixture(
            User user,
            Room room,
            BudgetRequest request
    ) {
    }

    // =========================================================
    // 동시 실행 유틸리티
    // =========================================================

    private List<Object> executeConcurrently(
            Callable<?> first,
            Callable<?> second
    ) throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Object> firstFuture = executor.submit(() -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new TimeoutException(
                            "첫 번째 작업이 시작 신호를 받지 못했습니다."
                    );
                }

                return first.call();
            });

            Future<Object> secondFuture = executor.submit(() -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new TimeoutException(
                            "두 번째 작업이 시작 신호를 받지 못했습니다."
                    );
                }

                return second.call();
            });

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "두 작업이 동시에 실행 준비를 마치지 못했습니다."
            );

            start.countDown();

            Object firstResult =
                    firstFuture.get(30, TimeUnit.SECONDS);

            Object secondResult =
                    secondFuture.get(30, TimeUnit.SECONDS);

            // null 결과도 허용해야 하므로 List.of()를 사용하지 않습니다.
            return Arrays.asList(firstResult, secondResult);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // =========================================================
    // MySQL 행 락 대기 확인
    // =========================================================

    private void awaitMySqlRoomLockWait(Duration timeout)
            throws InterruptedException {

        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> waitingTransactions =
                    jdbcTemplate.queryForList("""
                            SELECT
                                requesting_lock.OBJECT_SCHEMA,
                                requesting_lock.OBJECT_NAME,
                                requesting_lock.LOCK_TYPE,
                                requesting_lock.LOCK_MODE,
                                requesting_lock.LOCK_STATUS
                            FROM performance_schema.data_lock_waits AS lock_wait
                            JOIN performance_schema.data_locks AS requesting_lock
                              ON requesting_lock.ENGINE = lock_wait.ENGINE
                             AND requesting_lock.ENGINE_LOCK_ID =
                                 lock_wait.REQUESTING_ENGINE_LOCK_ID
                            WHERE requesting_lock.OBJECT_SCHEMA = DATABASE()
                              AND LOWER(requesting_lock.OBJECT_NAME) LIKE '%room%'
                              AND requesting_lock.LOCK_STATUS = 'WAITING'
                            """);

            if (!waitingTransactions.isEmpty()) {
                System.out.println(
                        "MySQL Room 락 대기 확인: " + waitingTransactions
                );
                return;
            }

            Thread.sleep(25);
        }

        fail("제한 시간 내에 MySQL Room 락 대기를 확인하지 못했습니다.");
    }

    // =========================================================
    // 예외 체인 및 ErrorCode 확인 유틸리티
    // =========================================================

    private <T extends Throwable> T findCause(
            Throwable failure,
            Class<T> expectedType
    ) {
        Throwable current = failure;

        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }

            current = current.getCause();
        }

        return null;
    }

    /*
     * BusinessException 구현을 아직 확인하지 못했으므로
     * getErrorCode()를 리플렉션으로 호출합니다.
     *
     * BusinessException에 getErrorCode()가 있고 ErrorCode를 반환한다면
     * 해당 메서드를 통해 실제 오류 코드를 비교합니다.
     */
    private ErrorCode extractBusinessErrorCode(
            BusinessException exception
    ) {
        Object value = invokeNoArg(exception, "getErrorCode");

        assertNotNull(
                value,
                "BusinessException에서 getErrorCode()를 찾을 수 없습니다. "
                        + "BusinessException 구현을 확인하세요."
        );

        assertTrue(
                value instanceof ErrorCode,
                "getErrorCode() 반환 타입이 ErrorCode가 아닙니다. 실제 타입: "
                        + value.getClass().getName()
        );

        return (ErrorCode) value;
    }

    private String describeCauseChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;

        while (current != null) {
            if (!result.isEmpty()) {
                result.append(" -> ");
            }

            result.append(current.getClass().getName());

            if (current.getMessage() != null) {
                result.append(": ").append(current.getMessage());
            }

            current = current.getCause();
        }

        return result.toString();
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (NoSuchMethodException exception) {
            return null;
        } catch (
                IllegalAccessException |
                InvocationTargetException exception
        ) {
            throw new AssertionError(
                    methodName + "() 호출 중 오류가 발생했습니다.",
                    exception
            );
        }
    }
}
