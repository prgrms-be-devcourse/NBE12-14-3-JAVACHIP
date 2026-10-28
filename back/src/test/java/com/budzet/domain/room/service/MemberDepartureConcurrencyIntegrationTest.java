package com.budzet.domain.room.service;

import com.budzet.domain.budget.dto.BudgetChangeCreateRequest;
import com.budzet.domain.budget.entity.BudgetRequest;
import com.budzet.domain.budget.entity.BudgetRequestType;
import com.budzet.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

// 상속한 상태별 정리·롤백 테스트도 실제 MySQL에서 함께 실행한다.
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberDepartureConcurrencyIntegrationTest extends MemberDepartureIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("departure_test").withUsername("budzet_test").withPassword("budzet_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void grantLockInspection() throws Exception {
        var result = MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e", """
                GRANT SELECT ON performance_schema.data_lock_waits TO 'budzet_test'@'%';
                GRANT SELECT ON performance_schema.data_locks TO 'budzet_test'@'%';
                """);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("정산과 탈퇴·강퇴가 겹치면 먼저 끝난 작업에 따라 삭제 또는 정산 보존하며 예산이 일치한다")
    void settlementAndDeparture(boolean kick, boolean departureFirst) throws Exception {
        Fixture f = fixture();
        BudgetRequest request = approved(f, 2_000L);
        Runnable settlement = () -> changes.createBudgetChange(f.room().getId(), f.member().getId(), request.getId(),
                new BudgetChangeCreateRequest(1_500L, "정산"));
        Runnable departure = () -> depart(f, kick);

        Throwable secondFailure = runInOrder(f, departureFirst ? departure : settlement,
                departureFirst ? settlement : departure);

        assertThat(connections.findByUser_IdAndRoom_Id(f.member().getId(), f.room().getId())).isNull();
        if (departureFirst) {
            assertThat(secondFailure).isInstanceOf(BusinessException.class);
            assertThat(requestRepository.existsById(request.getId())).isFalse();
            assertThat(changeRepository.findAllByRoomIdOrderByCreatedAtDesc(f.room().getId())).isEmpty();
            assertBudget(f.room().getId(), 10_000L, 10_000L);
        } else {
            assertThat(secondFailure).isNull();
            assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                    .isEqualTo(BudgetRequestType.SETTLEMENT);
            assertThat(changeRepository.findAllByRoomIdOrderByCreatedAtDesc(f.room().getId())).hasSize(1);
            assertBudget(f.room().getId(), 8_500L, 8_500L);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("승인과 탈퇴·강퇴가 겹쳐도 신청과 예약 금액이 남지 않는다")
    void approvalAndDeparture(boolean kick, boolean departureFirst) throws Exception {
        Fixture f = fixture();
        BudgetRequest request = request(f.room(), f.member(), 2_000L);
        Runnable approval = () -> requests.approveBudgetRequest(f.room().getId(), f.owner(), request.getId());
        Runnable departure = () -> depart(f, kick);

        Throwable secondFailure = runInOrder(f, departureFirst ? departure : approval,
                departureFirst ? approval : departure);

        if (departureFirst) assertThat(secondFailure).isInstanceOf(BusinessException.class);
        else assertThat(secondFailure).isNull();
        assertThat(requestRepository.existsById(request.getId())).isFalse();
        assertBudget(f.room().getId(), 10_000L, 10_000L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("탈퇴·강퇴 뒤 대기 중이던 신청 등록은 거절되어 고아 신청이 생기지 않는다")
    void registrationAfterDeparture(boolean kick) throws Exception {
        Fixture f = fixture();
        Throwable failure = runInOrder(f, () -> depart(f, kick), () -> requests.budgetRequestRegistration(
                f.room().getId(), f.member(), "늦은 신청", 1_000L));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(requestRepository.findDtoByRoomId(f.room().getId())).isEmpty();
        assertBudget(f.room().getId(), 10_000L, 10_000L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("탈퇴와 강퇴가 동시에 실행되어도 승인 예약 금액은 한 번만 반환된다")
    void simultaneousDepartures(boolean kickFirst) throws Exception {
        Fixture f = fixture();
        approved(f, 2_000L);
        Throwable failure = runInOrder(f, () -> depart(f, kickFirst), () -> depart(f, !kickFirst));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(requestRepository.findDtoByRoomId(f.room().getId())).isEmpty();
        assertBudget(f.room().getId(), 10_000L, 10_000L);
    }

    /** 첫 작업이 변경을 마쳤지만 커밋하지 않은 상태에서 두 번째 작업의 실제 Room 락 대기를 확인한다. */
    private Throwable runInOrder(Fixture f, Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        Future<?> firstFuture = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(rooms.findByWithLock(f.room().getId())).isNotNull();
            first.run();
            firstChanged.countDown();
            try {
                if (!commitFirst.await(15, TimeUnit.SECONDS)) throw new AssertionError("커밋 신호 대기 시간 초과");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        try {
            assertThat(firstChanged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> secondFuture = executor.submit(() -> {
                try { second.run(); return null; }
                catch (BusinessException e) { return e; }
            });
            awaitRoomLockWait();
            assertThat(secondFuture.isDone()).isFalse();
            commitFirst.countDown();
            firstFuture.get(10, TimeUnit.SECONDS);
            return secondFuture.get(10, TimeUnit.SECONDS);
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitRoomLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l
                      ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'room' AND l.LOCK_STATUS = 'WAITING'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("MySQL Room 행 잠금 대기를 확인하지 못했습니다.");
    }
}
