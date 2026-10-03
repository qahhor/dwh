package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldRules;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormPart;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What the migrated schema lacks of the entity declarations, as the DDL of a new migration (plan 10/10, item 6.1; the
 * entry point of {@code cms migration diff}). The comparison is {@link EntitySchemaCheck} (ADR-0033, 7), the one the
 * start runs; this class only writes the statements for what that check's schema snapshot does not have. It reads the
 * declarations the application runs, not their source, and the schema every migration produced, not the migration
 * files: a table that is missing is created by the table convention of ADR-0032, 14.1, a column that is missing is
 * added with the type of its field and the names of ADR-0020.
 * A column added to an existing table stays nullable even for a required field: the form requires the value, and a
 * table with rows takes the migration. Only what is missing is written; a column the schema has but the declaration
 * does not name is left alone (a drop is destructive and is written by hand).
 */
final class EntitySchemaDiff {

    private final EntitySchemaCheck schema;
    private final Map<String, String> tablesByEntity;
    private final List<String> statements = new ArrayList<>();

    private EntitySchemaDiff(EntitySchemaCheck schema, List<EntityDefinition> entities) {
        this.schema = schema;
        Map<String, String> tables = new HashMap<>();
        for (EntityDefinition entity : entities) {
            if (entity.model() != null) {
                tables.put(entity.code(), entity.model().table());
            }
        }
        this.tablesByEntity = tables;
    }

    /** The statements of a migration that brings the schema to the declarations; empty when nothing is missing. */
    static List<String> statements(List<EntityDefinition> entities, EntitySchemaCheck schema) {
        EntitySchemaDiff diff = new EntitySchemaDiff(schema, entities);
        entities.stream()
                .filter(entity -> entity.model() != null)
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .forEach(diff::entity);
        return List.copyOf(diff.statements);
    }

    private void entity(EntityDefinition entity) {
        EntityModel model = entity.model();
        if (model == null) return;
        String table = model.table();
        boolean archivable = entity.capabilities().contains(EntityCapability.ARCHIVE);
        if (!hasTable(table)) {
            createTable(entity.code(), table, model.fields(), archivable);
        } else {
            for (EntityField field : model.fields()) {
                addColumns(table, field);
            }
            if (archivable && !hasColumn(table, "archived_at")) {
                addArchive(table);
            }
        }
        for (EntityField field : model.fields()) {
            if (field.source() instanceof FieldSource.Link link && !hasTable(link.table())) {
                createLink(table, link, field);
            }
        }
        for (EntityCollection collection : model.collections()) {
            collection(table, collection);
        }
    }

    private void createTable(String code, String table, List<EntityField> fields, boolean archivable) {
        List<String> columns = new ArrayList<>();
        List<String> after = new ArrayList<>();
        columns.add("id bigint generated always as identity constraint " + table + "_pkey primary key");
        for (EntityField field : fields) {
            for (ColumnDdl column : columnsOf(table, field)) {
                columns.add(column.definition(isRequired(field)));
                after.addAll(column.after());
            }
        }
        columns.add("attributes jsonb not null default '{}'::jsonb constraint " + table
                + "_ck_attributes check (jsonb_typeof(attributes) = 'object')");
        if (archivable) {
            columns.add("archived_at timestamptz");
            columns.add("archived_by bigint constraint " + table + "_fk_archived_by references md_users (id)");
            after.add(index(table, "archived_by"));
        }
        for (String author : List.of("created_by", "modified_by")) {
            columns.add(
                    author + " bigint not null constraint " + table + "_fk_" + author + " references md_users (id)");
            after.add(index(table, author));
        }
        columns.add("created_at timestamptz not null default clock_timestamp()");
        columns.add("modified_at timestamptz not null default clock_timestamp()");
        columns.add("revision bigint not null default 1");
        statements.add("-- The table of the entity " + code + " (ADR-0020; ADR-0032, 14.1).\ncreate table " + table
                + " (\n    " + String.join(",\n    ", columns) + "\n);");
        statements.addAll(after);
    }

    private void addColumns(String table, EntityField field) {
        for (ColumnDdl column : columnsOf(table, field)) {
            if (hasColumn(table, column.name())) continue;
            String note = isRequired(field)
                    ? "-- " + field.key() + " is required on the form; the column takes null for the rows it finds.\n"
                    : "";
            statements.add(note + "alter table " + table + " add column " + column.definition(false) + ";");
            statements.addAll(column.after());
        }
    }

    private void addArchive(String table) {
        statements.add("alter table " + table + "\n    add column archived_at timestamptz,\n    add column archived_by"
                + " bigint constraint " + table + "_fk_archived_by references md_users (id);");
        statements.add(index(table, "archived_by"));
    }

    private void createLink(String owner, FieldSource.Link link, EntityField field) {
        String table = link.table();
        String target = tableOf(field.options().target());
        List<String> columns = new ArrayList<>();
        columns.add(link.ownerColumn() + " bigint not null constraint " + table + "_fk_" + stem(link.ownerColumn())
                + " references " + owner + " (id) on delete cascade");
        columns.add(link.targetColumn() + " bigint not null" + reference(table, link.targetColumn(), target));
        if (link.kindColumn() != null) {
            columns.add(link.kindColumn() + " text not null");
        }
        columns.add("position integer not null default 0");
        String key = link.kindColumn() == null
                ? link.ownerColumn() + ", " + link.targetColumn()
                : link.ownerColumn() + ", " + link.kindColumn() + ", " + link.targetColumn();
        columns.add("constraint " + table + "_pkey primary key (" + key + ")");
        statements.add("create table " + table + " (\n    " + String.join(",\n    ", columns) + "\n);");
        statements.add(index(table, link.targetColumn()));
    }

