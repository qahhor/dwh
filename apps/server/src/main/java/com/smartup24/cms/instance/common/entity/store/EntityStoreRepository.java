package com.smartup24.cms.instance.common.entity.store;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityRowMapper;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Every statement of the entity runtime (ADR-0032, 6.11; plan 10/10, item 5.4), built from the declaration: a record
 * is read by the select of the entity's list, so a read by id, a page and the answer of a save are one projection
 * (step 15 of ADR-0032, 6.3); a change is {@code update … revision = revision + 1 where id = :id and revision =
 * :expected} (ADR-0024); the identifiers were checked when the entity was declared and every value is a parameter.
 */
@Repository
public class EntityStoreRepository {

    private final JdbcClient jdbc;
    private final QueryListRepository lists;
    private final ObjectMapper mapper;

    public EntityStoreRepository(JdbcClient jdbc, QueryListRepository lists, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.lists = lists;
        this.mapper = mapper;
    }

    /**
     * The record with this id if it lies in {@code scope}, an archived one too (ADR-0032, 5.4); with {@code lock} the
     * row stays locked for the rest of the transaction (step 4 of ADR-0032, 6.3).
     */
    public Optional<Map<String, Object>> find(
            EntityDefinition entity, QueryList list, long id, QueryPlan.SqlFragment scope, boolean lock) {
        EntityModel model = model(entity);
        Map<String, Object> params = new LinkedHashMap<>(scope.params());
        params.put("rid", id);
        String sql = "select " + list.select() + " from " + list.from() + " where " + model.alias() + ".id = :rid"
                + scope.sql() + (lock ? " for update of " + model.alias() : "");
        return jdbc.sql(sql)
                .params(params)
                .query(EntityRowMapper.of(entity, mapper))
                .optional();
    }

    /** A page of the entity's list by the plan; {@code predicate} is the scope and the archive of the list. */
    public KeysetPage<Map<String, Object>> page(
            EntityDefinition entity, QueryPlan plan, QueryPlan.SqlFragment predicate) {
        return lists.page(plan, EntityRowMapper.of(entity, mapper), predicate);
    }

    /** Inserts the record as {@code userId}; {@code attributes} are its custom field values, or null for none. */
    public long insert(
            EntityDefinition entity, EntityWrite write, @Nullable Map<String, Object> attributes, long userId) {
        EntityModel model = model(entity);
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (EntityWrite.Column column : write.columns()) {
            String parameter = "p" + index++;
            names.add(column.name());
            values.add(column.parameter(parameter));
            params.put(parameter, column.value());
        }
        if (attributes != null) {
            names.add(EntityModel.ATTRIBUTES);
            values.add("cast(:attributes as jsonb)");
            params.put("attributes", json(model).object(attributes));
        }
        String owner = EntityWrite.ownerColumn(model);
        if (owner != null && !names.contains(owner)) {
            names.add(owner);
            values.add(":uid");
        }
        names.add("created_by");
        values.add(":uid");
        names.add("modified_by");
        values.add(":uid");
        params.put("uid", userId);
        String sql = "insert into " + model.table() + " (" + String.join(", ", names) + ") values ("
                + String.join(", ", values) + ") returning id";
        Long id = jdbc.sql(sql).params(params).query(Long.class).single();
        return Objects.requireNonNull(id, entity.code());
    }

    /**
     * Writes the change from {@code expected} as {@code userId} and raises the revision by one; empty when the record
     * is no longer at that revision.
     */
    public Optional<Long> update(
            EntityDefinition entity,
            long id,
            long expected,
            EntityWrite write,
            @Nullable Map<String, Object> attributes,
            long userId) {
        EntityModel model = model(entity);
        List<String> sets = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (EntityWrite.Column column : write.columns()) {
            String parameter = "p" + index++;
            sets.add(column.name() + " = " + column.parameter(parameter));
            params.put(parameter, column.value());
        }
        if (attributes != null) {
            sets.add(EntityModel.ATTRIBUTES + " = cast(:attributes as jsonb)");
            params.put("attributes", json(model).object(attributes));
        }
        sets.add("modified_by = :uid");
        sets.add("modified_at = clock_timestamp()");
        sets.add("revision = revision + 1");
        params.put("uid", userId);
        params.put("rid", id);
        params.put("expected", expected);
        String sql = "update " + model.table() + " set " + String.join(", ", sets)
                + " where id = :rid and revision = :expected returning revision";
        return jdbc.sql(sql).params(params).query(Long.class).optional();
    }

