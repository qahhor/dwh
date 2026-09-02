package com.greenwhite.dwh.instance.kwh.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KwhSubscriptionRepository {

    private final JdbcClient jdbcClient;

    public KwhSubscriptionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public SubscriptionRecord create(String name, String targetUrl, String secretToken, List<String> subscribedEvents, Long createdBy) {
        return jdbcClient.sql("""
                insert into kwh_subscriptions (name, target_url, secret_token, subscribed_events, state, created_at, created_by)
                values (:name, :targetUrl, :secretToken, :events, 'A', now(), :createdBy)
                returning id, name, target_url, secret_token, subscribed_events, state, created_at, created_by
                """)
                .param("name", name)
                .param("targetUrl", targetUrl)
                .param("secretToken", secretToken)
                .param("events", subscribedEvents.toArray(new String[0]))
                .param("createdBy", createdBy)
                .query(this::mapRecord)
                .single();
    }

    public Optional<SubscriptionRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, name, target_url, secret_token, subscribed_events, state, created_at, created_by
                from kwh_subscriptions
                where id = :id
                """)
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    public List<SubscriptionRecord> listSubscriptions() {
        return jdbcClient.sql("""
                select id, name, target_url, secret_token, subscribed_events, state, created_at, created_by
                from kwh_subscriptions
                order by created_at desc
                """)
                .query(this::mapRecord)
                .list();
    }

    public List<SubscriptionRecord> findActiveByEvent(String eventType) {
        return jdbcClient.sql("""
                select id, name, target_url, secret_token, subscribed_events, state, created_at, created_by
                from kwh_subscriptions
                where state = 'A' and :eventType = any(subscribed_events)
                """)
                .param("eventType", eventType)
                .query(this::mapRecord)
                .list();
    }

    public void update(Long id, String name, String targetUrl, List<String> subscribedEvents, String state) {
        jdbcClient.sql("""
                update kwh_subscriptions
                set name = coalesce(:name, name),
                    target_url = coalesce(:targetUrl, target_url),
                    subscribed_events = coalesce(:events, subscribed_events),
                    state = coalesce(:state, state)
                where id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("targetUrl", targetUrl)
                .param("events", subscribedEvents != null ? subscribedEvents.toArray(new String[0]) : null)
                .param("state", state)
                .update();
    }

    public void delete(Long id) {
        jdbcClient.sql("delete from kwh_subscriptions where id = :id").param("id", id).update();
    }

    private SubscriptionRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] arr = (String[]) rs.getArray("subscribed_events").getArray();
        List<String> events = arr != null ? List.of(arr) : List.of();

        return new SubscriptionRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("target_url"),
                rs.getString("secret_token"),
                events,
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null
        );
    }

    public record SubscriptionRecord(
            Long id,
            String name,
            String targetUrl,
            String secretToken,
            List<String> subscribedEvents,
            String state,
            Instant createdAt,
            Long createdBy
    ) {}
}
