package com.greenwhite.dwh.instance.kwh.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class KwhOutboxRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public KwhOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void enqueue(Long subscriptionId, String eventType, Map<String, Object> payload) {
        String payloadJson = toJson(payload);

        jdbcClient.sql("""
                insert into kwh_outbox (subscription_id, event_type, payload, status, attempts, max_attempts, next_attempt_at, created_at)
                values (:subscriptionId, :eventType, cast(:payload as jsonb), 'PENDING', 0, 5, now(), now())
                """)
                .param("subscriptionId", subscriptionId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }

    public List<KwhOutboxRecord> fetchPending(int limit) {
        UUID claimToken = UUID.randomUUID();
        return jdbcClient.sql("""
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
        return jdbcClient.sql("""
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
                .update() == 1;
    }

    public boolean markFailed(Long id, UUID claimToken, int newAttempts, Instant nextAttemptAt,
                              int httpStatus, String error, boolean isDeadLetter) {
        String status = isDeadLetter ? "DEAD_LETTER" : "PENDING";

        return jdbcClient.sql("""
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
                .update() == 1;
    }

    public void recordLog(Long subscriptionId, String eventType, int httpStatus, int durationMs, boolean isSuccess) {
        jdbcClient.sql("""
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

    private KwhOutboxRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new KwhOutboxRecord(
                rs.getLong("id"),
                rs.getLong("subscription_id"),
                rs.getString("event_type"),
                parseJson(rs.getString("payload_str")),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getString("last_error"),
                rs.getObject("last_http_status") != null ? rs.getInt("last_http_status") : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("processed_at") != null ? rs.getTimestamp("processed_at").toInstant() : null,
                rs.getObject("claim_token", UUID.class),
                rs.getTimestamp("claimed_at") != null ? rs.getTimestamp("claimed_at").toInstant() : null,
                rs.getString("target_url"),
                rs.getString("secret_token")
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record KwhOutboxRecord(
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
            String secretToken
    ) {}
}
