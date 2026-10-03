package com.smartup24.cms.platform.api.entity.collection;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.FormSection;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.EntityFields;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormPart;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The rows of a document, declared with the document (ADR-0032, 9.1; plan 10/10, item 5.7): a child table whose rows
 * point at the record by {@code parentColumn} ({@code on delete cascade}) and keep their order in
 * {@code positionColumn}; the rows have no revision, author or history of their own — the record's revision rises and
 * its audit names the change when a row changes. A row's fields are declared as the record's ({@link EntityFields}),
 * every one on the line's form; the list flags of a field are not used.
 *
 * <pre>{@code
 * EntityCollection.of("lines", "example.orders.lines")
 *         .table("ex_order_lines", "l").parentColumn("order_id").positionColumn("position")
 *         .field(text("product", "example.orders.line.product").column("product").required().length(1, 255))
 *         .field(number("qty", "example.orders.line.qty").column("qty").required().scale(3))
 *         .maxRows(500)
 *         .build();
 * }</pre>
 *
 * <p>What a row may hold is narrower than a record (ADR-0032, 9.5): a column, a pair of money columns — the currency
 * may be the document's ({@link EntityFields.Builder#currencyFrom}) — or a computed value; no attribute, link table,
 * file, enumeration, JSON or reference checked against its target, and no default, condition or field right. Such
 * values need a check against the database per row; a document that needs them keeps a reference entity instead.
 *
 * @param key            the record property of the rows ({@code lines}) and the root of their problem addresses
 * @param labelKey       the dictionary key of the collection's title
 * @param table          the child table
 * @param alias          its alias in the statements of the rows
 * @param parentColumn   the column that names the record
 * @param positionColumn the column of the row's place, 1 for the first
 * @param fields         the fields of a row, in the order of the line's columns
 * @param maxRows        the most rows a record has and a save sends (ADR-0032, 19, question 4: 500)
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record EntityCollection(
        String key,
        String labelKey,
        String table,
        String alias,
        String parentColumn,
        String positionColumn,
        List<EntityField> fields,
        int maxRows) {

    /** The most rows of a document by default (ADR-0032, 19, question 4). */
    public static final int DEFAULT_MAX_ROWS = 500;

    /** The property of a row's id in the API. */
    public static final String ID = "id";

    /** The property of a row's place in the API, 1 for the first. */
    public static final String POSITION = "position";

    /** The alias of the document in the statement of its rows; a collection's own alias is another. */
    public static final String DOCUMENT_ALIAS = "doc_";

    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    /** What a row's field may hold: a value checked by its own rules, without the database. */
    private static final Set<FieldType> ROW_TYPES = Set.of(
            FieldType.TEXT,
            FieldType.TEXTAREA,
            FieldType.NUMBER,
            FieldType.DATE,
            FieldType.DATETIME,
            FieldType.TIME,
            FieldType.BOOLEAN,
            FieldType.SELECT,
            FieldType.EMAIL,
            FieldType.PHONE,
            FieldType.URL,
            FieldType.MONEY,
            FieldType.REF);

    public EntityCollection {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad collection key: " + key);
        }
        Objects.requireNonNull(labelKey, "labelKey");
        for (String name : List.of(table, alias, parentColumn, positionColumn)) {
            if (name == null || !FieldSource.IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("Collection " + key + ": bad identifier " + name);
            }
        }
        if (DOCUMENT_ALIAS.equals(alias)) {
            throw new IllegalArgumentException("Collection " + key + ": the alias " + alias + " is the document's");
        }
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("Collection " + key + " has no fields");
        }
        if (maxRows < 1 || maxRows > DEFAULT_MAX_ROWS) {
            throw new IllegalArgumentException("Collection " + key + ": between 1 and " + DEFAULT_MAX_ROWS + " rows");
        }
        Set<String> keys = new HashSet<>(Set.of(ID, POSITION));
        for (EntityField field : fields) {
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException(
                        "Collection " + key + ": duplicate or reserved field " + field.key());
            }
            checkField(key, field);
        }
    }

    private static void checkField(String collection, EntityField field) {
        String where = "Collection " + collection + ", field " + field.key() + ": ";
        FormPart form = field.form();
        if (form == null) {
            throw new IllegalArgumentException(where + "every field of a row is on the line's form");
        }
        if (!ROW_TYPES.contains(field.type()) || field.options().target() != null) {
            throw new IllegalArgumentException(where + "a row holds values checked without the database");
        }
        boolean source = field.source() instanceof FieldSource.Column
                || field.source() instanceof FieldSource.MoneyColumns
                || field.source() instanceof FieldSource.Computed;
        if (!source) {
            throw new IllegalArgumentException(where + "a row's value lives in a column or is computed");
        }
        boolean computed = field.source() instanceof FieldSource.Computed;
        if ((!computed && form.readonly() != null) || form.defaultValue() != null || form.visibleWhen() != null) {
            throw new IllegalArgumentException(where + "a row's field has no read-only mode, default or condition");
        }
        if (!FieldAccess.OPEN.equals(field.access())) {
            throw new IllegalArgumentException(where + "a row's field has no right of its own");
        }
    }

    /** A collection with its key and the dictionary key of its title. */
    public static Builder of(String key, String labelKey) {
        return new Builder(key, labelKey);
    }

    /** The field of a row declared under {@code fieldKey}, if any. */
    public Optional<EntityField> field(String fieldKey) {
        return fields.stream().filter(field -> field.key().equals(fieldKey)).findFirst();
    }

    /** The fields of the line's form, in order. */
    public List<FormField> formFields() {
        return fields.stream()
                .flatMap(field -> Stream.ofNullable(field.formField()))
                .toList();
    }

    /** The fields a save of a row writes: its columns and money columns. */
    public List<EntityField> written() {
        return fields.stream().filter(field -> field.source().writable()).toList();
    }

    /**
     * A row as a form of its own, so the rules of the record's fields check it ({@code EntityValidator}): the line of
     * {@code entity}, without a table, every field in one section.
     */
    public EntityDefinition line(String entity) {
        List<FormField> form = formFields();
        return new EntityDefinition(
                entity + "." + key,
                entity,
                null,
                null,
                null,
                null,
                form,
                List.of(new FormSection(
                        "line", labelKey, form.stream().map(FormField::key).toList())),
                List.of(),
                Set.of());
    }

    /** Collects a collection's declaration; {@link #build()} checks it as a whole. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public static final class Builder {

        private final String key;
        private final String labelKey;
        private String table = "";
        private String alias = "";
        private String parentColumn = "";
        private String positionColumn = POSITION;
        private final List<EntityField> fields = new ArrayList<>();
        private int maxRows = DEFAULT_MAX_ROWS;

        private Builder(String key, String labelKey) {
            this.key = key;
            this.labelKey = labelKey;
        }

        /** The child table and its alias. */
        public Builder table(String name, String tableAlias) {
            this.table = name;
            this.alias = tableAlias;
            return this;
        }

        /** The column that names the record ({@code order_id}). */
        public Builder parentColumn(String name) {
            this.parentColumn = name;
            return this;
        }

        /** The column of the row's place; {@code position} by default. */
        public Builder positionColumn(String name) {
            this.positionColumn = name;
            return this;
        }

        public Builder field(EntityFields.Builder field) {
            return field(field.build());
        }

        public Builder field(EntityField field) {
            fields.add(field);
            return this;
        }

        /** The most rows of a record; {@value #DEFAULT_MAX_ROWS} by default. */
        public Builder maxRows(int most) {
            this.maxRows = most;
            return this;
        }

        public EntityCollection build() {
            return new EntityCollection(key, labelKey, table, alias, parentColumn, positionColumn, fields, maxRows);
        }
    }
}
