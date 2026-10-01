package com.smartup24.cms.instance.common.security;

import com.smartup24.cms.instance.common.security.ScopeFixture.Kind;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
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

    private static Case history(String key, Kind kind) {
        return new Case("RecordHistoryController#history", key, kind, (f, id) -> Map.of("kind", key), NO_BODY, OK);
    }

    static final List<Case> CASES = List.of(
            // users (md): the card, its changes, roles, units, sessions and history
            read("MdUserController#getUser", Kind.USER),
            write("MdUserController#updateUser", Kind.USER, (f, id) -> Map.of("name", "TEST renamed"), NO_CONTENT),
            write("MdUserController#blockUser", Kind.USER, NO_CONTENT),
            write("MdUserController#unblockUser", Kind.USER, NO_CONTENT),
            write("MdUserController#deleteUser", Kind.USER, NO_CONTENT),
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
            write("KauthSessionController#forcePasswordChange", Kind.USER, NO_CONTENT),
            write("KauthSessionController#reset2fa", Kind.USER, NO_CONTENT),
            history("users", Kind.USER),
            // projects (ms.task)
            read("MsProjectController#getProject", Kind.PROJECT),
            write(
                    "MsProjectController#updateProject",
                    Kind.PROJECT,
                    (f, id) -> Map.of("description", "TEST changed"),
                    NO_CONTENT),
            write(
                    "MsProjectController#addMember",
                    Kind.PROJECT,
                    (f, id) -> Map.of("userId", f.insider, "accessKind", "R"),
                    NO_CONTENT),
            new Case(
                    "MsProjectController#removeMember",
                    "",
                    Kind.PROJECT,
                    (f, id) -> Map.of("userId", f.insider),
                    NO_BODY,
                    NO_CONTENT),
            read("MsProjectController#getMembers", Kind.PROJECT),
            read("MsProjectController#pageMembers", Kind.PROJECT),
            history("projects", Kind.PROJECT),
            // tasks (ms.task): the card, its subresources and history
            read("MsTaskController#getTask", Kind.TASK),
            write("MsTaskController#markViewed", Kind.TASK, NO_CONTENT),
            read("MsTaskController#getSubtasks", Kind.TASK),
            write("MsTaskController#updateTask", Kind.TASK, (f, id) -> Map.of("title", "TEST changed"), NO_CONTENT),
            write("MsTaskController#changeStatus", Kind.TASK, (f, id) -> Map.of("statusId", f.statusId), NO_CONTENT),
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
            history("tasks", Kind.TASK),
            // notes (ms.note): personal, so another person's note is outside the scope
            read("MsNoteController#getNote", Kind.NOTE),
            write(
                    "MsNoteController#updateNote",
                    Kind.NOTE,
                    (f, id) -> Map.of("title", "TEST changed", "contentMd", "body", "color", "default"),
                    OK),
            write("MsNoteController#setPin", Kind.NOTE, (f, id) -> Map.of("pinned", true), OK),
            write("MsNoteController#togglePin", Kind.NOTE, OK),
            write("MsNoteController#deleteNote", Kind.NOTE, NO_CONTENT),
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
            Map.entry("MsTaskStatusController#updateStatus", "a task status, a directory (ADR-0032: all())"),
            Map.entry("MsTaskStatusController#deleteStatus", "a task status, a directory (ADR-0032: all())"),
            Map.entry("MsTaskStatusController#updateType", "a task type, a directory (ADR-0032: all())"),
            Map.entry("MsTaskStatusController#deleteType", "a task type, a directory (ADR-0032: all())"),
            Map.entry("ReportExportController#file", "the caller's own export; another id answers 404"),
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
