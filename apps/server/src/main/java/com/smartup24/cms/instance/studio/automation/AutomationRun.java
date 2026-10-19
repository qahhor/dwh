package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.entity.EntityFormulaReads;
import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.formula.FormulaScope;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One run of an automation in progress (ADR-0046): who it runs as, the record it acts on as the author reads it — kept
 * up to date as its actions change it — the values before the triggering change and what the change changed. A dry run
 * applies nothing that outlives it.
 */
final class AutomationRun {
    private final long automationId;
    private final String name;
    private final long authorId;
    private final String key;
    private final AutomationForm form;
    private final long recordId;
    private final int depth;
    private final boolean dry;
    private final FormulaScope scope;
    private final Map<String, Object> old;
    private final Set<String> changed;
    private Map<String, Object> record = Map.of();
    private Map<String, Object> labels = Map.of();
    private long revision;

    /**
     * @param depth   the place of the run in a chain of automations, from 1
     * @param key     what makes the deliveries of the run unique: the run's id, or a fresh key for a dry run
     * @param old     the values before the triggering change; the record's own for a scheduled or a dry run
     * @param changed the fields the triggering change changed
     */
    AutomationRun(
            long automationId,
            String name,
            long authorId,
            String key,
            AutomationForm form,
            long recordId,
            int depth,
            boolean dry,
            ZoneId zone,
            Instant now,
            Map<String, Object> old,
            Set<String> changed) {
        this.automationId = automationId;
        this.name = name;
        this.authorId = authorId;
        this.key = key;
        this.form = form;
        this.recordId = recordId;
        this.depth = depth;
        this.dry = dry;
        this.scope = new FormulaScope(zone, now);
        this.old = Collections.unmodifiableMap(new LinkedHashMap<>(old));
        this.changed = Set.copyOf(changed);
    }

    long automationId() {
        return automationId;
    }

    String name() {
        return name;
    }

    long authorId() {
        return authorId;
    }

    String key() {
        return key;
    }

    AutomationForm form() {
        return form;
    }

    long recordId() {
        return recordId;
    }

    /** The place of the run in a chain of automations, from 1. */
    int depth() {
        return depth;
    }

    boolean dry() {
        return dry;
    }

    FormulaScope scope() {
        return scope;
    }

    Map<String, Object> record() {
        return record;
    }

    Map<String, Object> labels() {
        return labels;
    }

    long revision() {
        return revision;
    }

    /** The record as the runtime answered it last. */
    void read(EntityRecordView view) {
        Map<String, Object> values = new LinkedHashMap<>(view.properties());
        values.remove(EntityRecordView.ACTIONS);
        values.remove(EntityRecordView.LABELS);
        this.record = values;
        this.labels = view.labels();
        this.revision = view.revision();
    }

    /** The record of a deleted record: its values as the triggering change left them. */
    void readGone(Map<String, Object> values) {
        this.record = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.labels = Map.of();
    }

    /** The values a formula of the run reads: the record's, {@code old.<field>} and {@code changed.<field>}. */
    Function<String, @Nullable Object> values() {
        Function<String, @Nullable Object> current = EntityFormulaReads.record(record);
        return name -> {
            if (name.startsWith(AutomationForm.OLD)) return old.get(name.substring(AutomationForm.OLD.length()));
            if (name.startsWith(AutomationForm.CHANGED)) {
                return changed.contains(name.substring(AutomationForm.CHANGED.length()));
            }
            return current.apply(name);
        };
    }
}
