package com.smartup24.cms.instance.common.security;

import com.smartup24.cms.instance.common.security.ScopeFixture.Kind;
import com.smartup24.cms.instance.md.service.MdUserEntity;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.ms.task.service.MsProjectEntity;
import com.smartup24.cms.instance.ms.task.service.MsTaskEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The by-id handlers of {@link ScopeByIdMatrixIntegrationTest} and the handlers that need no data scope.
 *
 * <p>A handler is named {@code Controller#method}, not by its path, so an alias path may come or go without an
 * edit here. Every path variable takes the record's id unless the case gives it another value.
 */
final class ScopeByIdCases {

    private ScopeByIdCases() {}

    /** One request: the handler, the kind of record it addresses, extra path variables, the body, the answers. */
    record Case(
            String handler,
            String label,
            Kind kind,
            BiFunction<ScopeFixture, Object, Map<String, Object>> vars,
            BiFunction<ScopeFixture, Object, Object> body,
            Set<Integer> inScope) {

        String name() {
            return label.isEmpty() ? handler : handler + " (" + label + ")";
        }
    }

    private static final BiFunction<ScopeFixture, Object, Map<String, Object>> NO_VARS = (f, id) -> Map.of();
    private static final BiFunction<ScopeFixture, Object, Object> NO_BODY = (f, id) -> null;
    private static final Set<Integer> OK = Set.of(200);
    private static final Set<Integer> NO_CONTENT = Set.of(204);

    private static Case read(String handler, Kind kind) {
        return new Case(handler, "", kind, NO_VARS, NO_BODY, OK);
    }

    private static Case write(String handler, Kind kind, Set<Integer> inScope) {
        return new Case(handler, "", kind, NO_VARS, NO_BODY, inScope);
    }

    private static Case write(
            String handler, Kind kind, BiFunction<ScopeFixture, Object, Object> body, Set<Integer> inScope) {
        return new Case(handler, "", kind, NO_VARS, body, inScope);
    }

    /** A by-id handler of the entity runtime, on a note: its code is the path variable {@code code}. */
    private static Case entity(String handler, BiFunction<ScopeFixture, Object, Object> body, Set<Integer> inScope) {
        return new Case(
                handler, MsNoteEntity.CODE, Kind.NOTE, (f, id) -> Map.of("code", MsNoteEntity.CODE), body, inScope);
    }

    /** A by-id handler of the entity runtime, on a user (ADR-0032, 8). */
    private static Case user(String handler, BiFunction<ScopeFixture, Object, Object> body, Set<Integer> inScope) {
        return runtime(MdUserEntity.CODE, Kind.USER, handler, "", body, inScope);
    }

    /** A record action of a user through the runtime: the action is the path variable {@code action}. */
    private static Case userAction(String action) {
        return runtime(MdUserEntity.CODE, Kind.USER, "EntityController#action", action, NO_BODY, OK);
    }

    /** A by-id handler of the entity runtime on a project; an action names its code as the path variable. */
    private static Case project(
            String handler, String action, BiFunction<ScopeFixture, Object, Object> body, Set<Integer> inScope) {
        return runtime(MsProjectEntity.CODE, Kind.PROJECT, handler, action, body, inScope);
    }

    /** A by-id handler of the entity runtime on a task; an action names its code as the path variable. */
    private static Case task(
            String handler, String action, BiFunction<ScopeFixture, Object, Object> body, Set<Integer> inScope) {
        return runtime(MsTaskEntity.CODE, Kind.TASK, handler, action, body, inScope);
    }

    private static Case runtime(
            String code,
            Kind kind,
            String handler,
            String action,
            BiFunction<ScopeFixture, Object, Object> body,
            Set<Integer> inScope) {
        String label = action.isEmpty() ? code : code + " " + action;
        return new Case(
                handler,
                label,
                kind,
                (f, id) -> action.isEmpty() ? Map.of("code", code) : Map.of("code", code, "action", action),
                body,
                inScope);
    }

    private static Case history(String key, Kind kind) {
        return new Case("RecordHistoryController#history", key, kind, (f, id) -> Map.of("kind", key), NO_BODY, OK);
    }

    static final List<Case> CASES = List.of(
            // users (md) on the general entity runtime (ADR-0032, 8): the record, its change and actions, then the
            // roles, units, sessions and history of the user
            user("EntityController#get", NO_BODY, OK),
            user("EntityController#update", (f, id) -> Map.of("name", "TEST renamed"), OK),
            userAction(MdUserEntity.BLOCK),
            userAction(MdUserEntity.UNBLOCK),
            userAction(MdUserEntity.RESET_2FA),
            userAction(MdUserEntity.ENABLE_2FA),
            userAction(MdUserEntity.FORCE_PASSWORD_CHANGE),
            userAction(MdUserEntity.ANONYMIZE),
            read("MdAssignmentController#getUserRoles", Kind.USER),
            write("MdAssignmentController#assignRoles", Kind.USER, (f, id) -> Map.of("roleIds", List.of()), OK),
            read("MdAssignmentController#getPersonalPermissions", Kind.USER),
            write(
                    "MdAssignmentController#replacePersonalPermissions",
                    Kind.USER,
                    (f, id) -> Map.of("grants", List.of()),
                    OK),
            read("MdAssignmentController#getEffectivePermissions", Kind.USER),
            read("MdOrgUnitController#getUserAssignments", Kind.USER),
            write(
                    "MdOrgUnitController#assignUser",
                    Kind.USER,
                    (f, id) -> Map.of("orgUnitIds", List.of(f.unitA)),
                    NO_CONTENT),
            read("MdOrgUnitController#getUserScope", Kind.USER),
            read("KauthSessionController#listUserSessions", Kind.USER),
            write("KauthSessionController#closeAllUserSessions", Kind.USER, NO_CONTENT),
            new Case(
                    "KauthSessionController#closeUserSession",
                    "",
                    Kind.USER,
                    (f, id) -> Map.of("id", f.session(id)),
                    NO_BODY,
                    NO_CONTENT),
            read("KauthSessionController#getUserSecuritySummary", Kind.USER),
            history(MdUserEntity.CODE, Kind.USER),
            // projects (ms.task) on the general entity runtime (ADR-0032, 8): seen by their author, members and the
            // participants of their tasks
            project("EntityController#get", "", NO_BODY, OK),
            project("EntityController#update", "", (f, id) -> Map.of("description", "TEST changed"), OK),
            project("EntityController#archive", "", (f, id) -> Map.of("archived", true), OK),
            project(
                    "EntityController#action",
                    MsProjectEntity.ADD_MEMBER,
                    (f, id) -> Map.of("userId", f.insider, "accessKind", "R"),
                    OK),
            project(
                    "EntityController#action",
                    MsProjectEntity.REMOVE_MEMBER,
                    (f, id) -> Map.of("userId", f.insider),
                    OK),
            read("MsProjectController#pageMembers", Kind.PROJECT),
            history(MsProjectEntity.CODE, Kind.PROJECT),
            // tasks (ms.task) on the general entity runtime (ADR-0032, 8): seen by their author and participants;
            // their participants, comments and files stay the module's
            task("EntityController#get", "", NO_BODY, OK),
            task("EntityController#update", "", (f, id) -> Map.of("title", "TEST changed"), OK),
            task("EntityController#action", MsTaskEntity.SET_STATUS, (f, id) -> Map.of("status", "in_progress"), OK),
            read("MsTaskController#getMembers", Kind.TASK),
            write("MsTaskController#markViewed", Kind.TASK, NO_CONTENT),
            read("MsTaskFileController#getTaskFiles", Kind.TASK),
            write("MsTaskFileController#attachFile", Kind.TASK, (f, id) -> Map.of("fileId", f.viewerFile), NO_CONTENT),
            new Case(
                    "MsTaskFileController#detachFile",
                    "",
                    Kind.TASK,
                    (f, id) -> Map.of("fileId", f.attached(id)),
                    NO_BODY,
                    NO_CONTENT),
            read("MsTaskCommentController#listComments", Kind.TASK),
            write(
                    "MsTaskCommentController#addComment",
                    Kind.TASK,
                    (f, id) -> Map.of("textMarkdown", "TEST comment"),
                    Set.of(201)),
            history(MsTaskEntity.CODE, Kind.TASK),
            // notes on the general entity runtime (ADR-0032, 6): personal, so another person's note is outside the
            // scope
            entity("EntityController#get", NO_BODY, OK),
            entity(
                    "EntityController#update",
                    (f, id) -> Map.of("title", "TEST changed", "contentMd", "body", "color", "default"),
                    OK),
            entity("EntityController#update", (f, id) -> Map.of("isPinned", true), OK),
            entity("EntityController#archive", (f, id) -> Map.of("archived", true), OK),
            entity("EntityController#delete", NO_BODY, NO_CONTENT),
            history(MsNoteEntity.DEFINITION.code(), Kind.NOTE),
            // files (mf)
            read("MfFileController#getFileMetadata", Kind.FILE),
            read("MfFileController#downloadFile", Kind.FILE),
            write("MfFileController#deleteFile", Kind.FILE, NO_CONTENT));

    /**
     * Handlers with a path variable that address no record of a scoped entity, each with the reason. A new by-id
     * handler belongs in {@link #CASES}; it goes here only with a reason a reviewer accepts.
     */
    static final Map<String, String> ALLOWLIST = Map.ofEntries(
            Map.entry("EntityBulkController#bulk", "an entity code; every record passes the entity's own by-id path"),
            Map.entry("EntityController#list", "an entity code: its list holds only the viewer's scope (the kit)"),
            Map.entry("EntityController#create", "an entity code: a new record, in the creator's scope"),
            Map.entry(
                    "EntityFileController#download",
                    "a record's file: the runtime's read in the entity's scope decides (EntityFileControllerTest);"
                            + " no entity of the application has a file field yet"),
            Map.entry("FormMetaController#get", "metadata of an entity code, no record"),
            Map.entry("QueryMetaController#get", "metadata of a list code, no record"),
            Map.entry("KauthApiTokenController#revokeToken", "the caller's own token; another id changes nothing"),
            Map.entry("KauthChannelController#unbindChannel", "the caller's own channel"),
            Map.entry("KauthSessionController#closeSession", "the caller's own session; another id answers 404"),
            Map.entry("MdCustomFieldController#updateField", "field configuration, not a scoped record"),
            Map.entry("MdCustomFieldController#deleteField", "field configuration, not a scoped record"),
            Map.entry("MdI18nAdminController#getEditor", "a language dictionary"),
            Map.entry("MdI18nAdminController#updateTranslations", "a language dictionary"),
            Map.entry("MdI18nAdminController#export", "a language dictionary"),
            Map.entry("MdI18nController#getDictionary", "a language dictionary"),
            Map.entry("MdListViewController#list", "a list code; the caller's own saved views"),
            Map.entry("MdListViewController#create", "a list code; the caller's own saved views"),
            Map.entry("MdListViewController#update", "the caller's own saved view; another id answers 404"),
            Map.entry("MdListViewController#delete", "the caller's own saved view; another id answers 404"),
            Map.entry(
                    "MdOrgUnitController#getById", "the org tree is the scope's configuration, read whole (ADR-0013)"),
            Map.entry("MdOrgUnitController#update", "the org tree is the scope's configuration (ADR-0013)"),
            Map.entry("MdOrgUnitController#delete", "the org tree is the scope's configuration (ADR-0013)"),
            Map.entry("MdOrgUnitController#getRoleRule", "a role, not a scoped record"),
            Map.entry("MdOrgUnitController#setRoleRule", "a role, not a scoped record"),
            Map.entry("MdRoleController#updateRole", "a role, not a scoped record"),
            Map.entry("MdRoleController#deleteRole", "a role, not a scoped record"),
            Map.entry("MdRoleController#getRolePermissions", "a role, not a scoped record"),
            Map.entry("MdRoleController#setRolePermissions", "a role, not a scoped record"),
            Map.entry("ModuleRegistryController#getModule", "an installed module, instance-wide"),
            Map.entry("ModuleRegistryController#setEnabled", "an installed module, instance-wide"),
            Map.entry("ModuleRegistryController#toggleModule", "an installed module, instance-wide"),
            Map.entry("ModuleRegistryController#putModule", "an installed module, instance-wide"),
            Map.entry("NavigationItemController#getItem", "a menu item, instance-wide"),
            Map.entry("NavigationItemController#getItemByCode", "a menu item, instance-wide"),
            Map.entry("NavigationItemController#updateItem", "a menu item, instance-wide"),
            Map.entry("NavigationItemController#setActive", "a menu item, instance-wide"),
            Map.entry("NavigationItemController#toggleItem", "a menu item, instance-wide"),
            Map.entry("NavigationItemController#deleteItem", "a menu item, instance-wide"),
            Map.entry("MsAnnouncementAdminController#update", "an announcement, instance-wide"),
            Map.entry("MsAnnouncementAdminController#publish", "an announcement, instance-wide"),
            Map.entry("MsAnnouncementAdminController#archive", "an announcement, instance-wide"),
            Map.entry("MsAnnouncementController#markAsRead", "an announcement, instance-wide; the caller's mark"),
            Map.entry(
                    "MsNotificationController#markAsRead", "the caller's own notification; another id changes nothing"),
            Map.entry("ReportExportController#file", "the caller's own export; another id answers 404"),
            Map.entry("ReportImportController#template", "an entity code: the template holds no record"),
            Map.entry(
                    "ReportImportController#start",
                    "an entity code: each row is a save in the importer's scope (the kit's import group)"),
            Map.entry("ReportImportController#get", "the caller's own import; another id answers 404"),
            Map.entry("ReportImportController#report", "the caller's own import; another id answers 404"),
            Map.entry("SearchManagementController#job", "a search index job, administrator only, instance-wide"),
            Map.entry("SearchManagementController#cancel", "a search index job, administrator only, instance-wide"),
            Map.entry("SearchManagementController#retry", "a search index job, administrator only, instance-wide"),
            Map.entry("UplPackageController#get", "a data package, instance-wide like its list"),
            Map.entry("UplPackageController#errors", "a data package, instance-wide like its list"),
            Map.entry("UplPackageController#errorsFile", "a data package, instance-wide like its list"),
            Map.entry("UplPackageController#apply", "a data package, instance-wide like its list"),
            Map.entry("UplSourceController#get", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#update", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#versions", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#versionAt", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#createDraft", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#getVersion", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#template", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#replaceDraft", "a data source format, instance-wide like its list"),
            Map.entry("UplSourceController#publish", "a data source format, instance-wide like its list"),
            Map.entry("WebhookSubscriptionController#updateSubscription", "a webhook subscription, instance-wide"),
            Map.entry("WebhookSubscriptionController#deleteSubscription", "a webhook subscription, instance-wide"));
}
