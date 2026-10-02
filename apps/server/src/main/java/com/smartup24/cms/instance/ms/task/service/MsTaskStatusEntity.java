package com.smartup24.cms.instance.ms.task.service;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.bool;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.number;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The task statuses (ADR-0032, 8, step 2; plan 10/10, item 5.6): a reference entity on the general runtime
 * {@code /api/v1/entities/ms.task_statuses} — the columns of the kanban, in their order — whose codes a task's status
 * takes ({@code ENUM}, ADR-0032, 4.5). A terminal status closes a task. The code is given once and kept; a status in use
 * is not deleted and the system statuses are neither archived nor deleted ({@link MsTaskStatusHooks}); the order is a
 * record action ({@code move}, {@link MsTaskDictionaryOrder}).
 */
@Configuration
public class MsTaskStatusEntity {

    /** The entity's code and its list's. */
    public static final String CODE = "ms.task_statuses";

    /** The status a new task starts in: a system status, so it is never archived or deleted. */
    public static final String INITIAL = MsTaskPref.STATUS_NEW;

    public static final EntityDefinition DEFINITION = Entity.define(CODE, MsTaskPref.FORM_STATUSES)
            .table("ms_task_statuses", "st")
            // Reference data: every status is seen by whoever holds the right (ADR-0013).
            .scope(EntityScope.all())
            .rights(
                    MsTaskPref.MODULE_CODE,
                    "tasks.statuses.rights.form",
                    Map.of(
                            "view", "tasks.statuses.rights.view",
                            "create", "tasks.statuses.rights.create",
                            "update", "tasks.statuses.rights.update",
                            "delete", "tasks.statuses.rights.delete"))
            // Without a code the hooks make one: a person names a status, the kanban and the tasks keep its code.
            .field(text("code", "tasks.statuses.col.code")
                    .column("code")
                    .length(1, 64)
                    .matching(MsTaskTypeEntity.CODE_PATTERN)
                    .readonlyOnUpdate()
                    .list(sortable().searchable()))
            .field(text("name", "tasks.statuses.col.name")
                    .column("name")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            .field(text("color", "tasks.statuses.col.color")
                    .column("color")
                    .required()
                    .matching(MsTaskTypeEntity.COLOR_PATTERN)
                    .defaultValue(FieldDefault.fixed("#64748b")))
            .field(number("sortOrder", "tasks.statuses.col.sort_order")
                    .column("sort_order")
                    .scale(0)
                    .range(BigDecimal.ZERO, MsTaskTypeEntity.MAX_ORDER)
                    .list(sortable()))
            .field(bool("terminal", "tasks.statuses.col.terminal").column("is_terminal"))
            .field(bool("system", "tasks.statuses.col.system")
                    .column("is_system")
                    .readonly())
            .section("main", "entity.section.main", "code", "name", "color")
            .section("settings", "entity.section.settings", "sortOrder", "terminal", "system")
            .actions("create", "update")
            .action(MsTaskDictionaryOrder.MOVE, "update")
            .archivable()
            .actions("delete")
            .reference("code", "name", "sort_order")
            .defaultSort("sortOrder", Entity.Sort.ASC)
            .capabilities(EntityCapability.HISTORY)
            .build();

    @Bean
    public EntityDefinition msTaskStatusesEntity() {
        return DEFINITION;
    }
}
