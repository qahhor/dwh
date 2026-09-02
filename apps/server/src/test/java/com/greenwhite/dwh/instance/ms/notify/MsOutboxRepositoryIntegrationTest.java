package com.greenwhite.dwh.instance.ms.notify;

import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.ms.notify.repository.MsOutboxRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MsOutboxRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_notification_outbox_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MsOutboxRepository firstWorkerRepository;
    static MsOutboxRepository secondWorkerRepository;

    @BeforeAll
    static void setup() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        firstWorkerRepository = new MsOutboxRepository(jdbc, new ObjectMapper());
        secondWorkerRepository = new MsOutboxRepository(JdbcClient.create(dataSource), new ObjectMapper());
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.sql("delete from ms_notification_outbox").update();
    }

    @Test
    @DisplayName("Заявленная одним worker запись не должна выдаваться второму worker")
    void claimedItemIsNotReturnedToAnotherWorker() {
        firstWorkerRepository.enqueue(
                "email",
                "release@example.test",
                "release-ready",
                Map.of("subject", "Release", "body", "Ready"),
                UUID.randomUUID());

        var firstClaim = firstWorkerRepository.fetchPending(1);
        var secondClaim = secondWorkerRepository.fetchPending(1);

        assertThat(firstClaim).hasSize(1);
        assertThat(secondClaim)
                .as("вторая реплика не получает уже заявленную запись")
                .isEmpty();
        assertThat(jdbc.sql("select status from ms_notification_outbox")
                .query(String.class)
                .single())
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("Завершить доставку может только worker с актуальным claim token")
    void staleWorkerCannotFinalizeAnotherWorkersClaim() {
        firstWorkerRepository.enqueue(
                "email", "release@example.test", "release-ready",
                Map.of("subject", "Release"), UUID.randomUUID());

        var claim = firstWorkerRepository.fetchPending(1).getFirst();

        assertThat(firstWorkerRepository.markSuccess(claim.id(), UUID.randomUUID())).isFalse();
        assertThat(firstWorkerRepository.markSuccess(claim.id(), claim.claimToken())).isTrue();
    }

    @Test
    @DisplayName("Зависшая claim-запись возвращается в обработку с новым owner token")
    void staleClaimIsRecoveredWithANewOwnerToken() {
        firstWorkerRepository.enqueue(
                "email", "release@example.test", "release-ready",
                Map.of("subject", "Release"), UUID.randomUUID());
        var firstClaim = firstWorkerRepository.fetchPending(1).getFirst();
        jdbc.sql("""
                        update ms_notification_outbox
                        set claimed_at = now() - interval '6 minutes'
                        where id = :id
                        """)
                .param("id", firstClaim.id())
                .update();

        var recovered = secondWorkerRepository.fetchPending(1).getFirst();

        assertThat(recovered.id()).isEqualTo(firstClaim.id());
        assertThat(recovered.claimToken()).isNotEqualTo(firstClaim.claimToken());
    }
}
