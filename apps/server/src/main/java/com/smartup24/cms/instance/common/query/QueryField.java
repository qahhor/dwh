package com.smartup24.cms.instance.common.query;

import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * @param format         the entity field type when the list type alone does not say how to show the value
 *                       ({@code money}, {@code email}, {@code file}, ...; ADR-0032, 4.1), or null
 * @param enumLabels     the words of an enumeration's values read from its reference entity (ADR-0032, 4.5), or null
 *                       when they come from the dictionary by {@code enumLabelPrefix}
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
        @Nullable QueryRef ref,
        @Nullable String format,
        @Nullable Map<String, String> enumLabels) {

    /** The format of an enumeration whose values are read from a reference entity at request time (ADR-0032, 4.5). */
    public static final String ENUM_FORMAT = "enum";

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
        if (sortable && !type.sortable()) {
            throw new IllegalArgumentException("Query field " + key + ": a " + type.wire() + " is never sorted");
        }
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        enumLabels = enumLabels == null ? null : Map.copyOf(enumLabels);
        if (searchable && type != QueryFieldType.TEXT) {
            throw new IllegalArgumentException("Query field " + key + ": only text fields are searchable");
        }
        if ((requiredForm == null) != (requiredAction == null)) {
            throw new IllegalArgumentException("Query field " + key + ": a required right needs both form and action");
        }
        boolean readAtRequest = type == QueryFieldType.ENUM && ENUM_FORMAT.equals(format);
        if ((type == QueryFieldType.ENUM) == enumValues.isEmpty() && !readAtRequest) {
            throw new IllegalArgumentException("Query field " + key + ": enum values go with the ENUM type only");
        }
        if (enumLabels != null && type != QueryFieldType.ENUM) {
            throw new IllegalArgumentException("Query field " + key + ": enum labels go with the ENUM type only");
        }
    }

    /** A list field without the format of plan 10/10, item 5.2. */
    public QueryField(
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
        this(
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
                ref,
                null,
                null);
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
        if (type != QueryFieldType.NUMBER && type != QueryFieldType.TEXT && type != QueryFieldType.REF_SET) {
            throw new IllegalArgumentException("Query field " + key + ": a reference holds a number or text key");
        }
        return copy(filterable, sortable, nullable, defaultVisible, searchable, requiredForm, requiredAction, ref);
    }

    public QueryField asSortable() {
        return copy(filterable, true, nullable, defaultVisible, searchable, requiredForm, requiredAction, ref);
    }

    public QueryField asNullable() {
        return copy(filterable, sortable, true, defaultVisible, searchable, requiredForm, requiredAction, ref);
    }

    public QueryField asNotFilterable() {
        return copy(false, sortable, nullable, defaultVisible, searchable, requiredForm, requiredAction, ref);
    }

    public QueryField asSearchable() {
        return copy(filterable, sortable, nullable, defaultVisible, true, requiredForm, requiredAction, ref);
    }

    public QueryField asHidden() {
        return copy(filterable, sortable, nullable, false, searchable, requiredForm, requiredAction, ref);
    }

    /**
     * A field only for holders of the permission: without it the field is not shown in metadata, not accepted
     * in filter, sort or search, and its value is not sent in the list response.
     */
    public QueryField requires(String form, String action) {
        return copy(filterable, sortable, nullable, defaultVisible, searchable, form, action, ref);
    }

    /** The field shown as {@code shownAs} (an entity field type, ADR-0032, 4.1). */
    public QueryField formatted(String shownAs) {
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
                ref,
                shownAs,
                enumLabels);
    }

    /**
     * The enumeration with the values of its reference entity as they are now and their words (ADR-0032, 4.5), in
     * the reference's order.
     */
    public QueryField withEnumeration(Map<String, String> labelsByValue) {
        if (type != QueryFieldType.ENUM) {
            throw new IllegalArgumentException("Query field " + key + " is not an enumeration");
        }
        Map<String, String> ordered = new LinkedHashMap<>(labelsByValue);
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filterable,
                sortable,
                nullable,
                defaultVisible,
                List.copyOf(ordered.keySet()),
                enumLabelPrefix,
                searchable,
                requiredForm,
                requiredAction,
                label,
                attribute,
                ref,
                format,
                ordered);
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

    private QueryField copy(
            boolean filters,
            boolean sorts,
            boolean empty,
            boolean visible,
            boolean searched,
            @Nullable String form,
            @Nullable String action,
            @Nullable QueryRef source) {
        return new QueryField(
                key,
                labelKey,
                type,
                sql,
                filters,
                sorts,
                empty,
                visible,
                enumValues,
                enumLabelPrefix,
                searched,
                form,
                action,
                label,
                attribute,
                source,
                format,
                enumLabels);
    }
}
