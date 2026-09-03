package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MfFileQuotaLockIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("file_quota_lock_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    @Test
    void concurrentQuotaWriterWaitsUntilTheFirstTransactionCommits() throws Exception {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var firstRepository = new MfFileRepository(JdbcClient.create(dataSource));
        var secondRepository = new MfFileRepository(JdbcClient.create(dataSource));
        var firstTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var secondTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondHasLock = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> firstTransaction.executeWithoutResult(status -> {
                firstRepository.lockQuotaBudget();
                firstHasLock.countDown();
                await(releaseFirst);
            }));
            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> secondTransaction.executeWithoutResult(status -> {
                secondRepository.lockQuotaBudget();
                secondHasLock.countDown();
            }));
            assertThat(secondHasLock.await(200, TimeUnit.MILLISECONDS))
                    .as("quota writers must not observe the same unlocked usage snapshot")
                    .isFalse();

            releaseFirst.countDown();
            assertThat(secondHasLock.await(5, TimeUnit.SECONDS)).isTrue();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while coordinating advisory-lock test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating advisory-lock test", exception);
        }
    }
}
