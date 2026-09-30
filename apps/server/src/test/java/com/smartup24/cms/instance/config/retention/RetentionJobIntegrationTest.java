package com.smartup24.cms.instance.config.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.retention.RetentionPolicy;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 3.13, acceptance: every journal loses its rows past their retention and keeps the recent ones and
 * those still in flight; run after run the tables do not grow.
 */
class RetentionJobIntegrationTest extends EmbeddedPostgresTest {

    /** Older than the longest default retention (365 days). */
    private static final String OLD = "now() - interval '400 days'";

    private static final String RECENT = "now() - interval '1 day'";

    @Autowired
    private RetentionJob retention;

    @Autowired
    private List<RetentionPolicy> policies;

    @Autowired
    private JdbcClient jdbc;

    private long user;
    private long subscription;

    @BeforeEach
    void fixtures() {
        user = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        subscription = jdbc.sql("""
                        insert into kwh_subscriptions (name, target_url, secret_token, subscribed_events, state, created_at)
                        values ('Retention probe', 'https://hooks.example.invalid/r', 'secret', array['task.created'], 'A', now())
                        returning id
                        """).query(Long.class).single();
        jdbc.sql("delete from kauth_login_attempts where login like 'retention-%'")
                .update();
    }

    @Test
    @DisplayName("3.13: ten journals are declared, each with a positive default retention")
    void journalsAreDeclared() {
        assertThat(policies)
                .extracting(RetentionPolicy::name)
                .containsExactlyInAnyOrder(
                        "security-events",
                        "login-attempts",
                        "otp-codes",
                        "password-reset-codes",
                        "closed-sessions",
                        "webhook-logs",
                        "webhook-outbox",
                        "inbox",
                        "notification-outbox",
                        "job-runs");
        assertThat(policies)
                .allSatisfy(policy -> assertThat(policy.defaultDays()).isPositive());
    }

    @Test
    @DisplayName("3.13: rows past their retention go, recent and in-flight rows stay")
    void oldRowsGoRecentAndPendingStay() {
        String marker = "retention-" + UUID.randomUUID().toString().substring(0, 8);
        for (String at : List.of(OLD, RECENT)) {
            insertJournals(marker, at);
        }
        // In flight however old: a pending delivery, an open session, a running job.
        insertOutbox("PENDING", OLD, marker + "-pending");
        jdbc.sql(
                        "insert into kauth_sessions (user_id, token_hash, ip, user_agent, created_at, last_seen_at, auth_version)"
                                + " values (:u, :h, '127.0.0.1', 'probe', " + OLD + ", " + OLD + ", 1)")
                .param("u", user)
                .param("h", marker + "-open")
                .update();

        Map<String, Long> deleted = retention.run();

        assertThat(deleted).containsKeys("security-events", "inbox", "webhook-outbox", "job-runs");
        assertThat(count("security_events", "details->>'marker' = '" + marker + "'"))
                .isEqualTo(1);
        assertThat(count("kauth_login_attempts", "login = '" + marker + "'")).isEqualTo(1);
        assertThat(count("kauth_otp_codes", "code_hash like '" + marker + "%'")).isEqualTo(1);
        assertThat(count("kauth_password_reset_codes", "code_hash like '" + marker + "%'"))
                .isEqualTo(1);
        assertThat(count("kauth_sessions", "token_hash like '" + marker + "%'")).isEqualTo(2);
        assertThat(count("kwh_logs", "event_type = '" + marker + "'")).isEqualTo(1);
        assertThat(count("kwh_outbox", "event_type like '" + marker + "%'")).isEqualTo(2);
        assertThat(count("ms_notifications", "title = '" + marker + "'")).isEqualTo(1);
        assertThat(count("ms_notification_outbox", "template_code = '" + marker + "'"))
                .isEqualTo(1);
        // The old finished run goes; the old running one and both recent ones stay.
        assertThat(count("fnd_job_runs", "handler = '" + marker + "'")).isEqualTo(3);
    }

