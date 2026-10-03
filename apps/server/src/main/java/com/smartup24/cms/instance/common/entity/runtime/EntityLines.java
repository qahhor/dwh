package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.entity.FieldValueRules;
import com.smartup24.cms.instance.common.entity.store.EntityCollectionStore;
import com.smartup24.cms.instance.common.entity.store.EntityCollectionStore.Row;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The rows of a document in the runtime's steps (ADR-0032, 9.1; plan 10/10, item 5.7): read with the record, checked
 * row by row with the rules of their fields — every problem addressed {@code lines[3].qty} by the row's place in the
 * body — and written in the transaction of the record, replacing the rows by id: a row with an id is changed, a row
 * without one is inserted, a row the save leaves out is deleted. A row with the id of a row of another record is
 * {@code lines[i].id} {@code not_found}; more rows than the collection takes are {@code lines} {@code too_many}; rows a
 * state of the process locks are {@code lines} {@code readonly}.
 */
@Component
public class EntityLines {

    private final EntityCollectionStore store;

    public EntityLines(EntityCollectionStore store) {
        this.store = store;
    }

    /**
     * The rows a save writes and the problems found in them.
     *
     * @param rows   the rows of each collection the save sends, by the collection's key, in their order
     * @param errors the problems, each addressed to its row and field
     */
    public record Prepared(Map<String, List<Row>> rows, List<FieldErrorItem> errors) {
        public Prepared {
            rows = Collections.unmodifiableMap(new LinkedHashMap<>(rows));
            errors = List.copyOf(errors);
        }

        /** The rows as the record holds them for the rules and the hooks: by key, each row's id and values. */
        public Map<String, Object> asRecord() {
            Map<String, Object> record = new LinkedHashMap<>();
            rows.forEach((key, list) ->
                    record.put(key, list.stream().map(EntityLines::values).toList()));
            return record;
        }
    }

    /** The record with the rows of each of its collections, in their order (ADR-0032, 6.2). */
    public Map<String, Object> withRows(EntityDefinition entity, Map<String, Object> record) {
        EntityModel model = entity.model();
        if (model == null || model.collections().isEmpty()) return record;
        Map<String, Object> full = new LinkedHashMap<>(record);
        long id = EntityReads.id(record);
        for (EntityCollection collection : model.collections()) {
            full.put(collection.key(), store.rows(model, collection, id));
        }
        return full;
    }

    /**
     * Checks the rows a save sends (steps 6–8 of ADR-0032, 6.3).
     *
     * @param sent    the rows of each collection the body sends, as read
     * @param current the record before the save with its rows, empty for a create
     * @param record  the record as the save leaves it: the currency of the document is read from it
     * @param locked  the keys the state of the process locks
     */
    public Prepared prepare(
            EntityDefinition entity,
            Map<String, List<Map<String, @Nullable Object>>> sent,
            Map<String, ?> current,
            Map<String, ?> record,
            Set<String> locked) {
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        List<FieldErrorItem> errors = new ArrayList<>();
        for (EntityCollection collection : EntityProcess.collections(entity)) {
            List<Map<String, @Nullable Object>> given = sent.get(collection.key());
            if (given == null) continue;
            if (given.size() > collection.maxRows()) {
                errors.add(FieldErrorItem.keyed(
                        collection.key(),
                        FieldValueRules.TOO_MANY,
                        "error.field.too_many",
                        Map.of("max", collection.maxRows())));
                continue;
            }
            List<Map<String, Object>> before = rowsOf(current.get(collection.key()));
            List<Row> checked = check(entity, collection, given, before, record, errors);
            if (locked.contains(collection.key()) && changes(collection, checked, before)) {
                errors.add(FieldErrorItem.keyed(collection.key(), EntityFieldRights.READONLY, "error.field.readonly"));
            }
            rows.put(collection.key(), checked);
        }
        return new Prepared(rows, errors);
    }

    /** Writes the rows of every collection the save sends (step 10 of ADR-0032, 6.3). */
    public void write(EntityDefinition entity, long id, Prepared prepared) {
        for (EntityCollection collection : EntityProcess.collections(entity)) {
            List<Row> rows = prepared.rows().get(collection.key());
            if (rows != null) store.replace(collection, id, rows);
        }
    }

    /**
     * What a save changed in a collection, as the audit keeps it (ADR-0032, 6.8): the rows added whole, of a changed
     * row its id and the values it changed, the ids of the rows removed, and the number of rows after — the fields with
     * history only, so a computed value is not kept; empty when nothing changed.
     */
    public static Map<String, Object> change(EntityCollection collection, List<?> before, List<?> after) {
        Map<Long, Map<String, Object>> was = new LinkedHashMap<>();
        rowsOf(before).forEach(row -> was.put(EntityReads.id(row), row));
        List<Object> added = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        for (Map<String, Object> row : rowsOf(after)) {
            Map<String, Object> old = was.remove(EntityReads.id(row));
            if (old == null) {
                added.add(audited(collection, row));
                continue;
            }
            Map<String, Object> difference = new LinkedHashMap<>();
            for (EntityField field : collection.fields()) {
                if (field.history() && !Objects.equals(old.get(field.key()), row.get(field.key()))) {
                    difference.put(field.key(), row.get(field.key()));
                }
            }
            if (!Objects.equals(old.get(EntityCollection.POSITION), row.get(EntityCollection.POSITION))) {
                difference.put(EntityCollection.POSITION, row.get(EntityCollection.POSITION));
            }
            if (!difference.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(EntityCollection.ID, row.get(EntityCollection.ID));
                entry.putAll(difference);
                changed.add(entry);
            }
        }
        if (added.isEmpty() && changed.isEmpty() && was.isEmpty()) return Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", after.size());
        if (!added.isEmpty()) summary.put("added", added);
        if (!changed.isEmpty()) summary.put("changed", changed);
        if (!was.isEmpty()) summary.put("removed", List.copyOf(was.keySet()));
        return summary;
    }

