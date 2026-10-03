package com.smartup24.cms.instance.common.entity.store;

import com.smartup24.cms.instance.common.entity.EntityRowMapper;
import com.smartup24.cms.instance.common.entity.EntitySelect;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The statements of a document's rows (ADR-0032, 9.1; plan 10/10, item 5.7), built from the collection's declaration:
 * the rows of one record in their order, and the replacement a save makes — a row the save names by id is changed, a
 * row without an id is inserted, a row the save leaves out is deleted, and every row takes the place it has in the
 * save. Only rows of the record are touched: the record's id is part of every condition. The record itself is locked
 * by the runtime before the rows are read or written.
 */
@Repository
public class EntityCollectionStore {

    /** The alias of the document in the statement of its rows: the currency of a row's money may be the document's. */
    private static final String DOCUMENT = EntityCollection.DOCUMENT_ALIAS;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public EntityCollectionStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** A row a save writes: its id, or null for a new one, and the values of its written fields by key. */
    public record Row(@Nullable Long id, Map<String, @Nullable Object> values) {
        public Row {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** The rows of the record {@code parentId}, in their order, each with its id, its place and its values. */
    public List<Map<String, Object>> rows(EntityModel parent, EntityCollection collection, long parentId) {
        String alias = collection.alias();
        JsonColumns json = new JsonColumns(mapper, collection.table());
        List<String> columns = new ArrayList<>();
        columns.add(alias + ".id as \"" + EntityCollection.ID + "\"");
        columns.add(alias + "." + collection.positionColumn() + " as \"" + EntityCollection.POSITION + "\"");
        for (EntityField field : collection.fields()) {
            columns.addAll(EntitySelect.columns(field, alias, key -> parentSql(parent, key)));
        }
        String sql = "select " + String.join(", ", columns) + " from " + collection.table() + " " + alias + " join "
                + parent.table() + " " + DOCUMENT + " on " + DOCUMENT + ".id = " + alias + "."
                + collection.parentColumn() + " where " + alias + "." + collection.parentColumn() + " = :rid order by "
                + alias + "." + collection.positionColumn() + ", " + alias + ".id";
        return jdbc.sql(sql)
                .param("rid", parentId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(EntityCollection.ID, rs.getLong(EntityCollection.ID));
                    row.put(EntityCollection.POSITION, rs.getInt(EntityCollection.POSITION));
                    for (EntityField field : collection.fields()) {
                        Object value = EntityRowMapper.fieldValue(rs, field, json);
                        if (value != null) row.put(field.key(), value);
                    }
                    return row;
                })
                .list();
    }

    /**
     * Replaces the rows of the record {@code parentId} by {@code rows}, in their order: the rows the save leaves out are
     * deleted first, then each row is changed or inserted at its place (1 for the first).
     */
    public void replace(EntityCollection collection, long parentId, List<Row> rows) {
        List<Long> kept = rows.stream().map(Row::id).filter(Objects::nonNull).toList();
        String table = collection.table();
        String parentColumn = collection.parentColumn();
        if (kept.isEmpty()) {
            jdbc.sql("delete from " + table + " where " + parentColumn + " = :rid")
                    .param("rid", parentId)
                    .update();
        } else {
            jdbc.sql("delete from " + table + " where " + parentColumn + " = :rid and id not in (:kept)")
                    .param("rid", parentId)
                    .param("kept", kept)
                    .update();
        }
        JsonColumns json = new JsonColumns(mapper, table);
        int position = 1;
        for (Row row : rows) {
            EntityWrite write = EntityWrite.of(collection.written(), row.values(), json);
            Map<String, Object> params = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            int index = 0;
            for (EntityWrite.Column column : write.columns()) {
                String parameter = "p" + index++;
                names.add(column.name());
                values.add(column.parameter(parameter));
                params.put(parameter, column.value());
            }
            params.put("rid", parentId);
            params.put("pos", position++);
            Long id = row.id();
            if (id == null) {
                names.add(parentColumn);
                values.add(":rid");
                names.add(collection.positionColumn());
                values.add(":pos");
                jdbc.sql("insert into " + table + " (" + String.join(", ", names) + ") values ("
                                + String.join(", ", values) + ")")
                        .params(params)
                        .update();
            } else {
                List<String> sets = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) {
                    sets.add(names.get(i) + " = " + values.get(i));
                }
                sets.add(collection.positionColumn() + " = :pos");
                params.put("row", id);
                jdbc.sql("update " + table + " set " + String.join(", ", sets) + " where id = :row and " + parentColumn
                                + " = :rid")
                        .params(params)
                        .update();
            }
        }
    }

    private static String parentSql(EntityModel parent, String key) {
        return parent.field(key)
                .orElseThrow(() -> new IllegalStateException(parent.table() + " has no field " + key))
                .sql(DOCUMENT);
    }
}
