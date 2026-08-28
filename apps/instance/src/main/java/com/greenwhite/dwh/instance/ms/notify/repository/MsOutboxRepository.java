package com.greenwhite.dwh.instance.ms.notify.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenwhite.dwh.instance.ms.notify.pref.MsNotifyPref;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MsOutboxRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MsOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public OutboxRecord enqueue(String channel, String recipient, String templateCode,
                                Map<String, Object> payload, UUID idempotencyKey) {
        String payloadJson = toJson(payload);

        return jdbcClient.sql("""
                insert into ms_notification_outbox (channel, recipient, template_code, payload,
                                                   status, attempts, max_attempts, next_attempt_at,
                                                   idempotency_key, created_at)
                values (:channel, :recipient, :templateCode, cast(:payload as jsonb),
                        :status, 0, 5, now(), :idempotencyKey, now())
                on conflict (idempotency_key) do nothing
                returning id, channel, recipient, template_code, payload::text as payload_str,
                          status, attempts, max_attempts, next_attempt_at, idempotency_key,
                          last_error, created_at, processed_at
                """)
                .param("channel", channel)
                .param("recipient", recipient)
                .param("templateCode", templateCode)
                .param("payload", payloadJson)
                .param("status", MsNotifyPref.OUTBOX_PENDING)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapRecord)
                .optional()
                .orElse(null);
    }

    public List<OutboxRecord> fetchPending(int limit) {
        return jdbcClient.sql("""
                select id, channel, recipient, template_code, payload::text as payload_str,
                       status, attempts, max_attempts, next_attempt_at, idempotency_key,
                       last_error, created_at, processed_at
                from ms_notification_outbox
                where status = 'PENDING' and next_attempt_at <= now()
                order by next_attempt_at asc
                limit :limit
                for update skip locked
                """)
                .param("limit", limit)
                .query(this::mapRecord)
                .list();
    }

    public void markSuccess(Long id) {
        jdbcClient.sql("""
                update ms_notification_outbox
                set status = 'SENT', processed_at = now()
                where id = :id
                """)
                .param("id", id)
                .update();
    }

    public void markFailed(Long id, int newAttempts, Instant nextAttemptAt, String error, boolean isDeadLetter) {
        String status = isDeadLetter ? MsNotifyPref.OUTBOX_DEAD_LETTER : MsNotifyPref.OUTBOX_PENDING;

        jdbcClient.sql("""
                update ms_notification_outbox
                set status = :status,
                    attempts = :attempts,
                    next_attempt_at = :nextAttemptAt,
                    last_error = :error,
                    processed_at = case when :isDeadLetter then now() else null end
                where id = :id
                """)
                .param("status", status)
                .param("attempts", newAttempts)
                .param("nextAttemptAt", nextAttemptAt != null ? java.sql.Timestamp.from(nextAttemptAt) : null)
                .param("error", error)
                .param("isDeadLetter", isDeadLetter)
                .param("id", id)
                .update();
    }

    private OutboxRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxRecord(
                rs.getLong("id"),
                rs.getString("channel"),
                rs.getString("recipient"),
                rs.getString("template_code"),
                parseJson(rs.getString("payload_str")),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                UUID.fromString(rs.getString("idempotency_key")),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("processed_at") != null ? rs.getTimestamp("processed_at").toInstant() : null
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
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

    public record OutboxRecord(
            Long id,
            String channel,
            String recipient,
            String templateCode,
            Map<String, Object> payload,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextAttemptAt,
            UUID idempotencyKey,
            String lastError,
            Instant createdAt,
            Instant processedAt
    ) {}
}
