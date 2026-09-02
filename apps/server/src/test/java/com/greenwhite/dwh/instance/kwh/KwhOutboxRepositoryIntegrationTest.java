package com.greenwhite.dwh.instance.kwh;

import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.kwh.repository.KwhOutboxRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class KwhOutboxRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_webhook_outbox_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static KwhOutboxRepository firstWorkerRepository;
    static KwhOutboxRepository secondWorkerRepository;
    static Long subscriptionId;

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
        subscriptionId = jdbc.sql("""
                        insert into kwh_subscriptions
                            (name, target_url, secret_token, subscribed_events, state)
                        values
                            ('release-hook', 'https://example.test/hooks/release',
                             'test-secret', array['release.ready'], 'A')
                        returning id
                        """)
                .query(Long.class)
                .single();
        firstWorkerRepository = new KwhOutboxRepository(jdbc, new ObjectMapper());
        secondWorkerRepository = new KwhOutboxRepository(JdbcClient.create(dataSource), new ObjectMapper());
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.sql("delete from kwh_outbox").update();
    }

    @Test
    @DisplayName("Webhook, заявленный одним worker, не должен выдаваться второму worker")
    void claimedWebhookIsNotReturnedToAnotherWorker() {
        firstWorkerRepository.enqueue(subscriptionId, "release.ready", Map.of("id", 42));

        var firstClaim = firstWorkerRepository.fetchPending(1);
        var secondClaim = secondWorkerRepository.fetchPending(1);

        assertThat(firstClaim).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("PROCESSING");
            assertThat(item.claimToken()).isNotNull();
            assertThat(item.claimedAt()).isNotNull();
        });
        assertThat(secondClaim)
                .as("вторая реплика не получает уже заявленный webhook")
                .isEmpty();
    }

    @Test
    @DisplayName("Завершить webhook может только worker с актуальным claim token")
    void staleWorkerCannotFinalizeAnotherWorkersClaim() {
        firstWorkerRepository.enqueue(subscriptionId, "release.ready", Map.of("id", 42));
        var claim = firstWorkerRepository.fetchPending(1).getFirst();

        assertThat(firstWorkerRepository.markSuccess(claim.id(), java.util.UUID.randomUUID(), 200)).isFalse();
        assertThat(firstWorkerRepository.markSuccess(claim.id(), claim.claimToken(), 200)).isTrue();
    }

    @Test
    @DisplayName("Зависший webhook возвращается в обработку с новым owner token")
    void staleClaimIsRecoveredWithANewOwnerToken() {
        firstWorkerRepository.enqueue(subscriptionId, "release.ready", Map.of("id", 42));
        var firstClaim = firstWorkerRepository.fetchPending(1).getFirst();
        jdbc.sql("""
                        update kwh_outbox
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
