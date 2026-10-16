package com.smartup24.cms.instance.kauth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KauthLoginAttemptRepository {

    private final JdbcClient jdbcClient;

    public KauthLoginAttemptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void recordAttempt(String login, Long userId, String ip, boolean isSuccess, String failureReason) {
        jdbcClient
                .sql("""
                insert into kauth_login_attempts (login, user_id, ip, is_success, failure_reason, attempt_at)
                values (:login, :userId, cast(:ip as inet), :isSuccess, :failureReason, clock_timestamp())
                """)
                .param("login", userId == null ? normalize(login) : login)
                .param("userId", userId)
                .param("ip", ip)
                .param("isSuccess", isSuccess)
                .param("failureReason", failureReason)
                .update();
    }

    public int countFailedAttemptsForIpSince(String ip, Instant since) {
        return jdbcClient
                .sql("""
                select count(*) from kauth_login_attempts
                where ip = cast(:ip as inet) and not is_success and attempt_at >= :since
                """)
                .param("ip", ip)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** Failures with the given reason from one address: password reset links, for one. */
    public int countFailedAttemptsForIpSince(String ip, String failureReason, Instant since) {
        return jdbcClient
                .sql("""
                select count(*) from kauth_login_attempts
                where ip = cast(:ip as inet) and not is_success and failure_reason = :failureReason
                  and attempt_at >= :since
                """)
                .param("ip", ip)
                .param("failureReason", failureReason)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** Always acquired before the account lock; Spring's login transaction deadline bounds lock waits. */
    public void lockIp(String ip) {
        jdbcClient.sql("""
                select pg_advisory_xact_lock(hashtext('kauth_login_ip'), hashtext(host(cast(:ip as inet))))
                """).param("ip", ip).query().singleValue();
    }

    /** Shared by all nodes, all login spellings and both account aliases. Hash collisions only serialize more work. */
    public void lockAccount(Long userId, String login) {
        String key = userId == null ? "login:" + normalize(login) : "user:" + userId;
        jdbcClient
                .sql("select pg_advisory_xact_lock(hashtext('kauth_login_account'), hashtext(:key))")
                .param("key", key)
                .query()
                .singleValue();
    }

    public int countFailedAttemptsForAccountSince(Long userId, String login, Instant since) {
        String account = userId == null ? "user_id is null and login = :login" : "user_id = :userId";
        return jdbcClient
                .sql("select count(*) from kauth_login_attempts where " + account
                        + " and not is_success and attempt_at >= :since")
                .param("userId", userId)
                .param("login", normalize(login))
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    public record LoginAttemptRecord(
            Long id, String login, String ip, boolean isSuccess, String failureReason, Instant attemptAt) {}

    private static String normalize(String login) {
        return login.trim().toLowerCase(Locale.ROOT);
    }

    public List<LoginAttemptRecord> findRecentAttemptsForUser(Long userId, int limit) {
        return jdbcClient
                .sql("""
                select id, login, host(ip) as ip, is_success, failure_reason, attempt_at
                from kauth_login_attempts
                where user_id = :userId
                order by attempt_at desc, id desc
                limit :limit
                """)
                .param("userId", userId)
                .param("limit", limit)
                .query((rs, rowNum) -> new LoginAttemptRecord(
                        rs.getLong("id"),
                        rs.getString("login"),
                        rs.getString("ip"),
                        rs.getBoolean("is_success"),
                        rs.getString("failure_reason"),
                        rs.getTimestamp("attempt_at").toInstant()))
                .list();
    }
}
