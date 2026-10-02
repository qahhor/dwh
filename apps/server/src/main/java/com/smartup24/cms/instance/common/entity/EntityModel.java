package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.collection.EntityCollection;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.common.entity.field.FieldSource;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.entity.hook.EntityRule;
import com.smartup24.cms.instance.common.entity.workflow.EntityWorkflow;
import com.smartup24.cms.instance.common.query.QueryField;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Where an entity's records live and the fields they have (ADR-0032, 3.2): the table and its alias, every field
 * declared once as an {@link EntityField}, and the list's default order. The entity's form fields
 * ({@link #formFields()}) and its list ({@link EntityLists#queryList}) are derived from it. Its scope says which rows a
 * viewer sees (ADR-0032, 5.1; plan 10/10, item 5.3); its rules check fields against each other (ADR-0032, 6.6; plan
 * 10/10, item 5.4). A document has collections of rows, a process and the tabs of its card (ADR-0032, 9; plan 10/10,
 * item 5.7). The search and import of ADR-0032 join it with the later items of phase 5.
 *
 * @param table             the entity's table ({@code ms_notes})
 * @param alias             its alias in the list's SQL ({@code n})
 * @param fields            every field, in the order of the form and the list
 * @param defaultSort       the key of the list's default sort field, a sortable list field
 * @param defaultDescending the default sort runs from the largest value
 * @param scope             which rows a viewer sees; every entity table declares it
 * @param reference         its rows are the items of enumerations (ADR-0032, 4.5), or null
 * @param rules             its cross-field rules by name, in declaration order (ADR-0032, 6.6)
 * @param collections       its collections of rows, in declaration order (ADR-0032, 9.1)
 * @param workflow          its process (ADR-0032, 9.2), or null
 * @param tabs              the tabs of its card, in order (ADR-0032, 9.3); empty for the platform's own tabs
 */
public record EntityModel(
        String table,
        String alias,
        List<EntityField> fields,
        String defaultSort,
        boolean defaultDescending,
        EntityScope scope,
        @Nullable EntityReference reference,
        Map<String, EntityRule> rules,
        List<EntityCollection> collections,
        @Nullable EntityWorkflow workflow,
        List<EntityTab> tabs) {

    /** The record property of the custom field values. */
    public static final String ATTRIBUTES = "attributes";

    /** The record property and list field of an archived record (ADR-0032, 5.4): true once it is archived. */
    public static final String ARCHIVED = "archived";

    /** The record property of the moment it was archived, or null. */
    public static final String ARCHIVED_AT = "archivedAt";

    /** The record's own properties: a field may use one only as its system column. */
    private static final Set<String> RESERVED = Stream.concat(
                    Stream.of(SystemColumn.values()).map(SystemColumn::key),
                    Stream.of(ATTRIBUTES, ARCHIVED, ARCHIVED_AT))
            .collect(Collectors.toUnmodifiableSet());

    public EntityModel {
        requireIdentifier(table);
        requireIdentifier(alias);
        fields = List.copyOf(fields);
        Objects.requireNonNull(defaultSort, "defaultSort");
        Objects.requireNonNull(scope, () -> "Table " + table + " declares its scope (ADR-0032, 5.1)");
        Set<String> keys = new HashSet<>();
        for (EntityField field : fields) {
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("Table " + table + ": duplicate field " + field.key());
            }
            if (RESERVED.contains(field.key()) && !(field.source() instanceof FieldSource.SystemValue)) {
                throw new IllegalArgumentException("Table " + table + ": the key " + field.key()
                        + " is the record's own; only its system field may use it");
            }
        }
        EntityModelRules.check(table, fields);
        rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        collections = List.copyOf(collections);
        tabs = List.copyOf(tabs);
        for (EntityCollection collection : collections) {
            if (!keys.add(collection.key()) || RESERVED.contains(collection.key())) {
                throw new IllegalArgumentException("Table " + table + ": the collection " + collection.key()
                        + " takes the key of a field or of the record");
            }
        }
        EntityModelRules.checkDocument(table, fields, collections, workflow, tabs);
    }

    /** A model of an entity without collections, process or tabs of its own. */
    public EntityModel(
            String table,
            String alias,
            List<EntityField> fields,
            String defaultSort,
            boolean defaultDescending,
            EntityScope scope,
            @Nullable EntityReference reference,
            Map<String, EntityRule> rules) {
        this(table, alias, fields, defaultSort, defaultDescending, scope, reference, rules, List.of(), null, List.of());
    }

    /** A model of an entity that is no reference and has no cross-field rules. */
    public EntityModel(
            String table,
            String alias,
            List<EntityField> fields,
            String defaultSort,
            boolean defaultDescending,
            EntityScope scope) {
        this(table, alias, fields, defaultSort, defaultDescending, scope, null, Map.of());
    }

    /** The collection declared under {@code key}, if any. */
    public Optional<EntityCollection> collection(String key) {
        return collections.stream()
                .filter(collection -> collection.key().equals(key))
                .findFirst();
    }

    /** The field declared under {@code key}, if any. */
    public Optional<EntityField> field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst();
    }

    /** The fields of the form, in declaration order. */
    public List<FormField> formFields() {
        return fields.stream()
                .flatMap(field -> Stream.ofNullable(field.formField()))
                .toList();
    }

    /** The fields of the list, in declaration order, read over the alias. */
    public List<QueryField> listFields() {
        return fields.stream()
                .flatMap(field -> field.queryFields(alias, this::sqlOf).stream())
                .toList();
    }

    /** The SQL of the field {@code key} over the alias: what money that takes its currency from a field reads. */
    public String sqlOf(String key) {
        return field(key)
                .orElseThrow(() -> new IllegalArgumentException("Table " + table + ": no field " + key))
                .sql(alias);
    }

    private static void requireIdentifier(String name) {
        if (name == null || !FieldSource.IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Bad identifier: " + name);
        }
    }
}
