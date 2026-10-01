package com.smartup24.cms.instance.common.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The request forms the API keeps for one release after it chose another (plan 10/10, item 3.4, ADR-0023): path
 * aliases, toggles replaced by an explicit {@code PUT}, and snake_case query parameters. One table serves the runtime
 * (the web filter answers them with {@code Deprecation}, {@code Sunset} and {@code Link}) and the API
 * description (the operations and parameters are marked deprecated), so both say the same.
 */
public final class ApiDeprecations {

    /** The day these forms were deprecated (RFC 9745 {@code Deprecation}). */
    public static final LocalDate DEPRECATED_SINCE = LocalDate.of(2026, 9, 29);

    /** The day they stop answering (RFC 8594 {@code Sunset}): the release after the one that deprecated them. */
    public static final LocalDate SUNSET = LocalDate.of(2026, 12, 31);

    private static final String ANY = "*";
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");

    /** Path aliases, the more specific first: the first that matches wins. */
    private static final List<PathAlias> PATHS = List.of(
            alias(ANY, "/api/v1/tasks/items/{*rest}", ANY, "/api/v1/tasks{rest}"),
            alias(ANY, "/api/v1/rbac/{*rest}", ANY, "/api/v1/iam{rest}"),
            alias(ANY, "/api/v1/notify/{*rest}", ANY, "/api/v1/notifications{rest}"),
            alias(ANY, "/api/v1/iam/sessions/{*rest}", ANY, "/api/v1/iam/profile/sessions{rest}"),
            alias(
                    ANY,
                    "/api/v1/iam/profile/sessions/users/{userId}/security",
                    ANY,
                    "/api/v1/iam/users/{userId}/security"),
            alias(
                    ANY,
                    "/api/v1/iam/profile/sessions/users/{userId}/force-password-change",
                    ANY,
                    "/api/v1/iam/users/{userId}/force-password-change"),
            alias(
                    ANY,
                    "/api/v1/iam/profile/sessions/users/{userId}/reset-2fa",
                    ANY,
                    "/api/v1/iam/users/{userId}/reset-2fa"),
            alias(
                    ANY,
                    "/api/v1/iam/profile/sessions/users/{userId}/{id}",
                    ANY,
                    "/api/v1/iam/users/{userId}/sessions/{id}"),
            alias(ANY, "/api/v1/iam/profile/sessions/users/{userId}", ANY, "/api/v1/iam/users/{userId}/sessions"),
            alias("POST", "/api/v1/iam/users/me/password", "POST", "/api/v1/auth/password"),
            alias("GET", "/api/v1/announcements", "GET", "/api/v1/announcements/active"),
            // Plan 10/10, item 3.5: a whole list of projects with their task counts; the paged project list carries
            // the same counts per project.
            alias("GET", "/api/v1/tasks/projects/stats", "GET", "/api/v1/tasks/projects"),
            alias("GET", "/api/v1/tasks/projects/{id}/members", "GET", "/api/v1/tasks/projects/{id}/members/page"),
            alias("POST", "/api/v1/notes/{id}/pin", "PUT", "/api/v1/notes/{id}/pin"),
            alias("POST", "/api/v1/modules/{code}/toggle", "PUT", "/api/v1/modules/{code}/enabled"),
            alias("POST", "/api/v1/modules", "PUT", "/api/v1/modules/{code}"),
            alias("POST", "/api/v1/navigation/items/{id}/toggle", "PUT", "/api/v1/navigation/items/{id}/active"));

    /** snake_case query parameters and the camelCase names that replaced them. */
    public static final Map<String, String> QUERY_PARAMETERS = Map.ofEntries(
            Map.entry("table_name", "tableName"),
            Map.entry("row_pk", "rowPk"),
            Map.entry("user_id", "userId"),
            Map.entry("event_type", "eventType"),
            Map.entry("entity_type", "entityType"),
            Map.entry("role_id", "roleId"),
            Map.entry("manager_id", "managerId"),
            Map.entry("is_2fa_enabled", "is2faEnabled"),
            Map.entry("project_id", "projectId"),
            Map.entry("status_id", "statusId"),
            Map.entry("hide_terminal", "hideTerminal"),
            Map.entry("assigned_user_id", "assignedUserId"),
            Map.entry("member_role", "memberRole"),
            Map.entry("reporter_id", "reporterId"));

    private ApiDeprecations() {}

    /**
     * Parameters with their snake_case names replaced by the camelCase ones: the query of a request, and the options
     * of an export (ADR-0018), which name the same filters. A current name wins over its legacy twin.
     */
    public static <V> Map<String, V> currentNames(Map<String, V> parameters) {
        Map<String, V> renamed = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            String current = QUERY_PARAMETERS.get(name);
            if (current == null) {
                renamed.put(name, value);
            } else if (!parameters.containsKey(current)) {
                renamed.put(current, value);
            }
        });
        return renamed;
    }

    /**
     * The successor of a deprecated request form, or empty for a current one. Works on request paths and on path
     * templates of the API description alike: a template variable is carried over as it is.
     */
    public static Optional<Successor> successor(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (PathAlias alias : PATHS) {
            if (!ANY.equals(alias.method()) && !alias.method().equalsIgnoreCase(method)) {
                continue;
            }
            PathPattern.PathMatchInfo match = alias.legacy().matchAndExtract(container);
            if (match != null) {
                String successorMethod = ANY.equals(alias.successorMethod()) ? method : alias.successorMethod();
                return Optional.of(new Successor(
                        alias.legacy().getPatternString(),
                        successorMethod,
                        expand(alias.successor(), match.getUriVariables())));
            }
        }
        return Optional.empty();
    }

    private static String expand(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    expanded,
                    // A variable the legacy form does not carry in its path (the code of POST /modules is in the
                    // body) stays a template variable.
                    Matcher.quoteReplacement(variables.getOrDefault(matcher.group(1), matcher.group())));
        }
        return matcher.appendTail(expanded).toString();
    }

    private static PathAlias alias(String method, String legacy, String successorMethod, String successor) {
        return new PathAlias(method, PathPatternParser.defaultInstance.parse(legacy), successorMethod, successor);
    }

    private record PathAlias(String method, PathPattern legacy, String successorMethod, String successor) {}

    /**
     * The form to use instead: {@code alias} is the legacy pattern (a bounded metric tag), {@code method} and
     * {@code path} the successor request.
     */
    public record Successor(String alias, String method, String path) {}
}
