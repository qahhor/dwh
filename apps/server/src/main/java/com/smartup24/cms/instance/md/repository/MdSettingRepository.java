package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.web.Revisions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MdSettingRepository {

    private final JdbcClient jdbcClient;

    public MdSettingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The revision of the system settings as a whole (plan 10/10, item 3.6): 1 until the first save. */
    public long instanceRevision() {
        return jdbcClient
                .sql("select revision from md_settings_revision where scope = 'system'")
                .query(Long.class)
                .optional()
                .orElse(1L);
    }

    /**
     * Claims the next revision of the system settings for a save made from {@code expectedRevision}: one statement
     * checks and raises it, so of two concurrent saves from the same revision the second is refused (409). The row
     * appears with the first save; until then the revision is 1.
     */
    public long nextInstanceRevision(long expectedRevision) {
        Optional<Long> raised = jdbcClient
                .sql("""
                update md_settings_revision set revision = revision + 1, modified_at = clock_timestamp()
                where scope = 'system' and revision = :expectedRevision
                returning revision
                """)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional();
        if (raised.isPresent() || expectedRevision != 1L) {
            return raised.orElseThrow(Revisions::conflict);
        }
        return jdbcClient.sql("""
                insert into md_settings_revision (scope, revision) values ('system', 2)
                on conflict (scope) do nothing
                returning revision
                """).query(Long.class).optional().orElseThrow(Revisions::conflict);
    }

    public void setInstanceSetting(String key, String value) {
        int updated = jdbcClient
                .sql("update md_settings set value = :value where user_id is null and key = :key")
                .param("key", key)
                .param("value", value)
                .update();
        if (updated == 0) {
            jdbcClient
                    .sql("insert into md_settings (user_id, key, value) values (null, :key, :value)")
                    .param("key", key)
                    .param("value", value)
                    .update();
        }
    }

    public void setUserSetting(Long userId, String key, String value) {
        int updated = jdbcClient
                .sql("update md_settings set value = :value where user_id = :userId and key = :key")
                .param("userId", userId)
                .param("key", key)
                .param("value", value)
                .update();
        if (updated == 0) {
            jdbcClient
                    .sql("insert into md_settings (user_id, key, value) values (:userId, :key, :value)")
                    .param("userId", userId)
                    .param("key", key)
                    .param("value", value)
                    .update();
        }
    }

    public Optional<String> getInstanceSetting(String key) {
        return jdbcClient
                .sql("select value from md_settings where user_id is null and key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
    }

    public Optional<String> getUserSetting(Long userId, String key) {
        return jdbcClient
                .sql("select value from md_settings where user_id = :userId and key = :key")
                .param("userId", userId)
                .param("key", key)
                .query(String.class)
                .optional();
    }

    public Map<String, String> getAllInstanceSettings() {
        Map<String, String> map = new HashMap<>();
        jdbcClient
                .sql("select key, value from md_settings where user_id is null")
                .query(rs -> {
                    while (rs.next()) {
                        map.put(rs.getString("key"), rs.getString("value"));
                    }
                    return map;
                });
        return map;
    }

    public Map<String, String> getAllUserSettings(Long userId) {
        Map<String, String> map = new HashMap<>();
        jdbcClient
                .sql("select key, value from md_settings where user_id = :userId")
                .param("userId", userId)
                .query(rs -> {
                    while (rs.next()) {
                        map.put(rs.getString("key"), rs.getString("value"));
                    }
                    return map;
                });
        return map;
    }
}
