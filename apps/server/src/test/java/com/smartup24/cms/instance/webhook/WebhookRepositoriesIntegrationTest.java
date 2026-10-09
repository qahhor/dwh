package com.smartup24.cms.instance.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.instance.support.TestStoredSecrets;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook tables on a migrated database: subscriptions keep their signing key sealed and change only at the
 * expected revision; an outbox item is retried, then becomes a dead letter; the backlog counts only what is due.
 */
class WebhookRepositoriesIntegrationTest {

    private JdbcClient jdbc;
    private WebhookSubscriptionRepository subscriptions;
    private WebhookOutboxRepository outbox;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("smc_webhook_repos_" + System.nanoTime()));
        subscriptions = new WebhookSubscriptionRepository(jdbc, TestStoredSecrets.secrets());
        outbox = new WebhookOutboxRepository(jdbc, new ObjectMapper(), TestStoredSecrets.secrets());
    }

    @Test
    @DisplayName("A subscription keeps its signing key sealed at rest and opens it on read")
    void signingKeyIsSealedAtRest() {
        var created =
                subscriptions.create("Orders", "https://hooks.example/a", "plain-key", List.of("x.created"), null);

        String stored = jdbc.sql("select secret_token from kwh_subscriptions where id = :id")
                .param("id", created.id())
                .query(String.class)
                .single();
        assertThat(stored).startsWith("v1:").doesNotContain("plain-key");
        assertThat(subscriptions.findById(created.id()).orElseThrow().secretToken())
                .isEqualTo("plain-key");
        assertThat(subscriptions.anySealedSecret()).contains(stored);
        assertThat(subscriptions.secretColumn()).isEqualTo(WebhookSubscriptionRepository.SECRET_COLUMN);
    }

    @Test
    @DisplayName("A key stored in the clear is sealed once, and its row moves to the next revision")
    void plainKeysAreSealed() {
        Long id = jdbc.sql("""
                        insert into kwh_subscriptions (name, target_url, secret_token, subscribed_events, state)
                        values ('Legacy', 'https://hooks.example/l', 'clear-key', array['*'], 'A')
                        returning id
                        """).query(Long.class).single();
        long revision = subscriptions.findById(id).orElseThrow().revision();

        assertThat(subscriptions.sealPlainSecrets(TestStoredSecrets.secrets())).isEqualTo(1);
        assertThat(subscriptions.sealPlainSecrets(TestStoredSecrets.secrets())).isZero();

        var sealed = subscriptions.findById(id).orElseThrow();
        assertThat(sealed.secretToken()).isEqualTo("clear-key");
        assertThat(sealed.revision()).isEqualTo(revision + 1);
    }

    @Test
    @DisplayName("Subscribers of an event: its own code or the wildcard, active only")
    void activeSubscribersOfAnEvent() {
        var own = subscriptions.create("Own", "https://hooks.example/o", "k1", List.of("x.created"), null);
        var all = subscriptions.create("All", "https://hooks.example/w", "k2", List.of("*"), null);
        var paused = subscriptions.create("Paused", "https://hooks.example/p", "k3", List.of("x.created"), null);
        subscriptions.update(paused.id(), null, null, null, "P", paused.revision());

        assertThat(subscriptions.findActiveByEvent("x.created"))
                .extracting(WebhookSubscriptionRepository.SubscriptionRecord::id)
                .containsExactlyInAnyOrder(own.id(), all.id());
        assertThat(subscriptions.findActiveByEvent("y.deleted"))
                .extracting(WebhookSubscriptionRepository.SubscriptionRecord::id)
                .containsExactly(all.id());
        assertThat(subscriptions.listSubscriptions()).hasSize(3);
    }

    @Test
    @DisplayName("An update applies at the expected revision only; a stale one is a conflict; delete removes it")
    void updateAtTheExpectedRevision() {
        var created = subscriptions.create("Orders", "https://hooks.example/a", "k", List.of("*"), null);

        long next = subscriptions.update(
                created.id(), "Renamed", "https://hooks.example/b", List.of("x.created"), null, created.revision());

        var updated = subscriptions.findById(created.id()).orElseThrow();
        assertThat(next).isEqualTo(created.revision() + 1);
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.targetUrl()).isEqualTo("https://hooks.example/b");
        assertThat(updated.subscribedEvents()).containsExactly("x.created");
        assertThat(updated.state()).isEqualTo("A");
        assertThatThrownBy(() -> subscriptions.update(created.id(), "Again", null, null, null, created.revision()))
                .isInstanceOf(ApiException.class);

        subscriptions.delete(created.id());
        assertThat(subscriptions.findById(created.id())).isEmpty();
    }

    @Test
    @DisplayName("A failed delivery waits for its next attempt; the last failed attempt makes a dead letter")
    void retryThenDeadLetter() {
        var subscription = subscriptions.create("Orders", "https://hooks.example/a", "k", List.of("*"), null);
        outbox.enqueue(subscription.id(), "x.created", Map.of("id", 1));

        var claimed = outbox.fetchPending(10).getFirst();
        assertThat(claimed.payload()).containsEntry("id", 1);
        assertThat(claimed.secretToken()).isEqualTo("k");
        assertThat(outbox.markFailed(
                        claimed.id(), claimed.claimToken(), 1, Instant.now().minusSeconds(1), 503, "boom", false))
                .isTrue();
        assertThat(row(claimed.id()))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("last_http_status", 503)
                .containsEntry("last_error", "boom")
                .containsEntry("processed", false);

        var again = outbox.fetchPending(10).getFirst();
        assertThat(outbox.markFailed(again.id(), again.claimToken(), 5, Instant.now(), 0, "gone", true))
                .isTrue();
        assertThat(row(again.id()))
                .containsEntry("status", "DEAD_LETTER")
                .containsEntry("attempts", 5)
                .containsEntry("processed", true);
        assertThat(outbox.fetchPending(10)).isEmpty();
        assertThat(outbox.markSuccess(again.id(), again.claimToken(), 200))
                .as("a dead letter is final")
                .isFalse();
    }

    @Test
    @DisplayName("The backlog counts due items of active subscriptions, and the delivery log keeps each attempt")
    void backlogAndDeliveryLog() {
        var active = subscriptions.create("Active", "https://hooks.example/a", "k", List.of("*"), null);
        var paused = subscriptions.create("Paused", "https://hooks.example/p", "k", List.of("*"), null);
        outbox.enqueue(active.id(), "x.created", Map.of("id", 1));
        outbox.enqueue(active.id(), "x.updated", Map.of("id", 1));
        outbox.enqueue(paused.id(), "x.created", Map.of("id", 2));
        subscriptions.update(paused.id(), null, null, null, "P", paused.revision());

        var backlog = outbox.backlog();
        assertThat(backlog.pending()).isEqualTo(2);
        assertThat(backlog.lagSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(outbox.fetchPending(10))
                .extracting(WebhookOutboxRepository.OutboxRecord::subscriptionId)
                .containsOnly(active.id());

        outbox.recordLog(active.id(), "x.created", 204, 12, true);
        outbox.recordLog(active.id(), "x.updated", 0, 3000, false);
        assertThat(jdbc.sql("select count(*) from kwh_logs where subscription_id = :id and is_success")
                        .param("id", active.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("""
                        select status, attempts, last_http_status, last_error, processed_at is not null as processed
                        from kwh_outbox where id = :id
                        """).param("id", id).query().singleRow();
    }
}
