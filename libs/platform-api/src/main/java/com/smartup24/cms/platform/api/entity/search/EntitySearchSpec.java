package com.smartup24.cms.platform.api.entity.search;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The SEARCH capability of an entity (ADR-0032, 10.3; plan 10/10, item 5.8): the fields its search documents hold, each
 * a text field of the entity's list that every viewer of the entity may see — a field with {@code requires} is never
 * indexed, the index being one for every viewer (ADR-0032, 5.2). The search module builds the entity's collection and
 * documents from it, no Java projection of the entity's own; the documents carry the keys of the entity's scope, and the
 * records an index query finds are checked again in the database before they are answered.
 *
 * <pre>{@code
 * .search(EntitySearchSpec.title("title").body("contentMd"))
 * .search(EntitySearchSpec.title("title").body("descriptionMarkdown").route("/tasks/items/{id}")
 *         .scopeUsers("array[t.created_by, t.reporter_id]"))
 * }</pre>
 *
 * @param titleField  the key of the field a hit is named by
 * @param bodyFields  the keys of the further fields searched, in order; the first one filled describes a hit
 * @param route       where a hit leads in the web application, {@code /tasks/items/{id}}, or null for the entity's
 *                    general screen {@code /e/<code>/<id>}
 * @param indexedWhen a condition over the entity's alias a record meets to be found ({@code u.state = 'A'}), or null;
 *                    an archivable entity's archived records are never found
 * @param scopeUsers  for a custom scope: SQL over the entity's alias of the users whose participation makes a record
 *                    visible ({@code bigint[]}); the org units of those users are the record's units. Null for any
 *                    other scope, whose keys come from its columns
 */
@PlatformApi(since = "1.0", stability = Stability.EXPERIMENTAL)
public record EntitySearchSpec(
        String titleField,
        List<String> bodyFields,
        @Nullable String route,
        @Nullable String indexedWhen,
        @Nullable String scopeUsers) {

    /** The fields of these kinds hold the words a search finds. */
    public static final Set<FieldType> TEXT_TYPES = Set.of(
            FieldType.TEXT, FieldType.TEXTAREA, FieldType.MARKDOWN, FieldType.EMAIL, FieldType.PHONE, FieldType.URL);

    /** The placeholder of the record's id in a route. */
    public static final String ID = "{id}";

    private static final Pattern ROUTE = Pattern.compile("^/[a-z0-9_./-]*\\{id}$");

    public EntitySearchSpec {
        Objects.requireNonNull(titleField, "titleField");
        bodyFields = List.copyOf(bodyFields);
        if (route != null && !ROUTE.matcher(route).matches()) {
            throw new IllegalArgumentException("A search route is an absolute path ending in {id}: " + route);
        }
        requireSql(indexedWhen);
        requireSql(scopeUsers);
    }

    /** A search of the entity whose hits are named by the field {@code key}. */
    public static EntitySearchSpec title(String key) {
        return new EntitySearchSpec(key, List.of(), null, null, null);
    }

    /** The same search over these further fields too. */
    public EntitySearchSpec body(String... keys) {
        List<String> fields = new ArrayList<>(bodyFields);
        fields.addAll(List.of(keys));
        return new EntitySearchSpec(titleField, fields, route, indexedWhen, scopeUsers);
    }

    /** The same search, its hits leading to the entity's own screen ({@code /tasks/items/{id}}). */
    public EntitySearchSpec route(String path) {
        return new EntitySearchSpec(titleField, bodyFields, path, indexedWhen, scopeUsers);
    }

    /** The same search, finding only the records that meet the condition over the entity's alias. */
    public EntitySearchSpec when(String condition) {
        return new EntitySearchSpec(titleField, bodyFields, route, condition, scopeUsers);
    }

    /** The same search, with the users of a custom scope as SQL over the entity's alias ({@code bigint[]}). */
    public EntitySearchSpec scopeUsers(String users) {
        return new EntitySearchSpec(titleField, bodyFields, route, indexedWhen, users);
    }

    /** Every searched field, the title first. */
    public List<String> fields() {
        List<String> fields = new ArrayList<>();
        fields.add(titleField);
        fields.addAll(bodyFields);
        return fields;
    }

    /** Where a hit of the entity {@code code} leads in the web application. */
    public String targetUrl(String code, long id) {
        return route == null ? "/e/" + code + "/" + id : route.replace(ID, Long.toString(id));
    }

    /**
     * Refuses a search of the table that names no field of it, a field twice, a field that is not a text field of the
     * list or one that needs a right; a custom scope names its users, any other scope does not.
     */
    public void check(String table, List<EntityField> fields, EntityScope scope) {
        Map<String, EntityField> byKey =
                fields.stream().collect(Collectors.toMap(EntityField::key, Function.identity(), (a, b) -> a));
        Set<String> seen = new HashSet<>();
        for (String key : fields()) {
            EntityField field = byKey.get(key);
            if (!seen.add(key) || field == null) {
                throw new IllegalArgumentException("Table " + table + ": the search names an unknown or repeated field "
                        + key + " (ADR-0032, 10.3)");
            }
            if (field.list() == null || !TEXT_TYPES.contains(field.type())) {
                throw new IllegalArgumentException(
                        "Table " + table + ": the search field " + key + " is a text field of the list");
            }
            if (field.access().restricted()) {
                throw new IllegalArgumentException("Table " + table + ": the search field " + key
                        + " needs a right, and the index is one for every viewer (ADR-0032, 5.2)");
            }
        }
        if ((scope instanceof EntityScope.Custom) != (scopeUsers != null)) {
            throw new IllegalArgumentException("Table " + table
                    + ": the search of a custom scope names its users (scopeUsers), and only that one");
        }
    }

    private static void requireSql(@Nullable String sql) {
        if (sql != null && (sql.isBlank() || sql.contains(";"))) {
            throw new IllegalArgumentException("Bad search SQL fragment");
        }
    }
}
