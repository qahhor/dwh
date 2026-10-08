package com.smartup24.cms.instance.kauth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KauthChannelRepository {

    private final JdbcClient jdbcClient;

    public KauthChannelRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ChannelRecord bindOrUpdate(Long userId, String channel, String address, boolean isVerified) {
        return jdbcClient
                .sql("""
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
                        rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    public Optional<ChannelRecord> findByUserIdAndChannel(Long userId, String channel) {
        return jdbcClient
                .sql("""
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
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public List<ChannelRecord> findByUserId(Long userId) {
        return jdbcClient
                .sql("""
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
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * The code channel of each given user resolved the way sign-in resolves it (the first verified channel in the
     * priority order), counted per channel.
     */
    public Map<String, Long> codeChannelUsers(List<Long> userIds, List<String> priority) {
        Map<String, Long> users = new TreeMap<>();
        if (userIds.isEmpty()) {
            return users;
        }
        return jdbcClient
                .sql("""
                select channel, count(*) as users
                from (select distinct on (c.user_id) c.user_id, c.channel
                      from kauth_user_channels c
                      where c.is_verified and c.user_id in (:userIds)
                      order by c.user_id, array_position(cast(:priority as text[]), c.channel)) resolved
                group by channel
                """)
                .param("userIds", userIds)
                .param("priority", priority.toArray(String[]::new))
                .query(rs -> {
                    while (rs.next()) {
                        users.put(rs.getString("channel"), rs.getLong("users"));
                    }
                    return users;
                });
    }

    public void delete(Long userId, String channel) {
        jdbcClient
                .sql("delete from kauth_user_channels where user_id = :userId and channel = :channel")
                .param("userId", userId)
                .param("channel", channel)
                .update();
    }

    public record ChannelRecord(
            Long id, Long userId, String channel, String address, boolean isVerified, Instant createdAt) {}
}