    /**
     * Replaces the keys of a several-references field: a row per key, in order (ADR-0032, 4.1); in a table shared by
     * several fields only the rows of the field's kind.
     */
    public void writeLinks(FieldSource.Link link, long id, List<Long> keys) {
        jdbc.sql("delete from " + link.table() + " l where l." + link.ownerColumn() + " = :rid" + link.kindSql("l"))
                .param("rid", id)
                .update();
        String kindColumn = link.kindColumn() == null ? "" : ", " + link.kindColumn();
        String kindValue = link.kind() == null ? "" : ", :kind";
        int position = 1;
        for (Long key : keys) {
            var insert = jdbc.sql("insert into " + link.table() + " (" + link.ownerColumn() + ", " + link.targetColumn()
                            + kindColumn + ", position) values (:rid, :key" + kindValue + ", :position)")
                    .param("rid", id)
                    .param("key", key)
                    .param("position", position++);
            if (link.kind() != null) insert = insert.param("kind", link.kind());
            insert.update();
        }
    }

    /** Deletes the record with the rows of its link tables; false when nothing was deleted. */
    public boolean delete(EntityDefinition entity, long id) {
        EntityModel model = model(entity);
        model.fields().stream()
                .map(field -> field.source())
                .filter(FieldSource.Link.class::isInstance)
                .map(FieldSource.Link.class::cast)
                .forEach(link -> jdbc.sql("delete from " + link.table() + " l where l." + link.ownerColumn() + " = :rid"
                                + link.kindSql("l"))
                        .param("rid", id)
                        .update());
        return jdbc.sql("delete from " + model.table() + " where id = :rid")
                        .param("rid", id)
                        .update()
                > 0;
    }

    /**
     * Archives or restores the record as {@code userId} (ADR-0032, 5.4), keeping who archived it and when, and raises
     * its revision; empty when nothing was written.
     */
    public Optional<Long> archive(EntityDefinition entity, long id, boolean archived, long userId) {
        String sql = """
                update %s
                set archived_at = case when cast(:archived as boolean) then clock_timestamp() end,
                    archived_by = case when cast(:archived as boolean) then cast(:uid as bigint) end,
                    modified_by = :uid,
                    modified_at = clock_timestamp(),
                    revision = revision + 1
                where id = :rid and (archived_at is not null) <> cast(:archived as boolean)
                returning revision
                """.formatted(model(entity).table());
        return jdbc.sql(sql)
                .param("archived", archived)
                .param("uid", userId)
                .param("rid", id)
                .query(Long.class)
                .optional();
    }

    /**
     * Of {@code ids}, the rows of the target entity in {@code scope}, each with whether it is archived (ADR-0032, 4.2
     * and 5.4): one statement per field, whatever the number of keys.
     */
    public Map<Long, Boolean> visibleRows(EntityDefinition target, Set<Long> ids, QueryPlan.SqlFragment scope) {
        EntityModel model = model(target);
        String archived = target.capabilities().contains(EntityCapability.ARCHIVE)
                ? "(" + model.alias() + ".archived_at is not null)"
                : "false";
        Map<String, Object> params = new LinkedHashMap<>(scope.params());
        params.put("ids", List.copyOf(ids));
        Map<Long, Boolean> rows = new LinkedHashMap<>();
        jdbc.sql("select " + model.alias() + ".id as rid, " + archived + " as archived from " + model.table() + " "
                        + model.alias() + " where " + model.alias() + ".id in (:ids)" + scope.sql())
                .params(params)
                .query((rs, rowNum) -> Map.entry(rs.getLong("rid"), rs.getBoolean("archived")))
                .list()
                .forEach(row -> rows.put(row.getKey(), row.getValue()));
        return rows;
    }

    private JsonColumns json(EntityModel model) {
        return new JsonColumns(mapper, model.table());
    }

    /** The JSON of the entity's table, for the values a write sends as JSON. */
    public JsonColumns json(EntityDefinition entity) {
        return json(model(entity));
    }

    private static EntityModel model(EntityDefinition entity) {
        return Objects.requireNonNull(entity.model(), () -> entity.code() + " has no table");
    }
}
