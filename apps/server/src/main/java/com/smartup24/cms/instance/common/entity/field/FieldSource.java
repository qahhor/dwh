package com.smartup24.cms.instance.common.entity.field;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where the value of an entity field lives (ADR-0032, 3.1). The names and the expressions are written in module code
 * and checked here, so only declared identifiers reach SQL (ADR-0032, 12); values are always parameters.
 *
 * <p>Money columns and link tables of the types of plan 10/10, item 5.2 join this list with their types.
 */
public sealed interface FieldSource
        permits FieldSource.Column,
                FieldSource.Expression,
                FieldSource.Computed,
                FieldSource.Attribute,
                FieldSource.SystemValue {

    /** A table or column name: lower-case letters, digits and underscores. */
    Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** The value read in the entity's list, an expression over its table aliased {@code alias}. */
    String sql(String alias);

    /** Whether a save writes it: only a column or an attribute; the server writes the rest. */
    boolean writable();

    /** A column of the entity's table: {@code title} reads as {@code n.title}. */
    record Column(String name) implements FieldSource {
        public Column {
            requireIdentifier(name);
        }

        @Override
        public String sql(String alias) {
            return alias + "." + name;
        }

        @Override
        public boolean writable() {
            return true;
        }
    }

    /** An expression over the entity's {@code from}, as a list field's SQL: read-only. */
    record Expression(String sql) implements FieldSource {
        public Expression {
            requireExpression(sql);
        }

        @Override
        public String sql(String alias) {
            return sql;
        }

        @Override
        public boolean writable() {
            return false;
        }
    }

    /**
     * A computed value ({@code qty * price}): read-only; its read-only form field comes with the form flags of plan
     * 10/10, item 5.2 (ADR-0032, 4.4).
     */
    record Computed(String sql) implements FieldSource {
        public Computed {
            requireExpression(sql);
        }

        @Override
        public String sql(String alias) {
            return sql;
        }

        @Override
        public boolean writable() {
            return false;
        }
    }

    /** A value in the record's {@code attributes} jsonb under {@code code}: no migration, never sorted (ADR-0019, 2.3). */
    record Attribute(String code) implements FieldSource {
        public Attribute {
            requireIdentifier(code);
        }

        @Override
        public String sql(String alias) {
            return "(" + alias + ".attributes->>'" + code + "')";
        }

        @Override
        public boolean writable() {
            return true;
        }
    }

    /** A column the server keeps on every entity table (ADR-0032, 14.1): read-only. */
    record SystemValue(SystemColumn column) implements FieldSource {
        public SystemValue {
            Objects.requireNonNull(column, "column");
        }

        @Override
        public String sql(String alias) {
            return alias + "." + column.column();
        }

        @Override
        public boolean writable() {
            return false;
        }
    }

    /** The system columns and the record properties they answer as. */
    enum SystemColumn {
        ID("id"),
        REVISION("revision"),
        CREATED_AT("createdAt"),
        CREATED_BY("createdBy"),
        MODIFIED_AT("modifiedAt"),
        MODIFIED_BY("modifiedBy");

        private final String key;

        SystemColumn(String key) {
            this.key = key;
        }

        /** The record property ({@code createdAt}). */
        public String key() {
            return key;
        }

        /** The column ({@code created_at}). */
        public String column() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static void requireIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Bad identifier: " + name);
        }
    }

    private static void requireExpression(String sql) {
        if (sql == null || sql.isBlank() || sql.contains(";") || sql.contains("--")) {
            throw new IllegalArgumentException("Bad field expression: " + sql);
        }
    }
}
