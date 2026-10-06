package com.smartup24.cms.instance.webhook.repository;

import com.smartup24.cms.instance.common.security.StoredSecretColumn;
import com.smartup24.cms.instance.common.security.StoredSecrets;
import com.smartup24.cms.instance.common.web.Revisions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookSubscriptionRepository implements StoredSecretColumn {

    /** The signing key of a subscription is encrypted at rest (ADR-0029); this is its encryption context. */
    public static final String SECRET_COLUMN = "kwh_subscriptions.secret_token";

    private final JdbcClient jdbcClient;
    private final StoredSecrets secrets;

    public WebhookSubscriptionRepository(JdbcClient jdbcClient, StoredSecrets secrets) {
        this.jdbcClient = jdbcClient;
        this.secrets = secrets;
    }

    public SubscriptionRecord create(
            String name, String targetUrl, String secretToken, List<String> subscribedEvents, Long createdBy) {
        return jdbcClient
                .sql("""
                insert into kwh_subscriptions (name, target_url, secret_token, subscribed_events, state, created_at, created_by)
                values (:name, :targetUrl, :secretToken, :events, 'A', now(), :createdBy)
                returning id, name, target_url, secret_token, subscribed_events, state, created_at, created_by, revision
                """)
                .param("name", name)
                .param("targetUrl", targetUrl)
                .param("secretToken", secrets.seal(secretToken, SECRET_COLUMN))
                .param("events", subscribedEvents.toArray(new String[0]))
                .param("createdBy", createdBy)
                .query(this::mapRecord)
                .single();
    }

    public Optional<SubscriptionRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, name, target_url, secret_token, subscribed_events, state, created_at, created_by, revision
                from kwh_subscriptions
                where id = :id
                """).param("id", id).query(this::mapRecord).optional();
    }

    public List<SubscriptionRecord> listSubscriptions() {
        return jdbcClient.sql("""
                select id, name, target_url, secret_token, subscribed_events, state, created_at, created_by, revision
                from kwh_subscriptions
                order by created_at desc
                """).query(this::mapRecord).list();
    }

    public List<SubscriptionRecord> findActiveByEvent(String eventType) {
        return jdbcClient
                .sql("""
                select id, name, target_url, secret_token, subscribed_events, state, created_at, created_by, revision
                from kwh_subscriptions
                where state = 'A' and (:eventType = any(subscribed_events) or '*' = any(subscribed_events))
                """)
                .param("eventType", eventType)
                .query(this::mapRecord)
                .list();
    }

    public long update(
            Long id,
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String state,
            long expectedRevision) {
        return jdbcClient
                .sql("""
                update kwh_subscriptions
                set name = coalesce(:name, name),
                    target_url = coalesce(:targetUrl, target_url),
                    subscribed_events = coalesce(:events, subscribed_events),
                    state = coalesce(:state, state),
                    revision = revision + 1
                where id = :id and revision = :expectedRevision
                returning revision
                """)
                .param("id", id)
                .param("name", name)
                .param("targetUrl", targetUrl)
                .param("events", subscribedEvents != null ? subscribedEvents.toArray(new String[0]) : null)
                .param("state", state)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public void delete(Long id) {
        jdbcClient
                .sql("delete from kwh_subscriptions where id = :id")
                .param("id", id)
                .update();
    }

    @Override
    public String secretColumn() {
        return SECRET_COLUMN;
    }

    @Override
    public Optional<String> anySealedSecret() {
        return jdbcClient
                .sql("select secret_token from kwh_subscriptions where secret_token like 'v1:%' limit 1")
                .query(String.class)
                .optional();
    }

    @Override
    public int sealPlainSecrets(StoredSecrets cipher) {
        List<PlainSecret> plain = jdbcClient
                .sql("select id, secret_token from kwh_subscriptions where secret_token not like 'v1:%'")
                .query((rs, rowNum) -> new PlainSecret(rs.getLong("id"), rs.getString("secret_token")))
                .list();
        int sealed = 0;
        for (PlainSecret row : plain) {
            // The stored form changes, so the revision moves on as for any write of the row (ADR-0024).
            sealed += jdbcClient
                    .sql("""
                    update kwh_subscriptions
                    set secret_token = :sealed,
                        revision = revision + 1
                    where id = :id and secret_token = :plain
                    """)
                    .param("sealed", cipher.seal(row.value(), SECRET_COLUMN))
                    .param("id", row.id())
                    .param("plain", row.value())
                    .update();
        }
        return sealed;
    }

    private record PlainSecret(long id, String value) {}

    private SubscriptionRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] arr = (String[]) rs.getArray("subscribed_events").getArray();
        List<String> events = arr != null ? List.of(arr) : List.of();

        return new SubscriptionRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("target_url"),
                secrets.open(rs.getString("secret_token"), SECRET_COLUMN),
                events,
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null,
                rs.getLong("revision"));
    }

    public record SubscriptionRecord(
            Long id,
            String name,
            String targetUrl,
            String secretToken,
            List<String> subscribedEvents,
            String state,
            Instant createdAt,
            Long createdBy,
            long revision) {}
}