    /** A row as the audit keeps it: its id and the values of the fields with history, not the computed ones. */
    private static Map<String, Object> audited(EntityCollection collection, Map<String, Object> row) {
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put(EntityCollection.ID, row.get(EntityCollection.ID));
        for (EntityField field : collection.fields()) {
            if (field.history() && row.get(field.key()) != null) kept.put(field.key(), row.get(field.key()));
        }
        return kept;
    }

    private static List<Row> check(
            EntityDefinition entity,
            EntityCollection collection,
            List<Map<String, @Nullable Object>> given,
            List<Map<String, Object>> before,
            Map<String, ?> record,
            List<FieldErrorItem> errors) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        before.forEach(row -> byId.put(EntityReads.id(row), row));
        EntityDefinition line = collection.line(entity.code());
        List<Row> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < given.size(); index++) {
            String at = collection.key() + "[" + index + "]";
            Map<String, @Nullable Object> sent = given.get(index);
            Long id = sent.get(EntityCollection.ID) instanceof Number number ? number.longValue() : null;
            Map<String, Object> base = id == null ? Map.of() : byId.get(id);
            if (base == null) {
                errors.add(
                        FieldErrorItem.keyed(at + "." + EntityCollection.ID, "not_found", "error.field.ref_not_found"));
                continue;
            }
            if (id != null && !seen.add(id)) {
                // A row is named once: a second row with its id would overwrite the first.
                errors.add(FieldErrorItem.keyed(
                        at + "." + EntityCollection.ID, EntityValidator.INVALID, "error.field.keys_repeated"));
                continue;
            }
            Map<String, @Nullable Object> values = new LinkedHashMap<>();
            for (EntityField field : collection.written()) {
                if (base.containsKey(field.key())) values.put(field.key(), base.get(field.key()));
            }
            List<FieldErrorItem> problems = new ArrayList<>();
            for (FormField field : line.fields()) {
                if (!sent.containsKey(field.key())) continue;
                Object value = sent.get(field.key());
                values.put(field.key(), value == null ? null : normalize(collection, field, value, record, problems));
            }
            problems.addAll(EntityValidator.problems(line, values, false));
            problems.forEach(problem -> errors.add(problem.at(at + "." + problem.field())));
            rows.add(new Row(id, values));
        }
        return rows;
    }

    /**
     * A value in the form a row keeps; money in the document's currency becomes {@code {amount, currency}} with that
     * currency, and another currency is refused.
     */
    private static Object normalize(
            EntityCollection collection,
            FormField field,
            Object value,
            Map<String, ?> record,
            List<FieldErrorItem> problems) {
        String from = collection
                .field(field.key())
                .map(declared -> declared.options().currencyFrom())
                .orElse(null);
        if (field.type() != FieldType.MONEY || from == null) return FieldValueRules.normalize(field, value);
        Object currency = record.get(from);
        Object amount = value instanceof Map<?, ?> money ? money.get("amount") : value;
        Object sentCurrency = value instanceof Map<?, ?> money ? money.get("currency") : null;
        if (sentCurrency != null && !Objects.equals(String.valueOf(sentCurrency), String.valueOf(currency))) {
            problems.add(FieldErrorItem.keyed(
                    field.key(),
                    EntityValidator.INVALID,
                    "error.field.currency_not_allowed",
                    Map.of("allowed", String.valueOf(currency))));
        }
        Map<String, @Nullable Object> money = new LinkedHashMap<>();
        money.put("amount", amount == null ? null : String.valueOf(amount));
        money.put("currency", currency);
        return money;
    }

    /** Whether the rows a save sends differ from the record's: another number, order, id or value of a field. */
    private static boolean changes(EntityCollection collection, List<Row> rows, List<Map<String, Object>> before) {
        if (rows.size() != before.size()) return true;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            Map<String, Object> kept = before.get(index);
            Long id = row.id();
            if (id == null || id != EntityReads.id(kept)) return true;
            for (EntityField field : collection.written()) {
                FormField form = Objects.requireNonNull(field.formField());
                if (!FieldValueRules.same(form, row.values().get(field.key()), kept.get(field.key()))) return true;
            }
        }
        return false;
    }

    private static Map<String, Object> values(Row row) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (row.id() != null) values.put(EntityCollection.ID, row.id());
        row.values().forEach((key, value) -> {
            if (value != null) values.put(key, value);
        });
        return values;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(@Nullable Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> row) rows.add((Map<String, Object>) row);
        }
        return rows;
    }
}
