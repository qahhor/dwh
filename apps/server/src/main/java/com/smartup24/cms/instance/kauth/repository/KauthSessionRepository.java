package com.smartup24.cms.instance.kauth.repository;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.kauth.pref.KauthSessionProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * Cookie sessions. A session is active while it is open, its account is active and on the same authentication
 * version, and it is neither older than the absolute lifetime nor idle longer than the idle timeout (FR-AUTH-8,
 * ADR-0034): expiry is part of the query, so no background job has to run for an expired session to stop working.
 * Session times come from the application clock.
 */
@Repository
public class KauthSessionRepository {

    private static final RowMapper<SessionRecord> ROW_MAPPER = (rs, rowNum) -> new SessionRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("token_hash"),
            rs.getString("ip_str"),
            rs.getString("user_agent"),
            rs.getString("device_info"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null,
            rs.getLong("auth_version"));

    private static final String SELECT = """
            select c.auth_version, c.id, c.user_id, c.token_hash, host(c.ip) as ip_str, c.user_agent, c.device_info, c.created_at, c.last_seen_at, c.closed_at
            from kauth_sessions c join md_pub_users u on u.id = c.user_id
            """;
    private static final String ACTIVE = """
            c.closed_at is null
            and u.state = 'A' and u.auth_version = c.auth_version
            and c.created_at > :absoluteCutoff and c.last_seen_at > :idleCutoff
            """;

    private final JdbcClient jdbcClient;
    private final KauthSessionProperties properties;
    private final Clock clock;

    public KauthSessionRepository(JdbcClient jdbcClient) {
        this(jdbcClient, KauthSessionProperties.defaults(), Clock.systemUTC());
    }

    @Autowired
    public KauthSessionRepository(
            JdbcClient jdbcClient, KauthSessionProperties properties, ObjectProvider<Clock> clock) {
        this(jdbcClient, properties, clock.getIfAvailable(Clock::systemUTC));
    }

    public KauthSessionRepository(JdbcClient jdbcClient, KauthSessionProperties properties, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.clock = clock;
    }

    public SessionRecord create(
            Long userId, long authenticationVersion, String tokenHash, String ip, String userAgent, String deviceInfo) {
        return jdbcClient
                .sql("""
                insert into kauth_sessions (user_id, auth_version, token_hash, ip, user_agent, device_info, created_at, last_seen_at)
                select u.id, :authenticationVersion, :tokenHash, cast(:ip as inet), :userAgent, :deviceInfo, :now, :now
                from md_pub_users u
                where u.id = :userId and u.state = 'A' and u.auth_version = :authenticationVersion
                returning auth_version, id, user_id, token_hash, host(ip) as ip_str, user_agent, device_info, created_at, last_seen_at, closed_at
                """)
                .param("userId", userId)
                .param("authenticationVersion", authenticationVersion)
                .param("tokenHash", tokenHash)
                .param("ip", ip)
                .param("userAgent", userAgent)
                .param("deviceInfo", deviceInfo)
                .param("now", Timestamp.from(clock.instant()))
                .query(ROW_MAPPER)
                .optional()
                .orElseThrow(ApiException::invalidCredentials);
    }

    public Optional<SessionRecord> findActiveByTokenHash(String tokenHash) {
        return active(SELECT + " where c.token_hash = :tokenHash and " + ACTIVE)
                .param("tokenHash", tokenHash)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<SessionRecord> findActiveById(Long id) {
        return active(SELECT + " where c.id = :id and " + ACTIVE)
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * Records the use of an active session. The write happens at most once per touch interval: a session seen within
     * it is left alone without a statement, and a concurrent request that already moved it changes nothing.
     *
     * @return whether the last activity was written
     */
    public boolean touch(SessionRecord session) {
        Instant now = clock.instant();
        Instant touchCutoff = now.minus(properties.touchInterval());
        if (session.lastSeenAt() != null && session.lastSeenAt().isAfter(touchCutoff)) {
            return false;
        }
        int updated = jdbcClient
                .sql("""
                update kauth_sessions
                set last_seen_at = :now
                where id = :sessionId
                  and closed_at is null
                  and last_seen_at <= :touchCutoff
                """)
                .param("sessionId", session.id())
                .param("now", Timestamp.from(now))
                .param("touchCutoff", Timestamp.from(touchCutoff))
                .update();
        return updated > 0;
    }

    public void close(Long sessionId) {
        jdbcClient.sql("""
                update kauth_sessions
                set closed_at = now()
                where id = :sessionId and closed_at is null
                """).param("sessionId", sessionId).update();
    }

    /** Closes a session only when it belongs to the user; the number of sessions closed (0 or 1). */
    public int closeOwned(Long sessionId, Long userId) {
        return jdbcClient
                .sql("""
                update kauth_sessions
                set closed_at = now()
                where id = :sessionId and user_id = :userId and closed_at is null
                """)
                .param("sessionId", sessionId)
                .param("userId", userId)
                .update();
    }

    public void closeAllUserSessions(Long userId) {
        jdbcClient.sql("""
                update kauth_sessions
                set closed_at = now()
                where user_id = :userId and closed_at is null
                """).param("userId", userId).update();
    }

    /**
     * Housekeeping: marks closed the open sessions the active condition already treats as expired, so they leave the
     * open-session indexes and the retention of closed sessions applies to them. Validity does not depend on it.
     */
    public int closeExpiredSessions() {
        Instant now = clock.instant();
        return jdbcClient
                .sql("""
                update kauth_sessions
                set closed_at = :now
                where closed_at is null
                  and (created_at <= :absoluteCutoff or last_seen_at <= :idleCutoff)
                """)
                .param("now", Timestamp.from(now))
                .param("absoluteCutoff", Timestamp.from(now.minus(properties.absoluteTtl())))
                .param("idleCutoff", Timestamp.from(now.minus(properties.idleTimeout())))
                .update();
    }

    public void closeOtherSessions(Long userId, Long currentSessionId) {
        jdbcClient
                .sql("""
                update kauth_sessions
                set closed_at = now()
                where user_id = :userId and id <> :currentSessionId and closed_at is null
                """)
                .param("userId", userId)
                .param("currentSessionId", currentSessionId)
                .update();
    }

    public List<SessionRecord> findActiveByUserId(Long userId) {
        return active(SELECT + " where c.user_id = :userId and " + ACTIVE + " order by c.last_seen_at desc")
                .param("userId", userId)
                .query(ROW_MAPPER)
                .list();
    }

    /** A statement with the expiry cutoffs of {@link #ACTIVE}, taken from the clock now. */
    private StatementSpec active(String sql) {
        Instant now = clock.instant();
        return jdbcClient
                .sql(sql)
                .param("absoluteCutoff", Timestamp.from(now.minus(properties.absoluteTtl())))
                .param("idleCutoff", Timestamp.from(now.minus(properties.idleTimeout())));
    }

    public record SessionRecord(
            Long id,
            Long userId,
            @JsonIgnore String tokenHash,
            String ip,
            String userAgent,
            String deviceInfo,
            Instant createdAt,
            Instant lastSeenAt,
            Instant closedAt,
            @JsonIgnore long authenticationVersion) {}
}
