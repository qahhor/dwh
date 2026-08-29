package com.greenwhite.dwh.cp.repository;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class CpSessionRepository {

    private final JdbcClient jdbc;

    public CpSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(Long userId, String rawToken, String ip, String userAgent) {
        jdbc.sql("""
                        insert into cp_sessions (user_id, token_hash, ip, user_agent)
                        values (:userId, :hash, cast(:ip as inet), :ua)
                        """)
                .param("userId", userId)
                .param("hash", CpPasswordHasher.sha256(rawToken))
                .param("ip", ip)
                .param("ua", userAgent)
                .update();
    }

    /** Активная сессия: не закрыта и не старше TTL. */
    public Optional<CpSession> findActive(String rawToken) {
        return jdbc.sql("""
                        select id, user_id, created_at, last_seen_at
                        from cp_sessions
                        where token_hash = :hash
                          and closed_at is null
                          and created_at > now() - make_interval(days => :ttl)
                        """)
                .param("hash", CpPasswordHasher.sha256(rawToken))
                .param("ttl", CpPref.SESSION_TTL_DAYS)
                .query((rs, n) -> new CpSession(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant()))
                .optional();
    }

    public void touch(Long sessionId) {
        jdbc.sql("update cp_sessions set last_seen_at = now() where id = :id")
                .param("id", sessionId)
                .update();
    }

    public void close(Long sessionId) {
        jdbc.sql("update cp_sessions set closed_at = now() where id = :id and closed_at is null")
                .param("id", sessionId)
                .update();
    }

    public record CpSession(Long id, Long userId, Instant createdAt, Instant lastSeenAt) {}
}
