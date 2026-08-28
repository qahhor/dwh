package com.greenwhite.dwh.instance.kauth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KauthSessionRepository {

    private final JdbcClient jdbcClient;

    public KauthSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public SessionRecord create(Long userId, String tokenHash, String ip, String userAgent, String deviceInfo) {
        return jdbcClient.sql("""
                insert into kauth_sessions (user_id, token_hash, ip, user_agent, device_info, created_at, last_seen_at)
                values (:userId, :tokenHash, cast(:ip as inet), :userAgent, :deviceInfo, now(), now())
                returning id, user_id, token_hash, host(ip) as ip_str, user_agent, device_info, created_at, last_seen_at, closed_at
                """)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("ip", ip)
                .param("userAgent", userAgent)
                .param("deviceInfo", deviceInfo)
                .query((rs, rowNum) -> new SessionRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("token_hash"),
                        rs.getString("ip_str"),
                        rs.getString("user_agent"),
                        rs.getString("device_info"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null
                ))
                .single();
    }

    public Optional<SessionRecord> findActiveByTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                select id, user_id, token_hash, host(ip) as ip_str, user_agent, device_info, created_at, last_seen_at, closed_at
                from kauth_sessions
                where token_hash = :tokenHash and closed_at is null
                """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new SessionRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("token_hash"),
                        rs.getString("ip_str"),
                        rs.getString("user_agent"),
                        rs.getString("device_info"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null
                ))
                .optional();
    }

    public void updateLastSeen(Long sessionId) {
        jdbcClient.sql("""
                update kauth_sessions
                set last_seen_at = now()
                where id = :sessionId and closed_at is null
                """)
                .param("sessionId", sessionId)
                .update();
    }

    public void close(Long sessionId) {
        jdbcClient.sql("""
                update kauth_sessions
                set closed_at = now()
                where id = :sessionId and closed_at is null
                """)
                .param("sessionId", sessionId)
                .update();
    }

    public void closeAllUserSessions(Long userId) {
        jdbcClient.sql("""
                update kauth_sessions
                set closed_at = now()
                where user_id = :userId and closed_at is null
                """)
                .param("userId", userId)
                .update();
    }

    public List<SessionRecord> findActiveByUserId(Long userId) {
        return jdbcClient.sql("""
                select id, user_id, token_hash, host(ip) as ip_str, user_agent, device_info, created_at, last_seen_at, closed_at
                from kauth_sessions
                where user_id = :userId and closed_at is null
                order by last_seen_at desc
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new SessionRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("token_hash"),
                        rs.getString("ip_str"),
                        rs.getString("user_agent"),
                        rs.getString("device_info"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toInstant() : null
                ))
                .list();
    }

    public record SessionRecord(
            Long id,
            Long userId,
            String tokenHash,
            String ip,
            String userAgent,
            String deviceInfo,
            Instant createdAt,
            Instant lastSeenAt,
            Instant closedAt
    ) {}
}
