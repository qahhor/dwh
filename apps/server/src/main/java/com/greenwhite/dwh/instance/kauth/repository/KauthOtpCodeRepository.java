package com.greenwhite.dwh.instance.kauth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class KauthOtpCodeRepository {

    private final JdbcClient jdbcClient;

    public KauthOtpCodeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Код второго фактора, привязанный к своему токену (FR-AUTH-5).
     *
     * Токен хранится хешем и служит единственным способом найти этот код.
     * До V015 его не было вовсе, и код искали по идентификатору пользователя,
     * который проверка возвращала захардкоженным.
     */
    public OtpRecord create(Long userId, String channel, String codeHash, String otpTokenHash,
                            String purpose, Instant expiresAt) {
        return jdbcClient.sql("""
                insert into kauth_otp_codes (user_id, channel, code_hash, otp_token_hash, purpose,
                                             attempts_left, expires_at, created_at, is_used)
                values (:userId, :channel, :codeHash, :otpTokenHash, :purpose, 3, :expiresAt, now(), false)
                returning id, user_id, channel, code_hash, attempts_left, expires_at, created_at, is_used
                """)
                .param("userId", userId)
                .param("channel", channel)
                .param("codeHash", codeHash)
                .param("otpTokenHash", otpTokenHash)
                .param("purpose", purpose)
                .param("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .query((rs, rowNum) -> new OtpRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("channel"),
                        rs.getString("code_hash"),
                        rs.getInt("attempts_left"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_used")
                ))
                .single();
    }

    /** Единственный правильный способ найти код: по хешу выданного токена. */
    public Optional<OtpRecord> findActiveByTokenHash(String otpTokenHash, String purpose) {
        return jdbcClient.sql("""
                select id, user_id, channel, code_hash, attempts_left, expires_at, created_at, is_used
                from kauth_otp_codes
                where otp_token_hash = :otpTokenHash and purpose = :purpose
                      and not is_used and attempts_left > 0
                """)
                .param("otpTokenHash", otpTokenHash)
                .param("purpose", purpose)
                .query((rs, rowNum) -> new OtpRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("channel"),
                        rs.getString("code_hash"),
                        rs.getInt("attempts_left"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_used")
                ))
                .optional();
    }

    public void decrementAttempts(Long otpId) {
        jdbcClient.sql("""
                update kauth_otp_codes
                set attempts_left = attempts_left - 1
                where id = :otpId
                """)
                .param("otpId", otpId)
                .update();
    }

    public void markAsUsed(Long otpId) {
        jdbcClient.sql("""
                update kauth_otp_codes
                set is_used = true
                where id = :otpId
                """)
                .param("otpId", otpId)
                .update();
    }

    public record OtpRecord(
            Long id,
            Long userId,
            String channel,
            String codeHash,
            int attemptsLeft,
            Instant expiresAt,
            Instant createdAt,
            boolean isUsed
    ) {}
}
