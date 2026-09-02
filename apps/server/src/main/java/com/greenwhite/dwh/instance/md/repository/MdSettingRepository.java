package com.greenwhite.dwh.instance.md.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class MdSettingRepository {

    private final JdbcClient jdbcClient;

    public MdSettingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void setInstanceSetting(String key, String value) {
        int updated = jdbcClient.sql("update md_settings set value = :value where user_id is null and key = :key")
                .param("key", key)
                .param("value", value)
                .update();
        if (updated == 0) {
            jdbcClient.sql("insert into md_settings (user_id, key, value) values (null, :key, :value)")
                    .param("key", key)
                    .param("value", value)
                    .update();
        }
    }

    public void setUserSetting(Long userId, String key, String value) {
        int updated = jdbcClient.sql("update md_settings set value = :value where user_id = :userId and key = :key")
                .param("userId", userId)
                .param("key", key)
                .param("value", value)
                .update();
        if (updated == 0) {
            jdbcClient.sql("insert into md_settings (user_id, key, value) values (:userId, :key, :value)")
                    .param("userId", userId)
                    .param("key", key)
                    .param("value", value)
                    .update();
        }
    }


    public Optional<String> getInstanceSetting(String key) {
        return jdbcClient.sql("select value from md_settings where user_id is null and key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
    }

    public Optional<String> getUserSetting(Long userId, String key) {
        return jdbcClient.sql("select value from md_settings where user_id = :userId and key = :key")
                .param("userId", userId)
                .param("key", key)
                .query(String.class)
                .optional();
    }

    public Map<String, String> getAllInstanceSettings() {
        Map<String, String> map = new HashMap<>();
        jdbcClient.sql("select key, value from md_settings where user_id is null")
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
        jdbcClient.sql("select key, value from md_settings where user_id = :userId")
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

