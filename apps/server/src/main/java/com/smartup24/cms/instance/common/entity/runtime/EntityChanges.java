package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityAuditRow;
import com.smartup24.cms.instance.common.entity.EntityFiles;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.store.EntityStoreRepository;
import com.smartup24.cms.instance.common.entity.store.EntityWrite;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.hook.EntityArchive;
import com.smartup24.cms.platform.api.entity.hook.EntityDelete;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntityRefusal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Steps 9–12 of ADR-0032, 6.3 around the write of a record: the entity's hooks before and after it, the rows of its
 * link tables and the attachments of its file fields (ADR-0032, 4.7), and its audit row (ADR-0032, 6.8) — every field
 * the entity declares and every custom field it has, under the keys of the API, written by the platform for every
 * entity.
 */
@Component
public class EntityChanges {

    /** The property of an audit row that names the record action of the change (ADR-0032, 6.8). */
    public static final String ACTION = "_action";

    private final EntityRegistry registry;
    private final EntityStoreRepository store;
    private final @Nullable EntityAuditLog audit;
    private final @Nullable EntityFiles files;

    public EntityChanges(
            EntityRegistry registry,
            EntityStoreRepository store,
            ObjectProvider<EntityAuditLog> audit,
            ObjectProvider<EntityFiles> files) {
        this.registry = registry;
        this.store = store;
        this.audit = audit.getIfAvailable();
        this.files = files.getIfAvailable();
    }

    /** {@code beforeSave} of the entity's hooks; the problems it adds answer one 422 (ADR-0032, 6.5). */
    void beforeSave(RuntimeSave save) {
        hooks(save.entity()).ifPresent(hook -> refusing(() -> hook.beforeSave(save)));
        refuseRejected(save);
    }

    void afterSave(RuntimeSave save) {
        hooks(save.entity()).ifPresent(hook -> refusing(() -> hook.afterSave(save)));
        refuseRejected(save);
    }

    void beforeDelete(EntityDelete delete) {
        hooks(delete.entity()).ifPresent(hook -> refusing(() -> hook.beforeDelete(delete)));
    }

    void afterDelete(EntityDelete delete) {
        hooks(delete.entity()).ifPresent(hook -> refusing(() -> hook.afterDelete(delete)));
    }

    void beforeArchive(EntityArchive archive) {
        hooks(archive.entity()).ifPresent(hook -> refusing(() -> hook.beforeArchive(archive)));
    }

    /**
     * Runs a hook or an action's handler: the refusal of a module outside the monorepo (ADR-0033, 3.2) answers as the
     * error of the request, like an {@code ApiException} of a built-in module.
     */
    static void refusing(Runnable hook) {
        try {
            hook.run();
        } catch (EntityRefusal refusal) {
            throw ApiException.refused(refusal);
        }
    }

    /** The problems a hook or an action's handler added: one 422, which rolls the save back. */
    static void refuseRejected(RuntimeSave save) {
        List<FieldErrorItem> rejected = save.rejected();
        if (!rejected.isEmpty()) {
            throw ApiException.validation("error.common.record_fields_invalid", rejected);
        }
    }

    /** The rows of the link tables and the attachments of the file fields a save writes (ADR-0032, 4.1 and 4.7). */
    void writeExtras(EntityDefinition entity, long id, EntityWrite write) {
        write.links().forEach((link, keys) -> store.writeLinks(link, id, keys));
        EntityFiles attachments = files;
        if (attachments == null) return;
        for (Map.Entry<String, @Nullable UUID> file : write.files().entrySet()) {
            attachments.attach(entity.code(), id, file.getKey(), file.getValue());
        }
    }

    /** Removes the attachments of a deleted record; the files go with the cleanup of the files module. */
    void detach(EntityDefinition entity, long id) {
        EntityFiles attachments = files;
        if (attachments != null && hasFiles(entity)) {
            attachments.detachAll(entity.code(), id);
        }
    }

    private static boolean hasFiles(EntityDefinition entity) {
        return Objects.requireNonNull(entity.model()).fields().stream()
                .anyMatch(field -> field.source() instanceof FieldSource.Column
                        && (field.type() == FieldType.FILE || field.type() == FieldType.IMAGE));
    }

    /**
     * Writes the audit row of a change (step 11) and gives the keys of the fields it changed, in the entity's order.
     *
     * @param before the record before, or null for a create
     * @param after  the record after, or null for a delete
     * @param action the record action, written as {@value #ACTION} of the new row, or null
     */
    List<String> audit(
            EntityDefinition entity,
            long id,
            String event,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after,
            @Nullable String action) {
        Map<String, Object> old = before == null ? null : row(entity, before);
        Map<String, Object> neu = after == null ? null : row(entity, after);
        rows(entity, before, after, old, neu);
        List<String> changed = old == null
                ? List.copyOf(Objects.requireNonNull(neu).keySet())
                : neu == null ? List.copyOf(old.keySet()) : EntityAuditRow.changed(old, neu);
        if (neu != null && action != null) {
            neu.put(ACTION, action);
        }
        EntityAuditLog log = audit;
        String table = entity.auditTable();
        if (log != null && table != null) {
            log.log(table, id, event, changed, old, neu);
        }
        return changed;
    }

    /**
     * The rows of the collections in the audit rows (ADR-0032, 6.8): one property per collection that changed — the
     * new row holds what changed ({@link EntityLines#change}), the old row the number of rows before; a deleted record
     * keeps the number of its rows.
     */
    private static void rows(
            EntityDefinition entity,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after,
            @Nullable Map<String, Object> old,
            @Nullable Map<String, Object> neu) {
        for (EntityCollection collection : EntityProcess.collections(entity)) {
            List<?> was = before != null && before.get(collection.key()) instanceof List<?> list ? list : List.of();
            if (after == null || neu == null) {
                if (old != null && !was.isEmpty()) old.put(collection.key(), Map.of("count", was.size()));
                continue;
            }
            List<?> now = after.get(collection.key()) instanceof List<?> list ? list : List.of();
            Map<String, Object> change = EntityLines.change(collection, was, now);
            if (change.isEmpty()) continue;
            neu.put(collection.key(), change);
            if (old != null) old.put(collection.key(), Map.of("count", was.size()));
        }
    }

    /** The audit row of an archive or a restore (ADR-0032, 5.4): the change of {@code archived}, labelled in history. */
    void auditArchive(EntityDefinition entity, long id, boolean archived) {
        EntityAuditLog log = audit;
        String table = entity.auditTable();
        if (log != null && table != null) {
            log.log(
                    table,
                    id,
                    "U",
                    List.of(EntityModel.ARCHIVED),
                    Map.of(EntityModel.ARCHIVED, !archived),
                    Map.of(EntityModel.ARCHIVED, archived));
        }
    }

    /** The audit row of a record: its declared fields and its custom fields (plan 10/10, item 5.0). */
    private static Map<String, Object> row(EntityDefinition entity, Map<String, Object> record) {
        Object attributes = record.get(EntityModel.ATTRIBUTES);
        return new LinkedHashMap<>(EntityAuditRow.of(
                entity, record, attributes instanceof Map<?, ?> map ? stringKeys(map) : null));
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        map.forEach((key, value) -> keyed.put(String.valueOf(key), value));
        return keyed;
    }

    private Optional<EntityHooks> hooks(EntityDefinition entity) {
        return registry.hooks(entity.code());
    }
}
