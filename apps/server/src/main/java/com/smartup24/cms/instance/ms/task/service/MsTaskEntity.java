package com.smartup24.cms.instance.ms.task.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.enumeration;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.hidden;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.markdown;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.multiRef;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;

import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserEntity;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import com.smartup24.cms.platform.api.entity.hook.Rules;
import com.smartup24.cms.platform.api.entity.search.EntitySearchSpec;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tasks (ADR-0032, 8, step 4; plan 10/10, item 5.6) on the general runtime {@code /api/v1/entities/ms.tasks}. A task
 * is seen by its participants — its author, creator and members, by the viewer's own rule (ADR-0013,
 * {@link MdScopeService#filterForTasks}, alias {@code t}). Its status and type are codes of the reference entities
 * {@code ms.task_statuses} and {@code ms.task_types} ({@code ENUM}); the status changes only by the record action
 * {@code set_status} ({@link MsTaskStatusAction}); the project and the parent task are references by entity code; the
 * responsible person, the executors and the observers are users, the executors and observers kept in the participants
 * table. {@link MsTaskHooks} keeps the rules the declaration cannot say: participants the author may see, no parent
 * cycle, the participation rows, the notifications and the search index.
 *
 * <p>The list fields {@code terminal} and {@code overdue} let a screen keep its presets as filter expressions (ADR-0016):
 * "active" — {@code terminal = false}, "overdue" — {@code overdue = true}, "mine" — {@code responsibleId = me}.
 */
@Configuration
public class MsTaskEntity {

    /** The entity's code and its list's. */
    public static final String CODE = "ms.tasks";

    /** The alias the reviewed task predicate of ADR-0013 is written for. */
    public static final String ALIAS = "t";

    /** The record action that moves a task to another status. */
    public static final String SET_STATUS = "set_status";

    public static final List<String> PRIORITIES = List.of(
            MsTaskPref.PRIORITY_LOW,
            MsTaskPref.PRIORITY_MEDIUM,
            MsTaskPref.PRIORITY_HIGH,
            MsTaskPref.PRIORITY_CRITICAL);

    /** The longest text of a task, generous but bounded. */
    public static final int MAX_DESCRIPTION = 100_000;

    /**
     * The users a participant is picked from and named by: the runtime list of the user entity (ADR-0032, 8, step 5).
     * A source, not a target: a target would ask the saver for the users' view right (ADR-0032, 4.6), which a task's
     * author need not have — {@link MsTaskHooks} checks that the author sees each new participant.
     */
    private static final QueryRef USERS = QueryRef.paged(QueryRef.entityPath(MdUserEntity.CODE), "name");

    /** Whether the task's status closes it. */
    private static final String TERMINAL =
            "coalesce((select s.is_terminal from ms_task_statuses s where s.code = t.status_code), false)";

    /**
     * The participants of a task — its creator, its author and its members — whose own rule ({@code SELF}) sees it and
     * whose org units open it to the rules {@code SUBTREE}/{@code UNITS}: the scope keys of its search document, the
     * same people {@link MdScopeService#filterForTasks} reads.
     */
    static final String PARTICIPANTS = "array(select t.created_by union select t.reporter_id"
            + " union select m.user_id from ms_task_members m where m.task_id = t.id)";

    /** The declaration with the rule that says which tasks a viewer sees. */
    public static EntityDefinition definition(EntityScope.ScopeProvider visible) {
        Entity task = Entity.define(CODE, MsTaskPref.FORM_TASKS)
                .table("ms_tasks", ALIAS)
                .scope(EntityScope.custom("tasks", visible))
                .rights(
                        MsTaskPref.MODULE_CODE,
                        "tasks.rights.form",
                        Map.of(
                                "view", "tasks.rights.view",
                                "create", "tasks.rights.create",
                                "update", "tasks.rights.update"));
        content(task);
        links(task);
        plan(task);
        return task.section("main", "entity.section.main", "title", "descriptionMarkdown", "typeCode", "priority")
                .section("people", "tasks.section.people", "responsibleId", "executorIds", "observerIds", "reporterId")
                .section(
                        "plan",
                        "tasks.section.plan",
                        "statusCode",
                        "projectId",
                        "parentTaskId",
                        "beginTime",
                        "endTime",
                        "resolvedTime")
                .rule("period", Rules.notBefore("endTime", "beginTime"))
                // Found by the global search among the tasks the viewer takes part in or whose participants stand in
                // the viewer's scope (ADR-0013, 2.5; ADR-0032, 10.3); a hit opens the task's own card.
                .search(EntitySearchSpec.title("title")
                        .body("descriptionMarkdown")
                        .route("/tasks/items/{id}")
                        .scopeUsers(PARTICIPANTS))
                .actions("create", "update")
                .action(SET_STATUS, "update")
                .defaultSort("id", Entity.Sort.ASC)
                .customFields("TASK")
                .capabilities(
                        EntityCapability.SAVED_VIEWS,
                        EntityCapability.EXPORT,
                        EntityCapability.HISTORY,
                        EntityCapability.BULK)
                .build();
    }

    /** What a task is: its number, title, text, type, status and priority. */
    private static void content(Entity task) {
        task.field(number("id", "tasks.col.id").system(SystemColumn.ID).list(sortable()));
        task.field(text("title", "tasks.col.title")
                .column("title")
                .required()
                .length(1, 500)
                .list(sortable().searchable()));
        task.field(markdown("descriptionMarkdown", "tasks.col.description")
                .column("description_markdown")
                .length(null, MAX_DESCRIPTION)
                .list(searchable().hidden()));
        task.field(enumeration("typeCode", "tasks.col.type", MsTaskTypeEntity.CODE)
                .column("type_code")
                .required()
                .defaultValue(FieldDefault.fixed("task"))
                .list(hidden()));
        task.field(enumeration("statusCode", "tasks.col.status", MsTaskStatusEntity.CODE)
                .column("status_code")
                .readonly()
                .defaultValue(FieldDefault.fixed(MsTaskStatusEntity.INITIAL)));
        task.field(select("priority", "tasks.col.priority", PRIORITIES, "tasks.priority.")
                .column("priority")
                .required()
                .defaultValue(FieldDefault.fixed(MsTaskPref.PRIORITY_MEDIUM)));
    }

    /** Where a task belongs and who takes part: its project, parent and people. */
    private static void links(Entity task) {
        task.field(ref("projectId", "tasks.col.project").column("project_id").target(MsProjectEntity.CODE, "name"));
        task.field(text("projectName", "tasks.col.project_name")
                .expression("(select p.name from ms_task_projects p where p.id = t.project_id)")
                .listOnly(hidden().notFilterable()));
        task.field(ref("parentTaskId", "tasks.col.parent")
                .column("parent_task_id")
                .target(CODE, "title")
                .list(hidden()));
        // Not a default column: naming a person reads the user, which a viewer without the users' right may not do.
        task.field(ref("responsibleId", "tasks.col.responsible", USERS)
                .column("responsible_id")
                .list(hidden()));
        task.field(multiRef("executorIds", "tasks.col.executors", USERS)
                .link("ms_task_members", "task_id", "user_id", "involve_kind", MsTaskPref.INVOLVE_EXECUTOR)
                .list(hidden()));
        task.field(multiRef("observerIds", "tasks.col.observers", USERS)
                .link("ms_task_members", "task_id", "user_id", "involve_kind", MsTaskPref.INVOLVE_OBSERVER)
                .list(hidden()));
        task.field(ref("reporterId", "tasks.col.reporter", USERS)
                .column("reporter_id")
                .readonly()
                .defaultValue(FieldDefault.currentUser())
                .list(hidden()));
    }

    /** When a task is due and in which state: its dates and the list fields of the presets. */
    private static void plan(Entity task) {
        task.field(instant("beginTime", "tasks.col.begin")
                .column("begin_time")
                .list(sortable().hidden()));
        task.field(instant("endTime", "tasks.col.due").column("end_time").list(sortable()));
        task.field(instant("resolvedTime", "tasks.col.resolved")
                .column("resolved_time")
                .readonly()
                .list(sortable().hidden()));
        task.field(bool("terminal", "tasks.col.terminal").expression(TERMINAL).listOnly(hidden()));
        task.field(bool("overdue", "tasks.col.overdue")
                .expression("(t.end_time is not null and t.end_time < clock_timestamp() and not " + TERMINAL + ")")
                .listOnly(hidden()));
        task.field(instant("createdAt", "tasks.col.created_at")
                .system(SystemColumn.CREATED_AT)
                .list(sortable().hidden()));
        task.field(instant("modifiedAt", "tasks.col.modified_at")
                .system(SystemColumn.MODIFIED_AT)
                .list(sortable().hidden()));
    }

    @Bean
    public EntityDefinition msTasksEntity(MdScopeService scopes) {
        return definition((userId, alias) -> {
            if (!ALIAS.equals(alias)) {
                throw new IllegalStateException("The task predicate is written for the alias " + ALIAS);
            }
            return scopes.filterForTasks(userId).condition();
        });
    }
}
