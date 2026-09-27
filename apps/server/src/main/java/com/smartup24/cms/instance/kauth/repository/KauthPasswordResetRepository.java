package com.smartup24.cms.instance.kauth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One-time password reset links ({@code kauth_password_reset_codes}). Only the SHA-256 of a link token is stored.
 *
 * <p>Until V126 this repository wrote to {@code kauth_password_resets}, a table that never existed, so every reset
 * request for a known email ended in a server error.
 */
@Repository
public class KauthPasswordResetRepository {

    private static final String COLUMNS = "id, user_id, auth_version, channel, expires_at, created_at";

    private static final RowMapper<ResetRecord> MAPPER = (rs, rowNum) -> new ResetRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("auth_version"),
            rs.getString("channel"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbcClient;

    public KauthPasswordResetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ResetRecord create(Long userId, long authVersion, String channel, String codeHash, Instant expiresAt) {
        return jdbcClient
                .sql("""
                insert into kauth_password_reset_codes (user_id, auth_version, channel, code_hash, expires_at)
                values (:userId, :authVersion, :channel, :codeHash, :expiresAt)
                returning\s""" + COLUMNS)
                .param("userId", userId)
                .param("authVersion", authVersion)
                .param("channel", channel)
                .param("codeHash", codeHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .query(MAPPER)
                .single();
    }

    /** A link that is neither used nor expired. */
    public Optional<ResetRecord> findActive(String codeHash) {
        return jdbcClient
                .sql("select " + COLUMNS + """
                 from kauth_password_reset_codes
                where code_hash = :codeHash and not is_used and expires_at > now()
                """)
                .param("codeHash", codeHash)
                .query(MAPPER)
                .optional();
    }

    /**
     * Uses the link up. Only one caller wins: the update matches an unused, unexpired row and nothing after that.
     *
     * @return {@code false} when the link was used or expired in the meantime
     */
    public boolean consume(Long id) {
        return jdbcClient.sql("""
                update kauth_password_reset_codes
                set is_used = true, used_at = now()
                where id = :id and not is_used and expires_at > now()
                """).param("id", id).update() == 1;
    }

    /** A new link replaces the ones issued before it: only the latest one works. */
    public void revokeActive(Long userId) {
        jdbcClient.sql("""
                update kauth_password_reset_codes
                set is_used = true, used_at = now()
                where user_id = :userId and not is_used
                """).param("userId", userId).update();
    }

    /** Serialises link issuing for one user until the end of the transaction. */
    public void lockUser(Long userId) {
        jdbcClient
                .sql(
                        "select pg_advisory_xact_lock(hashtext('kauth_password_reset'), cast(mod(:userId, 2147483647) as int))")
                .param("userId", userId)
                .query()
                .singleValue();
    }

    public int countIssuedSince(Long userId, Instant since) {
        return jdbcClient
                .sql("""
                select count(*) from kauth_password_reset_codes
                where user_id = :userId and created_at >= :since
                """)
                .param("userId", userId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    public record ResetRecord(
            Long id, Long userId, long authVersion, String channel, Instant expiresAt, Instant createdAt) {}
}
