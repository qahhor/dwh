package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityFieldValues;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.event.EntityEventType;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.common.entity.hook.EntityArchive;
import com.smartup24.cms.instance.common.entity.hook.EntityDelete;
import com.smartup24.cms.instance.common.entity.hook.EntityOperation;
import com.smartup24.cms.instance.common.entity.hook.EntityValues;
import com.smartup24.cms.instance.common.entity.store.EntityStoreRepository;
import com.smartup24.cms.instance.common.entity.store.EntityWrite;
import com.smartup24.cms.instance.common.entity.workflow.EntityTransition;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Steps 4–13 of ADR-0032, 6.3, run by {@link EntityRuntime} in the transaction of the request: the record read in its
 * scope for update, its revision, the body field by field, the values prepared and checked — every problem in one 422
 * — the hooks, the write, the audit and the event. The order is fixed; a hook cannot change it. A document's rows are
 * checked and written with the record (ADR-0032, 9.1); a state of its process locks what it names, and a transition
 * runs the same steps as a record action (ADR-0032, 9.2).
 */
@Component
public class EntityWrites {

    /** The action the audit row of an imported save names (ADR-0032, 19, question 7: the source of the change). */
    public static final String IMPORT_SOURCE = "import";

    private final EntityReads reads;
    private final EntityStoreRepository store;
    private final EntityFieldValues fieldValues;
    private final EntitySaveChecks checks;
    private final EntityChanges changes;
    private final EntityEvents events;
    private final EntityLines lines;

    public EntityWrites(
            EntityReads reads,
            EntityStoreRepository store,
            EntityFieldValues fieldValues,
            EntitySaveChecks checks,
            EntityChanges changes,
            EntityEvents events,
            EntityLines lines) {
        this.reads = reads;
        this.store = store;
        this.fieldValues = fieldValues;
        this.checks = checks;
        this.changes = changes;
        this.events = events;
        this.lines = lines;
    }

    /**
     * The values a save writes, its custom field values (null: as they are), the record as it will be and the rows of
     * its collections the save sends.
     */
    private record Prepared(
            Map<String, Object> values,
            @Nullable Map<String, Object> attributes,
            Map<String, Object> record,
            EntityLines.Prepared rows) {}

    Map<String, Object> create(EntityDefinition entity, @Nullable JsonNode body) {
        return create(entity, body, false);
    }

    /**
     * Creates a record; a row of an import (ADR-0032, 10.1) runs the same steps, its hooks see {@code imported()} and
     * its audit row names the import as its source.
     */
    Map<String, Object> create(EntityDefinition entity, @Nullable JsonNode body, boolean imported) {
        long user = EntityReads.userId();
        Prepared prepared = prepare(entity, EntityRequestReader.read(entity, body), null, null, user);
        RuntimeSave save = save(entity, EntityOperation.CREATE, null, null, prepared.record(), null, Map.of());
        if (imported) save.asImported();
        changes.beforeSave(save);
        EntityWrite write = EntityWrite.of(entity, written(entity, prepared, save), store.json(entity));
        long id = store.insert(entity, write, prepared.attributes(), user);
        changes.writeExtras(entity, id, write);
        lines.write(entity, id, prepared.rows());
        save.written(id);
        Map<String, Object> after = reads.visible(entity, id, false);
        List<String> changed = changes.audit(entity, id, "I", null, after, source(imported));
        changes.afterSave(save);
        events.changed(entity, id, EntityReads.revision(after), EntityEventType.CREATED, null, changed, user);
        return reads.visible(entity, id, false);
    }

    Map<String, Object> update(EntityDefinition entity, long id, long expected, @Nullable JsonNode body) {
        Map<String, Object> before = reads.visible(entity, id, true);
        requireRevision(before, expected);
        return update(entity, id, before, body, false);
    }

    /**
     * Changes a record from the revision it has now, as a row of an import (ADR-0032, 10.1): without If-Match — the
     * last write wins (ADR-0032, 19, question 7: the proposed default, taken as an assumption) — and audited with the
     * import as its source.
     */
    Map<String, Object> importUpdate(EntityDefinition entity, long id, @Nullable JsonNode body) {
        return update(entity, id, reads.visible(entity, id, true), body, true);
    }

    private Map<String, Object> update(
            EntityDefinition entity, long id, Map<String, Object> before, @Nullable JsonNode body, boolean imported) {
        long user = EntityReads.userId();
        long expected = EntityReads.revision(before);
        Prepared prepared = prepare(entity, EntityRequestReader.read(entity, body), before, id, user);
        RuntimeSave save = save(entity, EntityOperation.UPDATE, id, before, prepared.record(), null, Map.of());
        if (imported) save.asImported();
        changes.beforeSave(save);
        EntityWrite write = EntityWrite.of(entity, written(entity, prepared, save), store.json(entity));
        if (store.update(entity, id, expected, write, prepared.attributes(), user)
                .isEmpty()) {
            throw Revisions.conflict();
        }
        changes.writeExtras(entity, id, write);
        lines.write(entity, id, prepared.rows());
        Map<String, Object> after = reads.visible(entity, id, false);
        List<String> changed = changes.audit(entity, id, "U", before, after, source(imported));
        changes.afterSave(save);
        events.changed(entity, id, EntityReads.revision(after), EntityEventType.UPDATED, null, changed, user);
        return reads.visible(entity, id, false);
    }

