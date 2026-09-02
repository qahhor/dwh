package com.greenwhite.dwh.instance.kwh.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        return jdbcClient.sql("""
                select o.id, o.subscription_id, o.event_type, o.payload::text as payload_str,
                       o.status, o.attempts, o.max_attempts, o.next_attempt_at, o.last_error,
                       o.last_http_status, o.created_at, o.processed_at,
                       s.target_url, s.secret_token
                from kwh_outbox o
                join kwh_subscriptions s on s.id = o.subscription_id
                where o.status = 'PENDING' and o.next_attempt_at <= now() and s.state = 'A'
                order by o.next_attempt_at asc
                limit :limit
                for update of o skip locked
                """)
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    public void markSuccess(Long id, int httpStatus) {
        jdbcClient.sql("""
                update kwh_outbox
                set status = 'SENT', last_http_status = :httpStatus, processed_at = now()
                where id = :id
                """)
                .param("id", id)
                .param("httpStatus", httpStatus)
                .update();
    }

    public void markFailed(Long id, int newAttempts, Instant nextAttemptAt, int httpStatus, String error, boolean isDeadLetter) {
        String status = isDeadLetter ? "DEAD_LETTER" : "PENDING";

        jdbcClient.sql("""
                update kwh_outbox
                set status = :status,
                    attempts = :attempts,
                    next_attempt_at = :nextAttemptAt,
                    last_http_status = :httpStatus,
                    last_error = :error,
                    processed_at = case when :isDeadLetter then now() else null end
                where id = :id
                """)
                .param("status", status)
                .param("attempts", newAttempts)
                .param("nextAttemptAt", nextAttemptAt != null ? java.sql.Timestamp.from(nextAttemptAt) : null)
                .param("httpStatus", httpStatus)
                .param("error", error)
                .param("isDeadLetter", isDeadLetter)
                .param("id", id)
                .update();
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
            String targetUrl,
            String secretToken
    ) {}
}
