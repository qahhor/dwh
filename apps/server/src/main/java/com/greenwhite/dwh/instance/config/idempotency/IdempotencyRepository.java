package com.greenwhite.dwh.instance.config.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbcClient;

    public IdempotencyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record IdempotencyRecord(
            UUID key,
            Long userId,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant createdAt
    ) {}

    public Optional<IdempotencyRecord> findByKey(UUID key) {
        return jdbcClient.sql("""
                select key, user_id, request_hash, response_status, response_body::text, created_at
                from idempotency_keys
                where key = :key
                """)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        UUID.fromString(rs.getString("key")),
                        rs.getObject("user_id", Long.class),
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getString("response_body"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .optional();
    }

    public void save(UUID key, Long userId, String requestHash, int responseStatus, String responseBodyJson) {
        String safeBody = (responseBodyJson == null || responseBodyJson.isBlank()) ? "{}" : responseBodyJson;
        jdbcClient.sql("""
                insert into idempotency_keys (key, user_id, request_hash, response_status, response_body)
                values (:key, :userId, :requestHash, :responseStatus, :responseBody::jsonb)
                on conflict (key) do nothing
                """)
                .param("key", key)
                .param("userId", userId)
                .param("requestHash", requestHash)
                .param("responseStatus", responseStatus)
                .param("responseBody", safeBody)
                .update();
    }

    public void deleteOlderThan(Instant cutoff) {
        jdbcClient.sql("delete from idempotency_keys where created_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }
}
