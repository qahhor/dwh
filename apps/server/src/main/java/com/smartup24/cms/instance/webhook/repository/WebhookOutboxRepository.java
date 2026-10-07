package com.smartup24.cms.instance.webhook.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.metrics.Backlog;
import com.smartup24.cms.instance.common.security.StoredSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class WebhookOutboxRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;
    private final StoredSecrets secrets;

    public WebhookOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper, StoredSecrets secrets) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "kwh_outbox");
        this.secrets = secrets;
    }

    public void enqueue(Long subscriptionId, String eventType, Map<String, Object> payload) {
        String payloadJson = jsonColumns.object(payload);

        jdbcClient
                .sql("""
                insert into kwh_outbox (subscription_id, event_type, payload, status, attempts, max_attempts, next_attempt_at, created_at)
                values (:subscriptionId, :eventType, cast(:payload as jsonb), 'PENDING', 0, 5, now(), now())
                """)
                .param("subscriptionId", subscriptionId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }

    public List<OutboxRecord> fetchPending(int limit) {
        UUID claimToken = UUID.randomUUID();
        return jdbcClient
                .sql("""
                with candidates as (
                    select outbox.id
                    from kwh_outbox as outbox
                    join kwh_subscriptions as subscription on subscription.id = outbox.subscription_id
                    where subscription.state = 'A'
                      and ((outbox.status = 'PENDING' and outbox.next_attempt_at <= now())
                        or (outbox.status = 'PROCESSING'
                            and outbox.claimed_at <= now() - interval '5 minutes'))
                    order by coalesce(outbox.claimed_at, outbox.next_attempt_at) asc
                    limit :limit
                    for update of outbox skip locked
                ), claimed as (
                    update kwh_outbox as outbox
                    set status = 'PROCESSING',
                        claim_token = :claimToken,
                        claimed_at = now()
                    from candidates
                    where outbox.id = candidates.id
                    returning outbox.*
                )
                select claimed.id, claimed.subscription_id, claimed.event_type,
                       claimed.payload::text as payload_str, claimed.status, claimed.attempts,
                       claimed.max_attempts, claimed.next_attempt_at, claimed.last_error,
                       claimed.last_http_status, claimed.created_at, claimed.processed_at,
                       claimed.claim_token, claimed.claimed_at,
                       subscription.target_url, subscription.secret_token
                from claimed
                join kwh_subscriptions as subscription on subscription.id = claimed.subscription_id
                """)
                .param("limit", limit)
                .param("claimToken", claimToken)
                .query(this::mapRecord)
                .list();
    }

    public boolean markSuccess(Long id, UUID claimToken, int httpStatus) {
        return jdbcClient
                        .sql("""
                update kwh_outbox
                set status = 'SENT', last_http_status = :httpStatus, processed_at = now(),
                    claim_token = null, claimed_at = null
                where id = :id
                  and status = 'PROCESSING'
                  and claim_token = :claimToken
                """)
                        .param("id", id)
                        .param("httpStatus", httpStatus)
                        .param("claimToken", claimToken)
                        .update()
                == 1;
    }

    public boolean markFailed(
            Long id,
            UUID claimToken,
            int newAttempts,
            Instant nextAttemptAt,
            int httpStatus,
            String error,
            boolean isDeadLetter) {
        String status = isDeadLetter ? "DEAD_LETTER" : "PENDING";

        return jdbcClient
                        .sql("""
                update kwh_outbox
                set status = :status,
                    attempts = :attempts,
                    next_attempt_at = :nextAttemptAt,
                    last_http_status = :httpStatus,
                    last_error = :error,
                    processed_at = case when :isDeadLetter then now() else null end,
                    claim_token = null,
                    claimed_at = null
                where id = :id
                  and status = 'PROCESSING'
                  and claim_token = :claimToken
                """)
                        .param("status", status)
                        .param("attempts", newAttempts)
                        .param("nextAttemptAt", nextAttemptAt != null ? java.sql.Timestamp.from(nextAttemptAt) : null)
                        .param("httpStatus", httpStatus)
                        .param("error", error)
                        .param("isDeadLetter", isDeadLetter)
                        .param("id", id)
                        .param("claimToken", claimToken)
                        .update()
                == 1;
    }

    /**
     * The deliveries due now and the wait of the oldest past its due time (plan 10/10, item 7.3). Items of a paused
     * subscription are not counted: the worker does not deliver them, by design.
     */
    public Backlog backlog() {
        return jdbcClient
                .sql("""
                select count(*) as pending,
                       coalesce(extract(epoch from now() - min(outbox.next_attempt_at)), 0) as lag
                from kwh_outbox as outbox
                join kwh_subscriptions as subscription on subscription.id = outbox.subscription_id
                where subscription.state = 'A'
                  and outbox.status = 'PENDING'
                  and outbox.next_attempt_at <= now()
                """)
                .query((rs, row) -> new Backlog(rs.getLong("pending"), rs.getDouble("lag")))
                .single();
    }

    public void recordLog(Long subscriptionId, String eventType, int httpStatus, int durationMs, boolean isSuccess) {
        jdbcClient
                .sql("""
                insert into kwh_logs (subscription_id, event_type, http_status, duration_ms, is_success, sent_at)
                values (:subscriptionId, :eventType, :httpStatus, :durationMs, :isSuccess, now())
                """)
                .param("subscriptionId", subscriptionId)
                .param("eventType", eventType)
                .param("httpStatus", httpStatus)
                .param("durationMs", durationMs)
                .param("isSuccess", isSuccess)
                .update();
    }

    private OutboxRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxRecord(
                rs.getLong("id"),
                rs.getLong("subscription_id"),
                rs.getString("event_type"),
                jsonColumns.readObject(rs.getString("payload_str")),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getString("last_error"),
                rs.getObject("last_http_status") != null ? rs.getInt("last_http_status") : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("processed_at") != null
                        ? rs.getTimestamp("processed_at").toInstant()
                        : null,
                rs.getObject("claim_token", UUID.class),
                rs.getTimestamp("claimed_at") != null
                        ? rs.getTimestamp("claimed_at").toInstant()
                        : null,
                rs.getString("target_url"),
                secrets.open(rs.getString("secret_token"), WebhookSubscriptionRepository.SECRET_COLUMN));
    }

    public record OutboxRecord(
            Long id,
            Long subscriptionId,
            String eventType,
            Map<String, Object> payload,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastError,
            Integer lastHttpStatus,
            Instant createdAt,
            Instant processedAt,
            UUID claimToken,
            Instant claimedAt,
            String targetUrl,
            String secretToken) {}
}
