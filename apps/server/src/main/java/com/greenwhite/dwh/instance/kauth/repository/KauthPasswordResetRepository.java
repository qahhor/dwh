package com.greenwhite.dwh.instance.kauth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class KauthPasswordResetRepository {

    private final JdbcClient jdbcClient;

    public KauthPasswordResetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ResetRecord create(Long userId, String tokenHash, Instant expiresAt) {
        return jdbcClient.sql("""
                insert into kauth_password_resets (user_id, token_hash, expires_at, created_at, is_used)
                values (:userId, :tokenHash, :expiresAt, now(), false)
                returning id, user_id, token_hash, expires_at, created_at, is_used
                """)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .query((rs, rowNum) -> new ResetRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_used")
                ))
                .single();
    }

    public Optional<ResetRecord> findActiveByCodeHash(String codeHash) {
        return findActiveByTokenHash(codeHash);
    }

    public Optional<ResetRecord> findActiveByTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                select id, user_id, token_hash, expires_at, created_at, is_used
                from kauth_password_resets
                where token_hash = :tokenHash and not is_used and expires_at > now()
                order by created_at desc
                limit 1
                """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new ResetRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_used")
                ))
                .optional();
    }

    public void markAsUsed(Long id) {
        jdbcClient.sql("""
                update kauth_password_resets
                set is_used = true
                where id = :id
                """)
                .param("id", id)
                .update();
    }

    public record ResetRecord(
            Long id,
            Long userId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt,
            boolean isUsed
    ) {}
}
