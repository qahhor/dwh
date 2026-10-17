package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioListDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reference lists and their items (ADR-0041). Codes are permanent and nothing is deleted (triggers of V222); a
 * change names the revision it was made from and matches no row when it is stale.
 */
@Repository
public class StudioListRepository {
    private static final String LIST = """
            select l.id, l.code, l.name_ru, l.name_uz, l.name_en, l.revision, l.created_at, l.modified_at,
                   (select count(*) from studio_list_items i where i.list_id = l.id) as items,
                   (select count(*) from studio_list_items i where i.list_id = l.id and i.active) as active_items
            from studio_lists l
            """;
    private static final String ITEM = """
            select i.code, i.name_ru, i.name_uz, i.name_en, i.sort_order, i.active, i.revision, i.modified_at
            from studio_list_items i
            """;

    private final JdbcClient jdbc;

    /** A list with its key and item counts. */
    public record ListRow(
            long id,
            String code,
            Labels name,
            long revision,
            Instant createdAt,
            Instant modifiedAt,
            int items,
            int activeItems) {}

    public StudioListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lists, the newest first; {@code limit + 1} rows are read to tell whether more follow. */
    public List<ListRow> page(TimePage page) {
        TimePage.Position after = page.after();
        String where = after == null ? "" : " where (l.created_at, l.id) < (:at, :id)";
        var statement = jdbc.sql(LIST + where + " order by l.created_at desc, l.id desc limit :limit")
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> list(rs)).list();
    }

    public Optional<ListRow> find(String code) {
        return jdbc.sql(LIST + " where l.code = :code")
                .param("code", code)
                .query((rs, row) -> list(rs))
                .optional();
    }

    /** Every list there is. */
    public long count() {
        Long count =
                jdbc.sql("select count(*) from studio_lists").query(Long.class).single();
        return count == null ? 0 : count;
    }

    /** Serializes the changes of the lists: a new list against the limit, a new item against its list's. */
    public void lock() {
        jdbc.sql("select pg_advisory_xact_lock(756402006)")
                .query((rs, row) -> true)
                .single();
    }

    public long insert(String code, Labels name, long userId) {
        Long id = jdbc.sql("""
                        insert into studio_lists (code, name_ru, name_uz, name_en, created_by, modified_by)
                        values (:code, :ru, :uz, :en, :userId, :userId) returning id
                        """)
                .param("code", code)
                .param("ru", name.ru())
                .param("uz", name.uz())
                .param("en", name.en())
                .param("userId", userId)
                .query(Long.class)
                .single();
        return id == null ? 0 : id;
    }

    /** Renames the list from {@code revision}; false when the revision is no longer the list's. */
    public boolean rename(long id, long revision, Labels name, long userId) {
        return jdbc.sql("""
                        update studio_lists set name_ru = :ru, name_uz = :uz, name_en = :en,
                            revision = revision + 1, modified_by = :userId, modified_at = clock_timestamp()
                        where id = :id and revision = :revision
                        """)
                        .param("ru", name.ru())
                        .param("uz", name.uz())
                        .param("en", name.en())
                        .param("userId", userId)
                        .param("id", id)
                        .param("revision", revision)
                        .update()
                == 1;
    }

    /** The items of a list in their order, inactive ones too; bounded by the item limit. */
    public List<StudioListDtos.Item> items(long listId) {
        return jdbc.sql(ITEM + " where i.list_id = :listId order by i.sort_order, i.code limit :limit")
                .param("listId", listId)
                .param("limit", StudioListDtos.MAX_ITEMS)
                .query((rs, row) -> item(rs))
                .list();
    }

    /** The items of the list {@code code}, in their order; empty for an unknown list. */
    public List<StudioListDtos.Item> itemsOf(String code) {
        return jdbc.sql(ITEM + " join studio_lists l on l.id = i.list_id where l.code = :code"
                        + " order by i.sort_order, i.code limit :limit")
                .param("code", code)
                .param("limit", StudioListDtos.MAX_ITEMS)
                .query((rs, row) -> item(rs))
                .list();
    }

    public Optional<StudioListDtos.Item> item(long listId, String code) {
        return jdbc.sql(ITEM + " where i.list_id = :listId and i.code = :code")
                .param("listId", listId)
                .param("code", code)
                .query((rs, row) -> item(rs))
                .optional();
    }

    public void insertItem(long listId, StudioListDtos.CreateItem item, long userId) {
        jdbc.sql("""
                        insert into studio_list_items
                            (list_id, code, name_ru, name_uz, name_en, sort_order, created_by, modified_by)
                        values (:listId, :code, :ru, :uz, :en, :sortOrder, :userId, :userId)
                        """)
                .param("listId", listId)
                .param("code", item.code())
                .param("ru", item.name().ru())
                .param("uz", item.name().uz())
                .param("en", item.name().en())
                .param("sortOrder", item.sortOrder())
                .param("userId", userId)
                .update();
    }

    /** Changes an item from {@code revision}; false when the revision is no longer the item's. */
    public boolean updateItem(long listId, String code, long revision, StudioListDtos.UpdateItem item, long userId) {
        return jdbc.sql("""
                        update studio_list_items set name_ru = :ru, name_uz = :uz, name_en = :en,
                            sort_order = :sortOrder, active = :active, revision = revision + 1,
                            modified_by = :userId, modified_at = clock_timestamp()
                        where list_id = :listId and code = :code and revision = :revision
                        """)
                        .param("ru", item.name().ru())
                        .param("uz", item.name().uz())
                        .param("en", item.name().en())
                        .param("sortOrder", item.sortOrder())
                        .param("active", item.active())
                        .param("userId", userId)
                        .param("listId", listId)
                        .param("code", code)
                        .param("revision", revision)
                        .update()
                == 1;
    }

    private static ListRow list(ResultSet rs) throws SQLException {
        return new ListRow(
                rs.getLong("id"),
                rs.getString("code"),
                labels(rs),
                rs.getLong("revision"),
                instant(rs, "created_at"),
                instant(rs, "modified_at"),
                rs.getInt("items"),
                rs.getInt("active_items"));
    }

    private static StudioListDtos.Item item(ResultSet rs) throws SQLException {
        return new StudioListDtos.Item(
                rs.getString("code"),
                labels(rs),
                rs.getInt("sort_order"),
                rs.getBoolean("active"),
                rs.getLong("revision"),
                instant(rs, "modified_at"));
    }

    private static Labels labels(ResultSet rs) throws SQLException {
        return new Labels(rs.getString("name_ru"), rs.getString("name_uz"), rs.getString("name_en"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
