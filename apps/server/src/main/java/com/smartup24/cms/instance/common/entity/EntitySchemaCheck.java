package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormPart;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Compares every entity declaration with the tables of the database (ADR-0033, 7; plan 10/10, item 6.4): the tables of
 * the entity, of its collections and of its link tables exist, every column a field, the scope or the runtime reads or
 * writes is there with a type the field's type accepts, the revision and the archive columns are there, an org-unit
 * scope's column references the org units, and a column that must be filled is written. The start refuses on any
 * difference ({@code config.db.EntitySchemaGate}); {@code EntitySchemaContractTest} runs it in CI.
 */
public final class EntitySchemaCheck {

    private static final Set<String> TEXT = Set.of("text", "character varying", "character");
    private static final Set<String> KEY = Set.of("bigint", "integer");
    private static final Set<String> NUMBER =
            Set.of("numeric", "integer", "bigint", "smallint", "real", "double precision");
    private static final Set<String> MOMENT = Set.of("timestamp with time zone");

    private final Map<String, Map<String, Column>> tables;
    private final Map<String, Set<String>> references;

    /** A column as {@code information_schema.columns} describes it. */
    public record Column(String type, boolean nullable, boolean hasDefault) {}

    /**
     * @param tables     the columns of every table of the schema, by table and column
     * @param references the tables each {@code table.column} references by a foreign key
     */
    public EntitySchemaCheck(Map<String, Map<String, Column>> tables, Map<String, Set<String>> references) {
        this.tables = tables;
        this.references = references;
    }

    /** Reads the tables and foreign keys of the current schema in two statements. */
    public static EntitySchemaCheck of(JdbcClient jdbc) {
        Map<String, Map<String, Column>> tables = new HashMap<>();
        jdbc.sql("""
                        select table_name, column_name, data_type, is_nullable = 'YES' as nullable,
                               (column_default is not null or is_identity = 'YES' or is_generated = 'ALWAYS') as filled
                        from information_schema.columns
                        where table_schema = current_schema()
                        """)
                .query((rs, row) -> tables.computeIfAbsent(rs.getString("table_name"), name -> new HashMap<>())
                        .put(
                                rs.getString("column_name"),
                                new Column(
                                        rs.getString("data_type"), rs.getBoolean("nullable"), rs.getBoolean("filled"))))
                .list();
        Map<String, Set<String>> references = new HashMap<>();
        jdbc.sql("""
                        select src.relname as table_name, att.attname as column_name, dst.relname as target
                        from pg_constraint con
                        join pg_class src on src.oid = con.conrelid
                        join pg_class dst on dst.oid = con.confrelid
                        join pg_namespace ns on ns.oid = src.relnamespace
                        join pg_attribute att on att.attrelid = con.conrelid and att.attnum = any(con.conkey)
                        where con.contype = 'f' and ns.nspname = current_schema()
                        """)
                .query((rs, row) -> references
                        .computeIfAbsent(
                                rs.getString("table_name") + "." + rs.getString("column_name"), key -> new HashSet<>())
                        .add(rs.getString("target")))
                .list();
        return new EntitySchemaCheck(tables, references);
    }

