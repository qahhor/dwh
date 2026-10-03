package com.smartup24.cms.instance.ms.task.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;

import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The task types (ADR-0032, 8, step 1; plan 10/10, item 5.6): a reference entity on the general runtime
 * {@code /api/v1/entities/ms.task_types}, whose codes a task's type takes ({@code ENUM}, ADR-0032, 4.5). The code is
 * given once and kept; a type is archived instead of deleted while tasks use it, and the four system types the
 * product ships with are neither archived nor deleted ({@link MsTaskTypeHooks}); the order is a record action
 * ({@code move}, {@link MsTaskDictionaryOrder}). Types are imported from an xlsx file by their code (ADR-0032, 10.1).
 */
@Configuration
public class MsTaskTypeEntity {

    /** The entity's code and its list's. */
    public static final String CODE = "ms.task_types";

    /** A code: lower-case letters, digits and underscores, starting with a letter. */
    public static final String CODE_PATTERN = "^[a-z][a-z0-9_]{0,63}$";

    /** A colour as the screen picks it. */
    public static final String COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";

    /** The largest place in the order. */
    public static final BigDecimal MAX_ORDER = BigDecimal.valueOf(1_000_000);

    public static final EntityDefinition DEFINITION = Entity.define(CODE, MsTaskPref.FORM_TYPES)
            .table("ms_task_types", "ty")
            // Reference data: every type is seen by whoever holds the right (ADR-0013).
            .scope(EntityScope.all())
            .rights(
                    MsTaskPref.MODULE_CODE,
                    "tasks.types.rights.form",
                    Map.of(
                            "view", "tasks.types.rights.view",
                            "create", "tasks.types.rights.create",
                            "update", "tasks.types.rights.update",
                            "delete", "tasks.types.rights.delete",
                            "import", "tasks.types.rights.import"))
            .field(text("code", "tasks.types.col.code")
                    .column("code")
                    .required()
                    .length(1, 64)
                    .matching(CODE_PATTERN)
                    .readonlyOnUpdate()
                    .list(sortable().searchable()))
            .field(text("name", "tasks.types.col.name")
                    .column("name")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            .field(text("icon", "tasks.types.col.icon")
                    .column("icon")
                    .required()
                    .length(1, 64)
                    .defaultValue(FieldDefault.fixed("task_alt")))
            .field(text("color", "tasks.types.col.color")
                    .column("color")
                    .required()
                    .matching(COLOR_PATTERN)
                    .defaultValue(FieldDefault.fixed("#6366f1")))
            .field(number("sortOrder", "tasks.types.col.sort_order")
                    .column("sort_order")
                    .scale(0)
                    .range(BigDecimal.ZERO, MAX_ORDER)
                    .list(sortable()))
            .field(bool("system", "tasks.types.col.system").column("is_system").readonly())
            .section("main", "entity.section.main", "code", "name", "icon", "color")
            .section("settings", "entity.section.settings", "sortOrder", "system")
            .actions("create", "update")
            .action(MsTaskDictionaryOrder.MOVE, "update")
            .archivable()
            .actions("delete")
            .reference("code", "name", "sort_order")
            .importKey("code")
            .defaultSort("sortOrder", Entity.Sort.ASC)
            .capabilities(EntityCapability.HISTORY)
            .build();

    @Bean
    public EntityDefinition msTaskTypesEntity() {
        return DEFINITION;
    }
}
