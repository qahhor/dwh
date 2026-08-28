package com.greenwhite.dwh.instance.kauth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KauthChannelRepository {

    private final JdbcClient jdbcClient;

    public KauthChannelRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ChannelRecord bindOrUpdate(Long userId, String channel, String address, boolean isVerified) {
        return jdbcClient.sql("""
                insert into kauth_user_channels (user_id, channel, address, is_verified, created_at)
                values (:userId, :channel, :address, :isVerified, now())
                on conflict (user_id, channel) do update
                set address = :address, is_verified = :isVerified
                returning id, user_id, channel, address, is_verified, created_at
                """)
                .param("userId", userId)
                .param("channel", channel)
                .param("address", address)
                .param("isVerified", isVerified)
                .query((rs, rowNum) -> new ChannelRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("channel"),
                        rs.getString("address"),
                        rs.getBoolean("is_verified"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .single();
    }

    public Optional<ChannelRecord> findByUserIdAndChannel(Long userId, String channel) {
        return jdbcClient.sql("""
                select id, user_id, channel, address, is_verified, created_at
                from kauth_user_channels
                where user_id = :userId and channel = :channel
                """)
                .param("userId", userId)
                .param("channel", channel)
                .query((rs, rowNum) -> new ChannelRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("channel"),
                        rs.getString("address"),
                        rs.getBoolean("is_verified"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .optional();
    }

    public List<ChannelRecord> findByUserId(Long userId) {
        return jdbcClient.sql("""
                select id, user_id, channel, address, is_verified, created_at
                from kauth_user_channels
                where user_id = :userId
                order by channel asc
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new ChannelRecord(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("channel"),
                        rs.getString("address"),
                        rs.getBoolean("is_verified"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public record ChannelRecord(
            Long id,
            Long userId,
            String channel,
            String address,
            boolean isVerified,
            Instant createdAt
    ) {}
}