    /** Every difference of the declarations from the schema, one line each, in declaration order. */
    public List<String> problems(Collection<EntityDefinition> entities) {
        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            EntityModel model = entity.model();
            if (model != null) problems.addAll(problems(entity, model));
        }
        return problems;
    }

    private List<String> problems(EntityDefinition entity, EntityModel model) {
        List<String> problems = new ArrayList<>();
        String table = model.table();
        Map<String, Column> columns = tables.get(table);
        if (columns == null) {
            problems.add(entity.code() + ": table " + table + " is missing");
            return problems;
        }
        Set<String> written = new LinkedHashSet<>();
        require(problems, entity, table, columns, "id", KEY, "the record's key");
        require(problems, entity, table, columns, "revision", Set.of("bigint"), "the revision (ADR-0024)");
        requireNotNull(problems, entity, table, columns, "revision");
        for (String system : List.of("created_by", "modified_by")) {
            require(problems, entity, table, columns, system, KEY, "written by the runtime");
            written.add(system);
        }
        require(problems, entity, table, columns, "modified_at", MOMENT, "written by the runtime");
        written.add("modified_at");
        if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
            require(problems, entity, table, columns, "archived_at", MOMENT, "the archive (ADR-0032, 5.4)");
            require(problems, entity, table, columns, "archived_by", KEY, "the archive (ADR-0032, 5.4)");
        }
        if (entity.capabilities().contains(EntityCapability.CUSTOM_FIELDS)
                || model.fields().stream().anyMatch(field -> field.source() instanceof FieldSource.Attribute)) {
            require(problems, entity, table, columns, EntityModel.ATTRIBUTES, Set.of("jsonb"), "the attributes");
        }
        scope(problems, entity, model, columns, written);
        for (EntityField field : model.fields()) {
            fieldColumns(problems, entity, table, columns, field, written);
        }
        unwritten(problems, entity, table, columns, written);
        for (EntityCollection collection : model.collections()) {
            collection(problems, entity, collection);
        }
        return problems;
    }

    private void scope(
            List<String> problems,
            EntityDefinition entity,
            EntityModel model,
            Map<String, Column> columns,
            Set<String> written) {
        String table = model.table();
        for (String column : model.scope().columns()) {
            require(
                    problems,
                    entity,
                    table,
                    columns,
                    column,
                    KEY,
                    "the scope " + model.scope().describe());
            written.add(column);
        }
        if (model.scope() instanceof EntityScope.OrgUnit unit
                && columns.containsKey(unit.orgUnitColumn())
                && !references
                        .getOrDefault(table + "." + unit.orgUnitColumn(), Set.of())
                        .contains("md_org_units")) {
            problems.add(entity.code() + ": " + table + "." + unit.orgUnitColumn()
                    + " references no md_org_units (the org-unit scope)");
        }
    }

    private void fieldColumns(
            List<String> problems,
            EntityDefinition entity,
            String table,
            Map<String, Column> columns,
            EntityField field,
            Set<String> written) {
        String why = "field " + field.key() + " (" + field.type().wire() + ")";
        switch (field.source()) {
            case FieldSource.Column column -> {
                require(problems, entity, table, columns, column.name(), accepted(field.type()), why);
                if (field.form() != null) written.add(column.name());
                requireFilled(problems, entity, table, columns, column.name(), field);
            }
            case FieldSource.MoneyColumns money -> {
                require(problems, entity, table, columns, money.amount(), Set.of("numeric"), why);
                if (money.currency() != null) {
                    require(problems, entity, table, columns, money.currency(), TEXT, why);
                    written.add(money.currency());
                }
                written.add(money.amount());
            }
            case FieldSource.SystemValue system ->
                require(problems, entity, table, columns, system.column().column(), systemType(system), why);
            case FieldSource.Link link -> link(problems, entity, link, why);
            case FieldSource.Attribute attribute -> {
                // Kept in the attributes: no column of its own.
            }
            case FieldSource.Expression expression -> {
                // Read by its SQL over the table; nothing to compare.
            }
            case FieldSource.Computed computed -> {
                // Read by its SQL over the table; nothing to compare.
            }
        }
    }

    private void link(List<String> problems, EntityDefinition entity, FieldSource.Link link, String why) {
        Map<String, Column> columns = tables.get(link.table());
        if (columns == null) {
            problems.add(entity.code() + ": link table " + link.table() + " of " + why + " is missing");
            return;
        }
        require(problems, entity, link.table(), columns, link.ownerColumn(), KEY, why);
        require(problems, entity, link.table(), columns, link.targetColumn(), KEY, why);
        require(problems, entity, link.table(), columns, "position", NUMBER, why + ", the order of its keys");
        if (link.kindColumn() != null) {
            require(problems, entity, link.table(), columns, link.kindColumn(), TEXT, why);
        }
    }

    private void collection(List<String> problems, EntityDefinition entity, EntityCollection collection) {
        String table = collection.table();
        Map<String, Column> columns = tables.get(table);
        if (columns == null) {
            problems.add(entity.code() + ": table " + table + " of the rows " + collection.key() + " is missing");
            return;
        }
        String why = "the rows " + collection.key();
        require(problems, entity, table, columns, "id", KEY, why);
        require(problems, entity, table, columns, collection.parentColumn(), KEY, why + ", their record");
        require(problems, entity, table, columns, collection.positionColumn(), NUMBER, why + ", their place");
        Set<String> written = new LinkedHashSet<>(List.of(collection.parentColumn(), collection.positionColumn()));
        for (EntityField field : collection.fields()) {
            fieldColumns(problems, entity, table, columns, field, written);
        }
        unwritten(problems, entity, table, columns, written);
    }

    /** A column that must be filled and has no default is written by a field of the form or by the runtime. */
    private static void unwritten(
            List<String> problems,
            EntityDefinition entity,
            String table,
            Map<String, Column> columns,
            Set<String> written) {
        columns.entrySet().stream()
                .filter(column ->
                        !column.getValue().nullable() && !column.getValue().hasDefault())
                .map(Map.Entry::getKey)
                .filter(name -> !written.contains(name))
                .sorted()
                .forEach(name -> problems.add(entity.code() + ": " + table + "." + name
                        + " is not null without a default, and no field of the form or the runtime writes it"));
    }

    /** A field that may stay empty over a column that must be filled needs a default: of the column or the field. */
    private static void requireFilled(
            List<String> problems,
            EntityDefinition entity,
            String table,
            Map<String, Column> columns,
            String name,
            EntityField field) {
        Column column = columns.get(name);
        FormPart form = field.form();
        if (column == null || form == null || column.nullable() || column.hasDefault()) return;
        if (form.required() || form.defaultValue() != null || form.readonly() != null) return;
        problems.add(entity.code() + ": " + table + "." + name + " is not null without a default, field " + field.key()
                + " is optional and has no default");
    }

    private static void require(
            List<String> problems,
            EntityDefinition entity,
            String table,
            Map<String, Column> columns,
            String name,
            Set<String> types,
            String why) {
        Column column = columns.get(name);
        if (column == null) {
            problems.add(entity.code() + ": " + table + "." + name + " is missing (" + why + ")");
        } else if (!types.contains(column.type())) {
            problems.add(entity.code() + ": " + table + "." + name + " is " + column.type() + ", " + why + " needs "
                    + String.join(" or ", types.stream().sorted().toList()));
        }
    }

    private static void requireNotNull(
            List<String> problems, EntityDefinition entity, String table, Map<String, Column> columns, String name) {
        Column column = columns.get(name);
        if (column != null && column.nullable()) {
            problems.add(entity.code() + ": " + table + "." + name + " may be null");
        }
    }

    /** The column types a field of {@code type} is kept in (ADR-0033, 7). */
    public static Set<String> accepted(FieldType type) {
        return switch (type) {
            case TEXT, TEXTAREA, MARKDOWN, SELECT, ENUM, EMAIL, PHONE, URL -> TEXT;
            case NUMBER -> NUMBER;
            case MONEY -> Set.of("numeric");
            case DATE -> Set.of("date");
            case DATETIME -> MOMENT;
            case TIME -> Set.of("time without time zone");
            case BOOLEAN -> Set.of("boolean");
            case REF, MULTI_REF -> KEY;
            case FILE, IMAGE -> Set.of("uuid");
            case JSON -> Set.of("jsonb", "json");
        };
    }

    private static Set<String> systemType(FieldSource.SystemValue system) {
        return switch (system.column()) {
            case ID, CREATED_BY, MODIFIED_BY -> KEY;
            case REVISION -> Set.of("bigint");
            case CREATED_AT, MODIFIED_AT -> MOMENT;
        };
    }

    /** The columns of {@code table}, or null when it is missing; for the review of a difference. */
    public @Nullable Map<String, Column> columns(String table) {
        return tables.get(table);
    }
}
