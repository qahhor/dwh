package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A list field in the registry. {@code sql} is an expression over the list's {@code from}; it is written in module
 * code and reaches the query only through the registry, with values only as parameters. The client receives
 * everything except {@code sql}.
 *
 * @param key            field name in the DSL and in the response ({@code lastPublishedVersion})
 * @param labelKey       dictionary key for the caption
 * @param enumLabelPrefix dictionary key prefix for enum values ({@code upl.periodicity.})
 * @param sortable       sortable; such a field is never empty, otherwise the keyset cursor loses rows
 * @param searchable     takes part in free-text search {@code q} (text only)
 * @param requiredForm   permission without which the field does not exist for the viewer (ADR-0016); null means
 *                       the field is open
 * @param requiredAction the action of that permission
 * @param label          a ready caption instead of a dictionary key: a custom field has only a name (ADR-0019)
 * @param attribute      custom field code: the value lives in the row's {@code attributes[attribute]}, not in
 *                       {@code key}
 * @param ref            reference to another list: the value is that list's row key, chosen by name (ADR-0019)
 */
public record QueryField(
        String key,
        String labelKey,
        QueryFieldType type,
        String sql,
        boolean filterable,
        boolean sortable,
        boolean nullable,
        boolean defaultVisible,
        List<String> enumValues,
        @Nullable String enumLabelPrefix,
        boolean searchable,
        @Nullable String requiredForm,
        @Nullable String requiredAction,
        @Nullable String label,
        @Nullable String attribute,
        @Nullable QueryRef ref) {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    public QueryField {
        Objects.requireNonNull(type, "type");
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad query field key: " + key);
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Query field " + key + " has no sql");
        }
        if (sortable && nullable) {
            throw new IllegalArgumentException("Query field " + key + " is sortable, so it must not be nullable");
        }
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (searchable && type != QueryFieldType.TEXT) {
            throw new IllegalArgumentException("Query field " + key + ": only text fields are searchable");
        }
        if ((requiredForm == null) != (requiredAction == null)) {
            throw new IllegalArgumentException("Query field " + key + ": a required right needs both form and action");
        }
        if ((type == QueryFieldType.ENUM) == enumValues.isEmpty()) {
            throw new IllegalArgumentException("Query field " + key + ": enum values go with the ENUM type only");
        }
    }

    public static QueryField of(String key, String labelKey, QueryFieldType type, String sql) {
        return new QueryField(
                key, labelKey, type, sql, true, false, false, true, List.of(), null, false, null, null, null, null,
                null);
    }

    public static QueryField enumeration(
            String key, String labelKey, String sql, List<String> values, @Nullable String labelPrefix) {
        return new QueryField(
                key,
                labelKey,
                QueryFieldType.ENUM,
                sql,
                true,
                false,
                false,
                true,
                values,
                labelPrefix,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * A custom field of an entity (ADR-0019, 2.3): named by its own label, its value read from the row's
     * attributes. Never sortable — sorting needs an expression index the application cannot create.
     */
    public static QueryField custom(
            String key, String label, QueryFieldType type, String sql, String attribute, List<String> enumValues) {
        return new QueryField(
                key,
                "",
                type,
                sql,
                true,
                false,
                true,
                true,
                enumValues,
                null,
                type == QueryFieldType.TEXT,
                null,
                null,
                label,
                attribute,
                null);
    }

    /**
     * The field holds the key of a row in another list: the screen picks it by name from {@code ref.path}, and
     * that endpoint applies its own rights and data scope (ADR-0019, 2.4).
     */
    public QueryField refersTo(QueryRef ref) {
        if (type != QueryFieldType.NUMBER && type != QueryFieldType.TEXT) {
            throw new IllegalArgumentException("Query field " + key + ": a reference holds a number or text key");
        }
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                sortable,
                nullable,
                defaultVisible,
                enumValues,
                enumLabelPrefix,
                searchable,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref);
    }

    public QueryField asSortable() {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                true,
                nullable,
                defaultVisible,
                enumValues,
                enumLabelPrefix,
                searchable,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref);
    }

    public QueryField asNullable() {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                sortable,
                true,
                defaultVisible,
                enumValues,
                enumLabelPrefix,
                searchable,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref);
    }

    public QueryField asNotFilterable() {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                false,
                sortable,
                nullable,
                defaultVisible,
                enumValues,
                enumLabelPrefix,
                searchable,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref);
    }

    public QueryField asSearchable() {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                sortable,
                nullable,
                defaultVisible,
                enumValues,
                enumLabelPrefix,
                true,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref);
    }

    public QueryField asHidden() {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                sortable,
                nullable,
                false,
                enumValues,
                enumLabelPrefix,
                searchable,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref);
    }

    /**
     * A field only for holders of the permission: without it the field is not shown in metadata, not accepted
     * in filter, sort or search, and its value is not sent in the list response.
     */
    public QueryField requires(String form, String action) {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                sortable,
                nullable,
                defaultVisible,
                enumValues,
                enumLabelPrefix,
                searchable,
                form,
                action,
                label,
                attribute,
                ref);
    }

    /** Whether the current requester can see the field. */
    public boolean visibleToViewer() {
        // The constructor keeps form and action together.
        return requiredForm == null
                || SecurityContext.hasPermission(requiredForm, Objects.requireNonNull(requiredAction));
    }

    /** Operations the field accepts in a filter; empty means the field is not filterable. */
    public Set<QueryOp> ops() {
        if (!filterable) {
            return EnumSet.noneOf(QueryOp.class);
        }
        Set<QueryOp> ops = type.ops();
        if (nullable) {
            ops.add(QueryOp.EMPTY);
            ops.add(QueryOp.NOT_EMPTY);
        }
        return ops;
    }
}
