package com.smartup24.cms.instance.ms.task.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the hooks of the task types and statuses read and write besides the record the runtime saves (ADR-0032, 6.5):
 * whether a code is taken, the next place in the order, the places of the other items when one moves, and whether
 * tasks still use an item.
 */
@Repository
public class MsTaskDictionaryRepository {

    /** The dictionaries, by their tables; the names never come from a request. */
    public enum Dictionary {
        TYPES("ms_task_types"),
        STATUSES("ms_task_statuses");

        private final String table;

        Dictionary(String table) {
            this.table = table;
        }
    }

    /** The step between neighbouring places, so an item can later be put between two without renumbering. */
    public static final int STEP = 10;

    private final JdbcClient jdbc;

    public MsTaskDictionaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether an item other than {@code exceptId} — in use or archived — already has the code: a task keeps the code of
     * an archived item, so a new item may not take it.
     */
    public boolean codeTaken(Dictionary dictionary, String code, long exceptId) {
        return jdbc.sql("select exists (select 1 from " + dictionary.table + " where code = :code and id <> :exceptId)")
                .param("code", code)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    /** The place after the last item. */
    public int nextSortOrder(Dictionary dictionary) {
        Integer last = jdbc.sql("select coalesce(max(sort_order), 0) from " + dictionary.table)
                .query(Integer.class)
                .single();
        return (last == null ? 0 : last) + STEP;
    }

    /**
     * The items in use in their order, with {@code id} among them even when it is archived: the order a screen shows
     * and a move counts places in. An archived item keeps its last place.
     */
    public List<Long> orderedIds(Dictionary dictionary, long id) {
        return jdbc.sql("select id from " + dictionary.table
                        + " where archived_at is null or id = :id order by sort_order, id")
                .param("id", id)
                .query(Long.class)
                .list();
    }

    /**
     * Puts the other items at their new places, each change raising the item's revision (ADR-0024); an item already
     * in its place is not written.
     */
    public void place(Dictionary dictionary, Map<Long, Integer> places, long userId) {
        places.forEach((id, place) -> jdbc.sql("update " + dictionary.table
                        + " set sort_order = :place, revision = revision + 1, modified_by = :uid,"
                        + " modified_at = clock_timestamp() where id = :id and sort_order <> :place")
                .param("place", place)
                .param("uid", userId)
                .param("id", id)
                .update());
    }

    /** Whether a task is in the status with this code. */
    public boolean statusInUse(String code) {
        return jdbc.sql("select exists (select 1 from ms_tasks where status_code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    /** Whether a task is of the type with this code. */
    public boolean typeInUse(String code) {
        return jdbc.sql("select exists (select 1 from ms_tasks where type_code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }
}
