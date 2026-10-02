package com.smartup24.cms.instance.ms.task.service;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.number;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.textarea;

import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.EntityTab;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.entity.search.EntitySearchSpec;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The projects (ADR-0032, 8, step 3; plan 10/10, item 5.6) on the general runtime {@code /api/v1/entities/ms.projects}.
 * A project is seen by the rule of ADR-0013 for projects — its author, its members and the participants of its tasks,
 * by the viewer's own rule ({@link MdScopeService#filterForProjects}); a paused project is an archived one. Members are
 * the record actions {@code add_member} and {@code remove_member} ({@link MsProjectMemberActions}) until collections
 * come (ADR-0032, 9.1); their page and the progress of each project over the viewer's tasks are read from the module
 * ({@code MsProjectController}).
 */
@Configuration
public class MsProjectEntity {

    /** The entity's code and its list's. */
    public static final String CODE = "ms.projects";

    /** The alias the reviewed project predicate of ADR-0013 is written for. */
    public static final String ALIAS = "p";

    public static final String ADD_MEMBER = "add_member";
    public static final String REMOVE_MEMBER = "remove_member";

    /**
     * The people of a project — its author, its members and the participants of its tasks — whose own rule
     * ({@code SELF}) sees it and whose org units open it to {@code SUBTREE}/{@code UNITS}: the scope keys of its search
     * document, the same people {@link MdScopeService#filterForProjects} reads.
     */
    static final String PARTICIPANTS = "array(select p.created_by"
            + " union select pm.user_id from ms_task_project_members pm where pm.project_id = p.id"
            + " union select pt.created_by from ms_tasks pt where pt.project_id = p.id"
            + " union select pt.reporter_id from ms_tasks pt where pt.project_id = p.id"
            + " union select ptm.user_id from ms_task_members ptm join ms_tasks pt on pt.id = ptm.task_id"
            + " where pt.project_id = p.id)";

    /** The declaration with the rule that says which projects a viewer sees. */
    public static EntityDefinition definition(EntityScope.ScopeProvider visible) {
        return Entity.define(CODE, MsTaskPref.FORM_PROJECTS)
                .table("ms_task_projects", ALIAS)
                .scope(EntityScope.custom("projects", visible))
                .rights(
                        MsTaskPref.MODULE_CODE,
                        "projects.rights.form",
                        Map.of(
                                "view", "projects.rights.view",
                                "create", "projects.rights.create",
                                "update", "projects.rights.update"))
                .field(number("id", "projects.col.id")
                        .system(SystemColumn.ID)
                        .list(sortable().hidden()))
                .field(text("name", "projects.col.name")
                        .column("name")
                        .required()
                        .length(1, 255)
                        .list(sortable().searchable()))
                .field(textarea("description", "projects.col.description")
                        .column("description")
                        .length(null, 10_000)
                        .list(searchable().hidden()))
                .field(instant("createdAt", "projects.col.created_at")
                        .system(SystemColumn.CREATED_AT)
                        .list(sortable()))
                .field(instant("modifiedAt", "projects.col.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable().hidden()))
                .section("main", "entity.section.main", "name", "description")
                // The card of the general screen: the fields, the project's tasks as a related list of ms.tasks (read
                // with the tasks' own rights and scope, ADR-0032, 9.3) and the history.
                .tab(EntityTab.sections("main", "ui.entity_page.tab_fields", "main"))
                .tab(EntityTab.related("tasks", "nav.tasks", MsTaskEntity.CODE, "projectId"))
                .tab(EntityTab.history("history", "ui.entity_page.tab_history"))
                // Found by the global search in the viewer's scope of projects (ADR-0013, 2.5; ADR-0032, 10.3); a hit
                // opens the project's own card. An archived project is not found.
                .search(EntitySearchSpec.title("name")
                        .body("description")
                        .route("/tasks/projects/{id}")
                        .scopeUsers(PARTICIPANTS))
                .actions("create", "update")
                .action(ADD_MEMBER, "update")
                .action(REMOVE_MEMBER, "update")
                // Pausing a project was a change of it: the archive needs the same right.
                .archivable("update")
                .defaultSort("name", Entity.Sort.ASC)
                .customFields("PROJECT")
                .capabilities(EntityCapability.SAVED_VIEWS, EntityCapability.EXPORT, EntityCapability.HISTORY)
                .build();
    }

    @Bean
    public EntityDefinition msProjectsEntity(MdScopeService scopes) {
        return definition((userId, alias) -> {
            if (!ALIAS.equals(alias)) {
                throw new IllegalStateException("The project predicate is written for the alias " + ALIAS);
            }
            return scopes.filterForProjects(userId);
        });
    }
}
