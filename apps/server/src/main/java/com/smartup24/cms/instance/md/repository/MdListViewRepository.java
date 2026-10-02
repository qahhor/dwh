package com.smartup24.cms.instance.md.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Saved list views (V117) and their kinds (V192: a table view, a report or a widget, ADR-0032, 10.2). Every query stays
 * within the user's own views.
 */
@Repository
public class MdListViewRepository {

    private static final String COLUMNS =
            "id, list_code, kind, name, state::text as state, is_default, lock_version, modified_at";

    public record ListView(
            long id,
            String listCode,
            String kind,
            String name,
            String stateJson,
            boolean isDefault,
            int lockVersion,
            Instant modifiedAt) {}

    private final JdbcClient jdbc;

    public MdListViewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The user's views of one list of the given kinds, by name. */
    public List<ListView> list(long userId, String listCode, Collection<String> kinds) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views where user_id = :u and list_code = :l"
                        + " and kind in (:kinds) order by lower(name), id")
                .param("u", userId)
                .param("l", listCode)
                .param("kinds", kinds)
                .query(this::map)
                .list();
    }

    /** The user's widgets across every list, by name (the dashboard, ADR-0032, 10.2). */
    public List<ListView> widgets(long userId) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views where user_id = :u and kind = 'widget'"
                        + " order by lower(name), id")
                .param("u", userId)
                .query(this::map)
                .list();
    }

    /** How many widgets the user has across every list. */
    public long countWidgets(long userId) {
        return jdbc.sql("select count(*) from md_list_views where user_id = :u and kind = 'widget'")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    public Optional<ListView> find(long userId, String listCode, long id) {
        return jdbc.sql("select " + COLUMNS + " from md_list_views where id = :id and user_id = :u and list_code = :l")
                .param("id", id)
                .param("u", userId)
                .param("l", listCode)
                .query(this::map)
                .optional();
    }

    /** How many views of the given kinds the user has on one list. */
    public long count(long userId, String listCode, Collection<String> kinds) {
        return jdbc.sql("select count(*) from md_list_views where user_id = :u and list_code = :l and kind in (:kinds)")
                .param("u", userId)
                .param("l", listCode)
                .param("kinds", kinds)
                .query(Long.class)
                .single();
    }

    public long insert(long userId, String listCode, String kind, String name, String stateJson, boolean isDefault) {
        return jdbc.sql("""
                        insert into md_list_views (user_id, list_code, kind, name, state, is_default)
                        values (:u, :l, :kind, :name, cast(:state as jsonb), :def)
                        returning id
                        """)
                .param("u", userId)
                .param("l", listCode)
                .param("kind", kind)
                .param("name", name)
                .param("state", stateJson)
                .param("def", isDefault)
                .query(Long.class)
                .single();
    }

    /** @return 0 if the view does not exist or was already changed (a different {@code lock_version}) */
    public int update(long userId, String listCode, long id, int lockVersion, ListView data) {
        return jdbc.sql("""
                        update md_list_views
                        set kind = :kind, name = :name, state = cast(:state as jsonb), is_default = :def,
                            lock_version = lock_version + 1, modified_at = now()
                        where id = :id and user_id = :u and list_code = :l and lock_version = :lv
                        """)
                .param("kind", data.kind())
                .param("name", data.name())
                .param("state", data.stateJson())
                .param("def", data.isDefault())
                .param("id", id)
                .param("u", userId)
                .param("l", listCode)
                .param("lv", lockVersion)
                .update();
    }

    /** Clears the "default" flag on the list's other views. */
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
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("state"),
                rs.getBoolean("is_default"),
                rs.getInt("lock_version"),
                rs.getTimestamp("modified_at").toInstant());
    }
}
