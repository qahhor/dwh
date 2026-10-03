package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.FieldValueRules;
import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.hook.EntityActionCall;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A save as the hooks and an action's handler see it (ADR-0032, 6.5 and 6.7); the problems they add are kept here. */
final class RuntimeSave implements EntityActionCall {

    private final EntityDefinition entity;
    private final EntityOperation operation;
    private @Nullable Long id;
    private final @Nullable EntityValues before;
    private final EntityValues values;
    private final @Nullable String action;
    private final Map<String, Object> params;
    private final AuditActor actor;
    private boolean imported;
    private final List<FieldErrorItem> rejected = new ArrayList<>();

    RuntimeSave(
            EntityDefinition entity,
            EntityOperation operation,
            @Nullable Long id,
            @Nullable EntityValues before,
            EntityValues values,
            @Nullable String action,
            Map<String, Object> params,
            AuditActor actor) {
        this.entity = entity;
        this.operation = operation;
        this.id = id;
        this.before = before;
        this.values = values;
        this.action = action;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.actor = actor;
    }

    @Override
    public EntityDefinition entity() {
        return entity;
    }

    @Override
    public EntityOperation operation() {
        return operation;
    }

    @Override
    public @Nullable Long id() {
        return id;
    }

    /** The record is written: its id is known from {@code afterSave} on. */
    void written(long recordId) {
        this.id = recordId;
    }

    @Override
    public @Nullable EntityValues before() {
        return before;
    }

    @Override
    public EntityValues values() {
        return values;
    }

    @Override
    public @Nullable String action() {
        return action;
    }

    @Override
    public AuditActor actor() {
        return actor;
    }

    @Override
    public boolean imported() {
        return imported;
    }

    /** The save is a row of an import (ADR-0032, 10.1). */
    RuntimeSave asImported() {
        this.imported = true;
        return this;
    }

    @Override
    public boolean changed(String key) {
        FormField field = entity.fieldsByKey().get(key);
        Object now = values.get(key);
        Object was = before == null ? null : Objects.requireNonNull(before).get(key);
        if (field != null) return !FieldValueRules.same(field, now, was);
        return !Objects.equals(now, was);
    }

    @Override
    public void reject(String fieldPath, String code, String messageKey, Map<String, ?> params) {
        rejected.add(FieldErrorItem.keyed(fieldPath, code, messageKey, params));
    }

    @Override
    public Map<String, Object> params() {
        return params;
    }

    List<FieldErrorItem> rejected() {
        return List.copyOf(rejected);
    }
}