    private void collection(String parent, EntityCollection collection) {
        String table = collection.table();
        if (hasTable(table)) {
            collection.fields().forEach(field -> addColumns(table, field));
            return;
        }
        List<String> columns = new ArrayList<>();
        List<String> after = new ArrayList<>();
        columns.add("id bigint generated always as identity constraint " + table + "_pkey primary key");
        columns.add(collection.parentColumn() + " bigint not null constraint " + table + "_fk_"
                + stem(collection.parentColumn()) + " references " + parent + " (id) on delete cascade");
        columns.add(collection.positionColumn() + " integer not null constraint " + table + "_ck_"
                + collection.positionColumn() + " check (" + collection.positionColumn() + " >= 1)");
        for (EntityField field : collection.fields()) {
            for (ColumnDdl column : columnsOf(table, field)) {
                columns.add(column.definition(isRequired(field)));
                after.addAll(column.after());
            }
        }
        statements.add("create table " + table + " (\n    " + String.join(",\n    ", columns) + "\n);");
        statements.add("create index " + table + "_" + collection.parentColumn() + "_idx on " + table + " ("
                + collection.parentColumn() + ", " + collection.positionColumn() + ");");
        statements.addAll(after);
    }

    /** The columns a field writes: none for a value read from SQL, an attribute or a system column. */
    private List<ColumnDdl> columnsOf(String table, EntityField field) {
        return switch (field.source()) {
            case FieldSource.Column column -> List.of(column(table, column.name(), field));
            case FieldSource.MoneyColumns money -> {
                List<ColumnDdl> columns = new ArrayList<>();
                columns.add(new ColumnDdl(money.amount(), "numeric(19, 4)", List.of()));
                if (money.currency() != null) {
                    columns.add(new ColumnDdl(
                            money.currency(),
                            "text constraint " + table + "_ck_" + money.currency() + " check (" + money.currency()
                                    + " ~ '^[A-Z]{3}$')",
                            List.of()));
                }
                yield columns;
            }
            default -> List.of();
        };
    }

    private ColumnDdl column(String table, String name, EntityField field) {
        FieldRules rules = field.form() == null ? FieldRules.NONE : field.form().rules();
        String check = "constraint " + table + "_ck_" + name + " check (";
        return switch (field.type()) {
            case TEXT, TEXTAREA, MARKDOWN, EMAIL, PHONE, URL, ENUM ->
                new ColumnDdl(
                        name,
                        rules.maxLength() == null
                                ? "text"
                                : "text " + check + "char_length(" + name + ") <= " + rules.maxLength() + ")",
                        List.of());
            case SELECT ->
                new ColumnDdl(
                        name,
                        "text " + check + name + " in ("
                                + field.options().options().stream()
                                        .map(option -> "'" + option.replace("'", "''") + "'")
                                        .collect(Collectors.joining(", "))
                                + "))",
                        List.of());
            case NUMBER ->
                new ColumnDdl(name, "numeric(19, " + (rules.scale() == null ? 4 : rules.scale()) + ")", List.of());
            case DATE -> new ColumnDdl(name, "date", List.of());
            case DATETIME -> new ColumnDdl(name, "timestamptz", List.of());
            case TIME -> new ColumnDdl(name, "time", List.of());
            case BOOLEAN -> new ColumnDdl(name, "boolean", List.of());
            case JSON -> new ColumnDdl(name, "jsonb", List.of());
            case FILE, IMAGE ->
                new ColumnDdl(name, "uuid" + reference(table, name, "mf_files"), List.of(index(table, name)));
            case REF ->
                new ColumnDdl(
                        name,
                        "bigint"
                                + reference(table, name, tableOf(field.options().target())),
                        List.of(index(table, name)));
            case MONEY, MULTI_REF -> new ColumnDdl(name, "text", List.of());
        };
    }

    private boolean hasTable(String table) {
        return schema.columns(table) != null;
    }

    private boolean hasColumn(String table, String column) {
        Map<String, EntitySchemaCheck.Column> columns = schema.columns(table);
        return columns != null && columns.containsKey(column);
    }

    private @Nullable String tableOf(@Nullable String entity) {
        return entity == null ? null : tablesByEntity.get(entity);
    }

    private static String reference(String table, String column, @Nullable String target) {
        return target == null ? "" : " constraint " + table + "_fk_" + stem(column) + " references " + target + " (id)";
    }

    private static String index(String table, String column) {
        return "create index " + table + "_" + column + "_idx on " + table + " (" + column + ");";
    }

    /** {@code customer_id} names its constraint {@code _fk_customer}. */
    private static String stem(String column) {
        return column.endsWith("_id") ? column.substring(0, column.length() - 3) : column;
    }

    private static boolean isRequired(EntityField field) {
        FormPart form = field.form();
        return form != null && form.required() && field.type() != FieldType.BOOLEAN;
    }

    /** A column with its type and constraints, and the statements that follow the table (its index). */
    private record ColumnDdl(String name, String type, List<String> after) {
        String definition(boolean notNull) {
            int constraint = type.indexOf(" constraint ");
            if (!notNull) return name + " " + type;
            return constraint < 0
                    ? name + " " + type + " not null"
                    : name + " " + type.substring(0, constraint) + " not null" + type.substring(constraint);
        }
    }
}
