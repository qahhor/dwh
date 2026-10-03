package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where the value of an entity field lives (ADR-0032, 3.1). The names and the expressions are written in module code
 * and checked here, so only declared identifiers reach SQL (ADR-0032, 12); values are always parameters.
 *
 * <p>The pair of money columns and the link table of several references came with their types (plan 10/10, item
 * 5.2).
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public sealed interface FieldSource
        permits FieldSource.Column,
                FieldSource.Expression,
                FieldSource.Computed,
                FieldSource.Attribute,
                FieldSource.SystemValue,
                FieldSource.MoneyColumns,
                FieldSource.Link {

    /** A table or column name: lower-case letters, digits and underscores. */
    Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /** The value read in the entity's list, an expression over its table aliased {@code alias}. */
    String sql(String alias);

    /** Whether a save writes it: a column, an attribute, a pair of money columns or a link table. */
    boolean writable();

    /** A column of the entity's table: {@code title} reads as {@code n.title}. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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

    /** A computed value ({@code qty * price}): read-only; on the form it is read-only and marked computed. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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

    /**
     * The amount column of a money field and its currency column ({@code total_amount}, {@code total_currency}), or
     * no currency column when the field holds one currency only (ADR-0032, 4.1). {@link #sql} reads the amount: the
     * list sorts and filters by it.
     */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record MoneyColumns(String amount, @Nullable String currency) implements FieldSource {
        public MoneyColumns {
            requireIdentifier(amount);
            if (currency != null) {
                requireIdentifier(currency);
            }
        }

        @Override
        public String sql(String alias) {
            return alias + "." + amount;
        }

        /** The currency as SQL: its column, or the field's one currency as a literal. */
        public String currencySql(String alias, String fixed) {
            if (currency != null) return alias + "." + currency;
            if (!CURRENCY.matcher(fixed).matches()) {
                throw new IllegalArgumentException("Bad currency: " + fixed);
            }
            return "'" + fixed + "'";
        }

        @Override
        public boolean writable() {
            return true;
        }
    }

    /**
     * The link table of several references ({@code ex_order_tags(order_id, tag_id, position)}): a row per key, in the
     * order of {@code position} (ADR-0032, 4.1). {@link #sql} reads the keys as a {@code bigint[]}.
     *
     * <p>A table shared by several fields tells each field's rows by a kind column: the participants of a task
     * ({@code ms_task_members(task_id, user_id, involve_kind, position)}) hold the executors under {@code E} and the
     * observers under {@code O}; a field reads and replaces only the rows of its kind.
     *
     * @param kindColumn the column that tells the field's rows, or null when the table is the field's alone
     * @param kind       the value of that column on the field's rows: letters, digits, underscores
     */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record Link(
            String table,
            String ownerColumn,
            String targetColumn,
            @Nullable String kindColumn,
            @Nullable String kind) implements FieldSource {
        public Link {
            requireIdentifier(table);
            requireIdentifier(ownerColumn);
            requireIdentifier(targetColumn);
            if ((kindColumn == null) != (kind == null)) {
                throw new IllegalArgumentException("A link table's kind column goes with its kind");
            }
            if (kindColumn != null) {
                requireIdentifier(kindColumn);
                if (!KIND.matcher(Objects.requireNonNull(kind)).matches()) {
                    throw new IllegalArgumentException("Bad link kind: " + kind);
                }
            }
        }

        /** A link table that holds the rows of one field. */
        public Link(String table, String ownerColumn, String targetColumn) {
            this(table, ownerColumn, targetColumn, null, null);
        }

        @Override
        public String sql(String alias) {
            return "array(select l." + targetColumn + " from " + table + " l where l." + ownerColumn + " = " + alias
                    + ".id" + kindSql("l") + " order by l.position, l." + targetColumn + ")";
        }

        /** The condition on the field's kind over the link table aliased {@code alias}: {@code ""} without a kind. */
        public String kindSql(String alias) {
            return kindColumn == null ? "" : " and " + alias + "." + kindColumn + " = '" + kind + "'";
        }

        @Override
        public boolean writable() {
            return true;
        }
    }

    /** The kind of a shared link table's rows: a literal in SQL, so letters, digits and underscores only. */
    Pattern KIND = Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    /** An ISO 4217 currency code. */
    Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** The system columns and the record properties they answer as. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
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