    /** What the audit row of a save names as its action: the import for a row of one, nothing otherwise. */
    private static @Nullable String source(boolean imported) {
        return imported ? IMPORT_SOURCE : null;
    }

    /** Deletes the record; with a revision only from it (ADR-0032, 5.3), without one from whatever it is. */
    void delete(EntityDefinition entity, long id, @Nullable Long expected) {
        long user = EntityReads.userId();
        Map<String, Object> before = reads.visible(entity, id, true);
        if (expected != null) requireRevision(before, expected);
        EntityDelete delete =
                new EntityDelete(entity, id, EntityValues.readOnly(entity, before), AuditActor.user(user));
        changes.beforeDelete(delete);
        if (!store.delete(entity, id)) throw Revisions.conflict();
        changes.detach(entity, id);
        changes.audit(entity, id, "D", before, null, null);
        changes.afterDelete(delete);
        events.changed(entity, id, EntityReads.revision(before), EntityEventType.DELETED, null, List.of(), user);
    }

    /**
     * Archives or restores the record (ADR-0032, 5.4), a switch of ADR-0023: written and audited only when the state
     * changes; with a revision only from it — a stale one is 409 — without one (a bulk action) from whatever it is. The
     * entity's {@code beforeArchive} hook may refuse the switch before it is written.
     */
    Map<String, Object> archive(EntityDefinition entity, long id, @Nullable Long expected, boolean archived) {
        long user = EntityReads.userId();
        Map<String, Object> before = reads.visible(entity, id, true);
        if (expected != null) requireRevision(before, expected);
        if (Boolean.valueOf(archived).equals(before.get(EntityModel.ARCHIVED))) return before;
        changes.beforeArchive(
                new EntityArchive(entity, id, EntityValues.readOnly(entity, before), archived, AuditActor.user(user)));
        long revision = store.archive(entity, id, archived, user).orElseThrow(Revisions::conflict);
        changes.auditArchive(entity, id, archived);
        EntityEventType type = archived ? EntityEventType.ARCHIVED : EntityEventType.RESTORED;
        events.changed(entity, id, revision, type, null, List.of(EntityModel.ARCHIVED), user);
        return reads.visible(entity, id, false);
    }

    /**
     * Runs a declared record action (ADR-0032, 6.7): its handler changes the record's values in place of
     * {@code beforeSave}; the runtime writes them — the revision rises even when no value changes — audits the change
     * with the action's code and publishes it as the action's event.
     */
    Map<String, Object> action(
            EntityDefinition entity, long id, long expected, EntityActionHandler handler, Map<String, Object> params) {
        long user = EntityReads.userId();
        Map<String, Object> before = reads.visible(entity, id, true);
        requireRevision(before, expected);
        String code = handler.action();
        RuntimeSave call = save(entity, EntityOperation.ACTION, id, before, before, code, params);
        handler.run(call);
        EntityChanges.refuseRejected(call);
        Map<String, Object> touched = touched(entity, call.values().asMap(), before);
        EntityWrite write = EntityWrite.of(entity, touched, store.json(entity));
        if (store.update(entity, id, expected, write, null, user).isEmpty()) throw Revisions.conflict();
        changes.writeExtras(entity, id, write);
        Map<String, Object> after = reads.visible(entity, id, false);
        List<String> changed = changes.audit(entity, id, "U", before, after, code);
        changes.afterSave(call);
        events.changed(entity, id, EntityReads.revision(after), EntityEventType.ACTION, code, changed, user);
        return reads.visible(entity, id, false);
    }

    /**
     * A transition of the entity's process (ADR-0032, 9.2): from the record's state only — 422
     * {@code entity_transition_not_allowed} otherwise — the record moves to the transition's state if it meets the
     * transition's rules; the hooks see the save with the transition's code in place of an action's handler
     * ({@code beforeSave} and {@code afterSave} with {@link EntityOperation#ACTION}); the runtime writes, audits the
     * change with the code and publishes it as the transition's event.
     */
    Map<String, Object> transition(
            EntityDefinition entity, long id, long expected, EntityTransition transition, Map<String, Object> params) {
        long user = EntityReads.userId();
        Map<String, Object> before = reads.visible(entity, id, true);
        requireRevision(before, expected);
        EntityProcess.requireAllowed(entity, transition, before);
        String status = Objects.requireNonNull(
                        Objects.requireNonNull(entity.model()).workflow())
                .field();
        Map<String, Object> record = new LinkedHashMap<>(before);
        record.put(status, transition.to());
        List<FieldErrorItem> problems = EntityProcess.rules(
                transition, EntityValues.readOnly(entity, record), EntityValues.readOnly(entity, before));
        if (!problems.isEmpty()) {
            throw ApiException.validation("error.common.record_fields_invalid", problems);
        }
        String code = transition.code();
        RuntimeSave save = save(entity, EntityOperation.ACTION, id, before, record, code, params);
        changes.beforeSave(save);
        EntityWrite write = EntityWrite.of(entity, touched(entity, save.values().asMap(), before), store.json(entity));
        if (store.update(entity, id, expected, write, null, user).isEmpty()) throw Revisions.conflict();
        changes.writeExtras(entity, id, write);
        Map<String, Object> after = reads.visible(entity, id, false);
        List<String> changed = changes.audit(entity, id, "U", before, after, code);
        changes.afterSave(save);
        events.changed(entity, id, EntityReads.revision(after), EntityEventType.ACTION, code, changed, user);
        return reads.visible(entity, id, false);
    }

