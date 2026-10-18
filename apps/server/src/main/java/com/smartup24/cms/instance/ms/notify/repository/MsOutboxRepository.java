package com.smartup24.cms.instance.ms.notify.repository;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.metrics.Backlog;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MsOutboxRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final JsonColumns jsonColumns;

    public MsOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.jsonColumns = new JsonColumns(objectMapper, "ms_outbox");
    }

    public OutboxRecord enqueue(
            String channel, String recipient, String templateCode, Map<String, Object> payload, UUID idempotencyKey) {
        return enqueue(channel, recipient, templateCode, payload, idempotencyKey, null);
    }

    /**
     * Enqueues a delivery, with one attachment for a letter (ADR-0045); a second call with the same idempotency key
     * enqueues nothing and answers null.
     */
    public OutboxRecord enqueue(
            String channel,
            String recipient,
            String templateCode,
            Map<String, Object> payload,
            UUID idempotencyKey,
            @Nullable Attachment attachment) {
        String payloadJson = jsonColumns.object(payload);

        return jdbcClient
                .sql("""
                insert into ms_notification_outbox (channel, recipient, template_code, payload,
                                                   status, attempts, max_attempts, next_attempt_at,
                                                   idempotency_key, created_at,
                                                   attachment_name, attachment_type, attachment)
                values (:channel, :recipient, :templateCode, cast(:payload as jsonb),
                        :status, 0, 5, now(), :idempotencyKey, now(),
                        :attachmentName, :attachmentType, :attachment)
                on conflict (idempotency_key) do nothing
                returning id, channel, recipient, template_code, payload::text as payload_str,
                          status, attempts, max_attempts, next_attempt_at, idempotency_key,
                          last_error, created_at, processed_at, claim_token, claimed_at
                """)
                .param("channel", channel)
                .param("recipient", recipient)
                .param("templateCode", templateCode)
                .param("payload", payloadJson)
                .param("status", MsNotifyPref.OUTBOX_PENDING)
                .param("idempotencyKey", idempotencyKey)
                .param("attachmentName", attachment == null ? null : attachment.name(), Types.VARCHAR)
                .param("attachmentType", attachment == null ? null : attachment.type(), Types.VARCHAR)
                .param("attachment", attachment == null ? null : attachment.content(), Types.BINARY)
                .query(this::mapRecord)
                .optional()
                .orElse(null);
    }

    /** The attachment of a delivery while it waits; none once the delivery is sent or given up (ADR-0045). */
    public Optional<Attachment> attachment(long id) {
        return jdbcClient
                .sql("""
                select attachment_name, attachment_type, attachment
                from ms_notification_outbox
                where id = :id and attachment is not null
                """)
                .param("id", id)
                .query((rs, row) -> new Attachment(
                        rs.getString("attachment_name"), rs.getString("attachment_type"), rs.getBytes("attachment")))
                .optional();
    }

    /** The states of the deliveries with these idempotency keys; a key no longer kept is absent. */
    public Map<UUID, String> states(Collection<UUID> keys) {
        Map<UUID, String> states = new HashMap<>();
        if (keys.isEmpty()) {
            return states;
        }
        jdbcClient
                .sql("select idempotency_key, status from ms_notification_outbox where idempotency_key in (:keys)")
                .param("keys", List.copyOf(keys))
                .query((rs, row) -> states.put(rs.getObject("idempotency_key", UUID.class), rs.getString("status")))
                .list();
        return states;
    }

    public List<OutboxRecord> fetchPending(int limit) {
        UUID claimToken = UUID.randomUUID();
        return jdbcClient
                .sql("""
                with candidates as (
                    select id
                    from ms_notification_outbox
                    where (status = 'PENDING' and next_attempt_at <= now())
                       or (status = 'PROCESSING' and claimed_at <= now() - interval '5 minutes')
                    order by coalesce(claimed_at, next_attempt_at) asc
                    limit :limit
                    for update skip locked
                )
                update ms_notification_outbox as outbox
                set status = 'PROCESSING',
                    claim_token = :claimToken,
                    claimed_at = now()
                from candidates
                where outbox.id = candidates.id
                returning outbox.id, outbox.channel, outbox.recipient, outbox.template_code,
                          outbox.payload::text as payload_str, outbox.status, outbox.attempts,
                          outbox.max_attempts, outbox.next_attempt_at, outbox.idempotency_key,
                          outbox.last_error, outbox.created_at, outbox.processed_at,
                          outbox.claim_token, outbox.claimed_at
                """)
                .param("limit", limit)
                .param("claimToken", claimToken)
                .query(this::mapRecord)
                .list();
    }

    /** The notifications due now and the wait of the oldest past its due time (plan 10/10, item 7.3). */
    public Backlog backlog() {
        return jdbcClient
                .sql("""
                select count(*) as pending, coalesce(extract(epoch from now() - min(next_attempt_at)), 0) as lag
                from ms_notification_outbox
                where status = 'PENDING' and next_attempt_at <= now()
                """)
                .query((rs, row) -> new Backlog(rs.getLong("pending"), rs.getDouble("lag")))
                .single();
    }

    public boolean markSuccess(Long id, UUID claimToken) {
        return jdbcClient
                        .sql("""
                update ms_notification_outbox
                set status = 'SENT', processed_at = now(), claim_token = null, claimed_at = null,
                    attachment = null, attachment_name = null, attachment_type = null
                where id = :id
                  and status = 'PROCESSING'
                  and claim_token = :claimToken
                """)
                        .param("id", id)
                        .param("claimToken", claimToken)
                        .update()
                == 1;
    }

    public boolean markFailed(
            Long id, UUID claimToken, int newAttempts, Instant nextAttemptAt, String error, boolean isDeadLetter) {
        String status = isDeadLetter ? MsNotifyPref.OUTBOX_DEAD_LETTER : MsNotifyPref.OUTBOX_PENDING;

        return jdbcClient
                        .sql("""
                update ms_notification_outbox
                set status = :status,
                    attempts = :attempts,
                    next_attempt_at = :nextAttemptAt,
                    last_error = :error,
                    processed_at = case when :isDeadLetter then now() else null end,
                    claim_token = null,
                    claimed_at = null,
                    attachment = case when :isDeadLetter then null else attachment end,
                    attachment_name = case when :isDeadLetter then null else attachment_name end,
                    attachment_type = case when :isDeadLetter then null else attachment_type end
                where id = :id
                  and status = 'PROCESSING'
                  and claim_token = :claimToken
                """)
                        .param("status", status)
                        .param("attempts", newAttempts)
                        .param("nextAttemptAt", nextAttemptAt != null ? java.sql.Timestamp.from(nextAttemptAt) : null)
                        .param("error", error)
                        .param("isDeadLetter", isDeadLetter)
                        .param("id", id)
                        .param("claimToken", claimToken)
                        .update()
                == 1;
    }

    private OutboxRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OutboxRecord(
                rs.getLong("id"),
                rs.getString("channel"),
                rs.getString("recipient"),
                rs.getString("template_code"),
                jsonColumns.readObject(rs.getString("payload_str")),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                UUID.fromString(rs.getString("idempotency_key")),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("processed_at") != null
                        ? rs.getTimestamp("processed_at").toInstant()
                        : null,
                rs.getObject("claim_token", UUID.class),
                rs.getTimestamp("claimed_at") != null
                        ? rs.getTimestamp("claimed_at").toInstant()
                        : null);
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
            Instant processedAt,
            UUID claimToken,
            Instant claimedAt) {}

    /** One file a letter carries (ADR-0045). */
    public record Attachment(String name, String type, byte[] content) {}
}