    @Test
    @DisplayName("3.13: cycle after cycle of old rows, the journals stay the size of their recent rows")
    void tablesDoNotGrow() {
        String marker = "retention-" + UUID.randomUUID().toString().substring(0, 8);
        for (int cycle = 0; cycle < 5; cycle++) {
            for (int i = 0; i < 20; i++) {
                insertJournals(marker, OLD);
            }
            insertJournals(marker, RECENT);

            retention.run();

            assertThat(count("security_events", "details->>'marker' = '" + marker + "'"))
                    .isEqualTo(cycle + 1);
            assertThat(count("ms_notifications", "title = '" + marker + "'")).isEqualTo(cycle + 1);
            assertThat(count("kwh_logs", "event_type = '" + marker + "'")).isEqualTo(cycle + 1);
        }
        assertThat(retention.run().values()).allSatisfy(rows -> assertThat(rows).isZero());
    }

    private void insertJournals(String marker, String at) {
        String code = marker + "-" + UUID.randomUUID();
        jdbc.sql("insert into security_events (event_type, user_id, ip, details, created_at)"
                        + " values ('PROBE', :u, '127.0.0.1', jsonb_build_object('marker', :m), " + at + ")")
                .param("u", user)
                .param("m", marker)
                .update();
        jdbc.sql("insert into kauth_login_attempts (login, ip, is_success, attempt_at) values (:m, '127.0.0.1', false, "
                        + at + ")")
                .param("m", marker)
                .update();
        jdbc.sql("insert into kauth_otp_codes (user_id, channel, code_hash, expires_at) values (:u, 'sms', :c, " + at
                        + ")")
                .param("u", user)
                .param("c", code)
                .update();
        jdbc.sql("insert into kauth_password_reset_codes (user_id, code_hash, expires_at, auth_version, channel)"
                        + " values (:u, :c, " + at + ", 1, 'email')")
                .param("u", user)
                .param("c", code)
                .update();
        jdbc.sql("insert into kauth_sessions (user_id, token_hash, ip, user_agent, created_at, last_seen_at, closed_at,"
                        + " auth_version)"
                        + " values (:u, :c, '127.0.0.1', 'probe', " + at + ", " + at + ", " + at + ", 1)")
                .param("u", user)
                .param("c", code)
                .update();
        jdbc.sql("insert into kwh_logs (subscription_id, event_type, http_status, duration_ms, is_success, sent_at)"
                        + " values (:s, :m, 200, 5, true, " + at + ")")
                .param("s", subscription)
                .param("m", marker)
                .update();
        insertOutbox("SENT", at, marker + "-sent");
        jdbc.sql("insert into ms_notifications (user_id, type, title, body, created_at) values (:u, 'info', :m, 'b', "
                        + at + ")")
                .param("u", user)
                .param("m", marker)
                .update();
        jdbc.sql("insert into ms_notification_outbox (channel, recipient, template_code, payload, idempotency_key,"
                        + " status, created_at, processed_at) values ('email', 'probe@example.invalid', :m, '{}', :k,"
                        + " 'SENT', " + at + ", " + at + ")")
                .param("m", marker)
                .param("k", UUID.randomUUID())
                .update();
        jdbc.sql("insert into fnd_job_runs (queue_id, handler, args, started_at, finished_at, status)"
                        + " values (0, :m, '{}', " + at + ", " + at + ", 'done')")
                .param("m", marker)
                .update();
        jdbc.sql("insert into fnd_job_runs (queue_id, handler, args, started_at, status)" + " values (0, :m, '{}', "
                        + at + ", 'running')")
                .param("m", marker)
                .update();
    }

    private void insertOutbox(String status, String at, String eventType) {
        jdbc.sql("insert into kwh_outbox (subscription_id, event_type, payload, status, created_at, processed_at)"
                        + " values (:s, :e, '{}', :st, " + at + ", " + (status.equals("PENDING") ? "null" : at) + ")")
                .param("s", subscription)
                .param("e", eventType)
                .param("st", status)
                .update();
    }

    private long count(String table, String where) {
        return jdbc.sql("select count(*) from " + table + " where " + where)
                .query(Long.class)
                .single();
    }
}
