package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.web.ApiDeprecations;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan 10/10, item 3.5: a collection that grows with use is read a page at a time (KeysetPage, a limit with a
 * maximum, 422 above it). A GET answers a whole list only for a reference list whose size the configuration bounds —
 * statuses, roles, languages, the menu — or for what a single parent holds within a fixed bound; each such handler
 * is named here with that bound. A new one must page, or be added here with its bound.
 */
class CollectionsArePagedTest {

    private static final String ROOT = "com.smartup24.cms.instance";

    /** Handlers ({@code Controller#method}) that answer a whole list, and why the list stays small. */
    private static final Map<String, String> REFERENCE_LISTS = Map.ofEntries(
            Map.entry("AnalyticsController#getProjects", "top 15 projects"),
            Map.entry("AnalyticsController#getTrends", "one point a day, 365 days at most"),
            Map.entry("AnalyticsController#getWorkload", "top 15 users"),
            Map.entry("EntityMenuController#menu", "the declared entities"),
            Map.entry("KauthApiTokenController#listTokens", "the viewer's own tokens, created by hand"),
            Map.entry("KauthChannelController#listChannels", "one per delivery channel"),
            Map.entry("KauthSessionController#listActiveSessions", "live sessions of the viewer; they expire"),
            Map.entry("KauthSessionController#listUserSessions", "live sessions of one user; they expire"),
            Map.entry("KwhSubscriptionController#listSubscriptions", "webhooks an administrator configures"),
            Map.entry("MdCustomFieldController#getFields", "custom fields an administrator declares"),
            Map.entry("MdI18nController#listLanguages", "the installed languages"),
            Map.entry("MdListViewController#list", "20 views per list and user (MdListViewService)"),
            Map.entry("MdOrgUnitController#list", "the organization tree, read whole to draw it"),
            Map.entry("MdRoleController#getFormCatalog", "the forms the modules declare"),
            Map.entry("MdRoleController#getRolePermissions", "the rights of one role, bounded by the catalog"),
            Map.entry("MdRoleController#listRoles", "roles an administrator creates; the matrix shows them all"),
            Map.entry("ModuleRegistryController#getActiveModules", "the installed modules"),
            Map.entry("ModuleRegistryController#getAllModules", "the installed modules"),
            Map.entry("MsAnnouncementController#getAnnouncements", "announcements active now for the reader"),
            Map.entry("MsNotificationController#getPreferences", "one per notification kind"),
            Map.entry("MsTaskController#getSubtasks", "the subtasks of one task, created by hand"),
            Map.entry("MsTaskFileController#getTaskFiles", "the files attached to one task"),
            Map.entry("MsTaskStatusController#listStatuses", "task statuses an administrator configures"),
            Map.entry("MsTaskStatusController#listTypes", "task types an administrator configures"),
            Map.entry("NavigationItemController#getActiveItems", "the menu"),
            Map.entry("NavigationItemController#getAllItems", "the menu"),
            Map.entry("NavigationItemController#getPermissionChoices", "the rights of the catalog"),
            Map.entry("OAuth2AuthController#getProviders", "the configured identity providers"),
            Map.entry("RecordHistoryController#kinds", "the declared record kinds"),
            Map.entry("ReportExportController#journal", "the viewer's last exports (JOURNAL_SIZE)"),
            Map.entry("UplSourceController#versions", "the format versions of one source, published by hand"),
            Map.entry("UplUnitController#list", "the units of measure"));

    /**
     * Collections that grow with use and are still answered whole: debt of item 3.5, which only shrinks. Projects
     * feed the pickers of the task screens, which have to become server-searched lookups first.
     */
    private static final Map<String, String> NOT_YET_PAGED =
            Map.ofEntries(Map.entry("MsProjectController#listProjects", "task screen pickers load every project"));

    /**
     * Whole lists kept only until their sunset: each is deprecated in {@link ApiDeprecations} for a paged successor.
     * The value is the request that answers the list whole.
     */
    private static final Map<String, String> DEPRECATED_WHOLE_LISTS = Map.ofEntries(
            Map.entry("MsProjectController#getMembers", "/api/v1/tasks/projects/1/members"),
            Map.entry("MsTaskController#getProjectStats", "/api/v1/tasks/projects/stats"));

    @Test
    @DisplayName("3.5: a GET answers a whole list only for a bounded reference list")
    void growingCollectionsArePaged() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        TreeSet<String> whole = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(ROOT)) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(GetMapping.class) && answersWholeList(method.getGenericReturnType())) {
                    whole.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        TreeSet<String> allowed = new TreeSet<>(REFERENCE_LISTS.keySet());
        allowed.addAll(NOT_YET_PAGED.keySet());
        allowed.addAll(DEPRECATED_WHOLE_LISTS.keySet());
        DEPRECATED_WHOLE_LISTS.forEach((handler, request) -> assertThat(ApiDeprecations.successor("GET", request))
                .as(handler + " is deprecated for a paged successor")
                .isPresent());
        assertThat(whole)
                .as("GET handlers answering a whole list that is not a bounded reference list: page them")
                .isSubsetOf(allowed);
        assertThat(allowed)
                .as("lists named here that no handler answers whole any more: remove them")
                .isSubsetOf(whole);
    }

    private static boolean answersWholeList(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw == ResponseEntity.class) {
                return answersWholeList(parameterized.getActualTypeArguments()[0]);
            }
            return raw instanceof Class<?> rawClass && Collection.class.isAssignableFrom(rawClass);
        }
        return type instanceof Class<?> plain && Collection.class.isAssignableFrom(plain);
    }
}
