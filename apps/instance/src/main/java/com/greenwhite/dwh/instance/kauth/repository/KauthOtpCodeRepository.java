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

    public OtpRecord create(Long userId, String channel, String codeHash, Instant expiresAt) {
        return jdbcClient.sql("""
                insert into kauth_otp_codes (user_id, channel, code_hash, attempts_left, expires_at, created_at, is_used)
                values (:userId, :channel, :codeHash, 3, :expiresAt, now(), false)
                returning id, user_id, channel, code_hash, attempts_left, expires_at, created_at, is_used
                """)
                .param("userId", userId)
                .param("channel", channel)
                .param("codeHash", codeHash)
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

    public Optional<OtpRecord> findLatestActiveByUserId(Long userId) {
        return jdbcClient.sql("""
                select id, user_id, channel, code_hash, attempts_left, expires_at, created_at, is_used
                from kauth_otp_codes
                where user_id = :userId and not is_used and expires_at > now() and attempts_left > 0
                order by created_at desc
                limit 1
                """)
                .param("userId", userId)
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
