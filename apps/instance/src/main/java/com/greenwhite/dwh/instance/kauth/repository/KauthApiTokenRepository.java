package com.greenwhite.dwh.instance.kauth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KauthApiTokenRepository {

    private final JdbcClient jdbcClient;

    public KauthApiTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ApiTokenRecord create(Long userId, String name, String tokenPrefix, String tokenHash, Instant expiresAt) {
        return jdbcClient.sql("""
                insert into kauth_api_tokens (user_id, name, token_prefix, token_hash, expires_at, created_at)
                values (:userId, :name, :tokenPrefix, :tokenHash, :expiresAt, now())
                returning id, user_id, name, token_prefix, token_hash, expires_at, created_at, last_used_at, revoked_at
                """)
                .param("userId", userId)
                .param("name", name)
                .param("tokenPrefix", tokenPrefix)
                .param("tokenHash", tokenHash)
                .param("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .query((rs, rowNum) -> new ApiTokenRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("name"),
                        rs.getString("token_prefix"),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null,
                        rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null
                ))
                .single();
    }

    public Optional<ApiTokenRecord> findActiveByTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                select id, user_id, name, token_prefix, token_hash, expires_at, created_at, last_used_at, revoked_at
                from kauth_api_tokens
                where token_hash = :tokenHash and revoked_at is null and (expires_at is null or expires_at > now())
                """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new ApiTokenRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("name"),
                        rs.getString("token_prefix"),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null,
                        rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null
                ))
                .optional();
    }

    public List<ApiTokenRecord> findByUserId(Long userId) {
        return jdbcClient.sql("""
                select id, user_id, name, token_prefix, token_hash, expires_at, created_at, last_used_at, revoked_at
                from kauth_api_tokens
                where user_id = :userId
                order by created_at desc
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new ApiTokenRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("name"),
                        rs.getString("token_prefix"),
                        rs.getString("token_hash"),
                        rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") != null ? rs.getTimestamp("last_used_at").toInstant() : null,
                        rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null
                ))
                .list();
    }

    public void updateLastUsed(Long tokenId) {
        jdbcClient.sql("""
                update kauth_api_tokens
                set last_used_at = now()
                where id = :tokenId
                """)
                .param("tokenId", tokenId)
                .update();
    }

    public void revoke(Long tokenId, Long userId) {
        jdbcClient.sql("""
                update kauth_api_tokens
                set revoked_at = now()
                where id = :tokenId and user_id = :userId and revoked_at is null
                """)
                .param("tokenId", tokenId)
                .param("userId", userId)
                .update();
    }

    public record ApiTokenRecord(
            Long id,
            Long userId,
            String name,
            String tokenPrefix,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt
    ) {}
}