    /**
     * Steps 6–8: the body's problems, the fields and rows the state of the process locks, the values prepared by the
     * field rules (read-only fields, defaults, conditions, enumerations, files), the rows of the collections, then the
     * references, the unit, the rules and the custom fields — one 422 with them all.
     */
    private Prepared prepare(
            EntityDefinition entity,
            EntityRequestReader.Body body,
            @Nullable Map<String, Object> before,
            @Nullable Long id,
            long user) {
        List<FieldErrorItem> errors = new ArrayList<>(body.errors());
        Map<String, Object> current = before == null ? Map.of() : before;
        Set<String> locked = EntityProcess.locked(entity, before);
        errors.addAll(EntityProcess.lockProblems(entity, body.values(), current, locked));
        Set<String> refused = new HashSet<>();
        errors.forEach(error -> refused.add(error.field()));
        Map<String, Object> sent = new LinkedHashMap<>(body.values());
        sent.keySet().removeAll(refused);
        EntityFieldValues.Prepared values = fieldValues.prepareAll(entity, sent, before, id, user);
        values.errors().stream()
                .filter(error -> !refused.contains(error.field()))
                .forEach(errors::add);
        Map<String, Object> record = new LinkedHashMap<>(current);
        record.putAll(values.values());
        EntityLines.Prepared rows = lines.prepare(entity, body.collections(), current, record, locked);
        errors.addAll(rows.errors());
        record.putAll(rows.asRecord());
        if (errors.isEmpty()) {
            errors.addAll(checks.references(entity, values.values(), current, user));
            errors.addAll(checks.unit(entity, values.values(), current, user));
        }
        EntityValues was = before == null ? null : EntityValues.readOnly(entity, before);
        errors.addAll(checks.rules(entity, EntityValues.readOnly(entity, record), was));
        EntitySaveChecks.Checked attributes = checks.attributes(entity, attributes(current, body.attributes()));
        errors.addAll(attributes.errors());
        if (!errors.isEmpty()) {
            throw ApiException.validation("error.common.record_fields_invalid", errors);
        }
        return new Prepared(values.values(), attributes.attributes(), record, rows);
    }

    /** What the save writes: the prepared values and every value the hook changed (ADR-0032, 6.5). */
    private static Map<String, Object> written(EntityDefinition entity, Prepared prepared, RuntimeSave save) {
        Map<String, Object> written = new LinkedHashMap<>(prepared.values());
        written.putAll(touched(entity, save.values().asMap(), prepared.record()));
        return written;
    }

    /**
     * The custom field values a save keeps: the record's, with the values the body sends over them — a body may send
     * only the values it changes. Null when the body sends none: the record keeps its own.
     */
    private static @Nullable Map<String, Object> attributes(
            Map<String, Object> current, @Nullable Map<String, Object> sent) {
        if (sent == null) return null;
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current.get(EntityModel.ATTRIBUTES) instanceof Map<?, ?> kept) {
            kept.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        merged.putAll(sent);
        return merged;
    }

    /** The values an action's handler changed, by key. */
    private static Map<String, Object> touched(
            EntityDefinition entity, Map<String, Object> now, Map<String, Object> before) {
        Map<String, Object> touched = new LinkedHashMap<>();
        now.forEach((key, value) -> {
            if (entity.fieldsByKey().containsKey(key) && !Objects.equals(value, before.get(key))) {
                touched.put(key, value);
            }
        });
        return touched;
    }

    private static RuntimeSave save(
            EntityDefinition entity,
            EntityOperation operation,
            @Nullable Long id,
            @Nullable Map<String, Object> before,
            Map<String, Object> record,
            @Nullable String action,
            Map<String, Object> params) {
        return new RuntimeSave(
                entity,
                operation,
                id,
                before == null ? null : EntityValues.readOnly(entity, before),
                EntityValues.writable(entity, record),
                action,
                params,
                AuditActor.user(EntityReads.userId()));
    }

    /** Step 5: the record is still at the revision the change names (ADR-0024), 409 otherwise. */
    private static void requireRevision(Map<String, Object> record, long expected) {
        if (EntityReads.revision(record) != expected) throw Revisions.conflict();
    }
}
