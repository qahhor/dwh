package com.greenwhite.dwh.instance.md.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Сохранённые представления списков (V117). Все запросы — только в пределах своего пользователя и списка. */
@Repository
public class MdListViewRepository {

    private static final String COLUMNS = "id, list_code, name, state::text as state, is_default, lock_version, modified_at";

    public record ListView(long id, String listCode, String name, String stateJson, boolean isDefault,
                           int lockVersion, Instant modifiedAt) {
    }

    private final JdbcClient jdbc;

    public MdListViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ListView> list(long userId, String listCode) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views where user_id = :u and list_code = :l"
                        + " order by lower(name), id")
                .param("u", userId)
                .param("l", listCode)
                .query(this::map)
                .list();
    }

    public Optional<ListView> find(long userId, String listCode, long id) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views where id = :id and user_id = :u and list_code = :l")
                .param("id", id)
                .param("u", userId)
                .param("l", listCode)
                .query(this::map)
                .optional();
    }

    public long count(long userId, String listCode) {
        return jdbc.sql("select count(*) from md_list_views where user_id = :u and list_code = :l")
                .param("u", userId)
                .param("l", listCode)
                .query(Long.class)
                .single();
    }

    public long insert(long userId, String listCode, String name, String stateJson, boolean isDefault) {
        return jdbc.sql("""
                        insert into md_list_views (user_id, list_code, name, state, is_default)
                        values (:u, :l, :name, cast(:state as jsonb), :def)
                        returning id
                        """)
                .param("u", userId)
                .param("l", listCode)
                .param("name", name)
                .param("state", stateJson)
                .param("def", isDefault)
                .query(Long.class)
                .single();
    }

    /** @return 0 — представления нет или его уже изменили (другой {@code lock_version}) */
    public int update(long userId, String listCode, long id, int lockVersion, String name, String stateJson,
                      boolean isDefault) {
        return jdbc.sql("""
                        update md_list_views
                        set name = :name, state = cast(:state as jsonb), is_default = :def,
                            lock_version = lock_version + 1, modified_at = now()
                        where id = :id and user_id = :u and list_code = :l and lock_version = :lv
                        """)
                .param("name", name)
                .param("state", stateJson)
                .param("def", isDefault)
                .param("id", id)
                .param("u", userId)
                .param("l", listCode)
                .param("lv", lockVersion)
                .update();
    }

    /** Снимает признак «по умолчанию» с остальных представлений списка. */
    public void clearDefault(long userId, String listCode, Long exceptId) {
        jdbc.sql("""
                        update md_list_views set is_default = false, modified_at = now()
                        where user_id = :u and list_code = :l and is_default
                          and (cast(:except as bigint) is null or id <> :except)
                        """)
                .param("u", userId)
                .param("l", listCode)
                .param("except", exceptId)
                .update();
    }

    public int delete(long userId, String listCode, long id) {
        return jdbc.sql("delete from md_list_views where id = :id and user_id = :u and list_code = :l")
                .param("id", id)
                .param("u", userId)
                .param("l", listCode)
                .update();
    }

    private ListView map(ResultSet rs, int rowNum) throws SQLException {
        return new ListView(
                rs.getLong("id"),
                rs.getString("list_code"),
                rs.getString("name"),
                rs.getString("state"),
                rs.getBoolean("is_default"),
                rs.getInt("lock_version"),
                rs.getTimestamp("modified_at").toInstant());
    }
}
