package com.greenwhite.dwh.instance.kauth.repository;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class KauthOtpCodeRepository {

    private static final RowMapper<OtpRecord> ROW_MAPPER = (rs, rowNum) -> new OtpRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("channel"),
            rs.getString("code_hash"),
            rs.getInt("attempts_left"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("is_used"),
            rs.getLong("auth_version")
    );

    private static final String SELECT = """
            select c.auth_version, c.id, c.user_id, c.channel, c.code_hash, c.attempts_left, c.expires_at, c.created_at, c.is_used
            from kauth_otp_codes c join md_users u on u.id = c.user_id
            """;
    private static final String ACTIVE = """
            not c.is_used and c.attempts_left > 0 and c.expires_at > now()
            and u.state = 'A' and u.auth_version = c.auth_version
            """;

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
    public OtpRecord create(Long userId, long authenticationVersion, String channel, String codeHash, String otpTokenHash,
                            String purpose, Instant expiresAt) {
        return jdbcClient.sql("""
                insert into kauth_otp_codes (user_id, auth_version, channel, code_hash, otp_token_hash, purpose,
                                             attempts_left, expires_at, created_at, is_used)
                select u.id, :authenticationVersion, :channel, :codeHash, :otpTokenHash, :purpose, 3, :expiresAt, now(), false
                from md_users u
                where u.id = :userId and u.state = 'A' and u.auth_version = :authenticationVersion
                returning auth_version, id, user_id, channel, code_hash, attempts_left, expires_at, created_at, is_used
                """)
                .param("userId", userId)
                .param("authenticationVersion", authenticationVersion)
                .param("channel", channel)
                .param("codeHash", codeHash)
                .param("otpTokenHash", otpTokenHash)
                .param("purpose", purpose)
                .param("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .query(ROW_MAPPER)
                .optional().orElseThrow(ApiException::invalidCredentials);
    }

    /** Единственный правильный способ найти код: по хешу выданного токена. */
    public Optional<OtpRecord> findActiveByTokenHash(String otpTokenHash, String purpose) {
        return jdbcClient.sql(SELECT + " where c.otp_token_hash = :otpTokenHash and c.purpose = :purpose and " + ACTIVE)
                .param("otpTokenHash", otpTokenHash)
                .param("purpose", purpose)
                .query(ROW_MAPPER)
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

    public boolean consume(Long otpId, Long userId, long authenticationVersion, String purpose) {
        return jdbcClient.sql("""
                update kauth_otp_codes o set is_used = true
                where o.id = :otpId and o.user_id = :userId and o.purpose = :purpose
                  and o.auth_version = :authenticationVersion and not o.is_used
                  and o.attempts_left > 0 and o.expires_at > now()
                  and exists (select 1 from md_users u where u.id = o.user_id
                              and u.state = 'A' and u.auth_version = o.auth_version)
                """)
                .param("otpId", otpId)
                .param("userId", userId)
                .param("authenticationVersion", authenticationVersion)
                .param("purpose", purpose)
                .update() == 1;
    }

    public record OtpRecord(
            Long id,
            Long userId,
            String channel,
            String codeHash,
            int attemptsLeft,
            Instant expiresAt,
            Instant createdAt,
            boolean isUsed,
            @JsonIgnore long authenticationVersion
    ) {}
}
