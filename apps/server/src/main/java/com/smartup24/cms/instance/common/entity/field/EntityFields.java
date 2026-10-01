package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.query.QueryRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The way a module declares an entity field (ADR-0032, 3.2): a type, then where the value lives, then the form rules
 * and flags and the list flags.
 *
 * <pre>{@code
 * text("title", "notes.col.title").column("title").required().length(1, 255).list(sortable().searchable())
 * text("rank", "notes.col.rank").expression(RANK).listOnly(sortable().notFilterable().hidden())
 * money("total", "orders.col.total", "UZS", "USD").money("total_amount", "total_currency").required()
 * text("number", "orders.col.number").column("number").defaultValue(FieldDefault.sequence("ex_orders_number_seq",
 *         "ORD-{000000}"))
 * }</pre>
 *
 * <p>A written field (a column, an attribute, money columns, a link table) is on the form and in the list unless
 * {@link Builder#listOnly} or {@link Builder#formOnly} says otherwise; a computed value is on the form read-only and
 * in the list (ADR-0032, 4.1); an expression and a system column are in the list only.
 */
public final class EntityFields {

    private EntityFields() {}

    public static Builder text(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.TEXT, FieldOptions.NONE);
    }

    public static Builder textarea(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.TEXTAREA, FieldOptions.NONE);
    }

    public static Builder markdown(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.MARKDOWN, FieldOptions.NONE);
    }

    public static Builder number(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.NUMBER, FieldOptions.NONE);
    }

    public static Builder date(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.DATE, FieldOptions.NONE);
    }

    /** A moment with its offset; an instant in the list. */
    public static Builder instant(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.DATETIME, FieldOptions.NONE);
    }

    public static Builder time(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.TIME, FieldOptions.NONE);
    }

    public static Builder bool(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.BOOLEAN, FieldOptions.NONE);
    }

    /** A choice of {@code options}; an enumeration with the same options and labels in the list. */
    public static Builder select(
            String key, String labelKey, List<String> options, @Nullable String optionLabelPrefix) {
        return new Builder(key, labelKey, FieldType.SELECT, FieldOptions.choice(options, optionLabelPrefix));
    }

    /** The key of a row of another list, picked by name from {@code source} (ADR-0019, 2.4). */
    public static Builder ref(String key, String labelKey, QueryRef source) {
        return new Builder(key, labelKey, FieldType.REF, FieldOptions.refersTo(source));
    }

    /** An e-mail address, kept in lower case. */
    public static Builder email(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.EMAIL, FieldOptions.NONE);
    }

    /** A phone number in E.164. */
    public static Builder phone(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.PHONE, FieldOptions.NONE);
    }

    /** An {@code http} or {@code https} address. */
    public static Builder url(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.URL, FieldOptions.NONE);
    }

    /** An amount in one of {@code currencies} (ISO 4217), the first offered first; lives in {@link Builder#money}. */
    public static Builder money(String key, String labelKey, String... currencies) {
        return new Builder(key, labelKey, FieldType.MONEY, FieldOptions.money(List.of(currencies)));
    }

    /** The code of an item of the reference entity {@code reference} (ADR-0032, 4.5). */
    public static Builder enumeration(String key, String labelKey, String reference) {
        return new Builder(key, labelKey, FieldType.ENUM, FieldOptions.enumeration(reference));
    }

    /** The keys of several rows of another list, picked by name from {@code source}; lives in {@link Builder#link}. */
    public static Builder multiRef(String key, String labelKey, QueryRef source) {
        return new Builder(key, labelKey, FieldType.MULTI_REF, FieldOptions.refersTo(source));
    }

    /** A stored file, its id in a {@code uuid} column (ADR-0032, 4.7). */
    public static Builder file(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.FILE, FieldOptions.NONE);
    }

    /** A stored PNG, JPEG or WebP image, its id in a {@code uuid} column. */
    public static Builder image(String key, String labelKey) {
        return new Builder(key, labelKey, FieldType.IMAGE, FieldOptions.NONE);
    }

    /** A JSON value whose root is {@code root}, or an object or an array when null. */
    public static Builder json(String key, String labelKey, FieldOptions.@Nullable JsonRoot root) {
        return new Builder(key, labelKey, FieldType.JSON, FieldOptions.json(root));
    }

    /** A visible, filterable list column. */
    public static ListPart listed() {
        return ListPart.LISTED;
    }

    public static ListPart sortable() {
        return ListPart.LISTED.sortable();
    }

    public static ListPart searchable() {
        return ListPart.LISTED.searchable();
    }

    /** A filterable list column the viewer has to switch on. */
    public static ListPart hidden() {
        return ListPart.LISTED.hidden();
    }

    /** Collects one field's declaration; {@link #build()} checks it as a whole. */
    public static final class Builder {

        private final String key;
        private final String labelKey;
        private final FieldType type;
        private final FieldOptions options;
        private @Nullable FieldSource source;
        private boolean required;
        private FieldRules rules = FieldRules.NONE;
        private @Nullable FieldReadonly readonly;
        private @Nullable FieldDefault defaultValue;
        private @Nullable FieldCondition visibleWhen;
        private @Nullable ListPart list;
        private boolean listOnly;
        private boolean formOnly;

        private Builder(String key, String labelKey, FieldType type, FieldOptions options) {
            this.key = key;
            this.labelKey = Objects.requireNonNull(labelKey, "labelKey");
            this.type = type;
            this.options = options;
        }

        /** A column of the entity's table. */
        public Builder column(String name) {
            return from(new FieldSource.Column(name));
        }

        /** A read-only expression over the entity's {@code from}. */
        public Builder expression(String sql) {
            return from(new FieldSource.Expression(sql));
        }

        /** A read-only computed value, shown on the form read-only. */
        public Builder computed(String sql) {
            return from(new FieldSource.Computed(sql));
        }

        /** A value in the record's {@code attributes} under {@code code}. */
        public Builder attribute(String code) {
            return from(new FieldSource.Attribute(code));
        }

        /** A column the server keeps ({@code modified_at}). */
        public Builder system(SystemColumn column) {
            return from(new FieldSource.SystemValue(column));
        }

        /** The amount column and the currency column of money; no currency column for money in one currency. */
        public Builder money(String amountColumn, @Nullable String currencyColumn) {
            return from(new FieldSource.MoneyColumns(amountColumn, currencyColumn));
        }

        /** The link table of several references: a row per key, ordered by its {@code position}. */
        public Builder link(String table, String ownerColumn, String targetColumn) {
            return from(new FieldSource.Link(table, ownerColumn, targetColumn));
        }

        public Builder required() {
            this.required = true;
            return this;
        }

        public Builder length(@Nullable Integer shortest, @Nullable Integer longest) {
            this.rules = rules.length(shortest, longest);
            return this;
        }

        public Builder range(@Nullable BigDecimal smallest, @Nullable BigDecimal largest) {
            this.rules = rules.range(smallest, largest);
            return this;
        }

        public Builder matching(String regex) {
            this.rules = rules.matching(regex);
            return this;
        }

        /** Most digits after the point of a number. */
        public Builder scale(int digits) {
            this.rules = rules.withScale(digits);
            return this;
        }

        /** Most keys of a multiple reference (100 without it). */
        public Builder maxItems(int most) {
            this.rules = rules.withMaxItems(most);
            return this;
        }

        /** The largest file and the content types it may have; no types take any (an image: PNG, JPEG, WebP). */
        public Builder files(@Nullable Long largestBytes, String... contentTypes) {
            this.rules = rules.withFiles(largestBytes, List.of(contentTypes));
            return this;
        }

        /** Nobody changes it with a save: a hook, a default or the server writes it. */
        public Builder readonly() {
            return readonly(FieldReadonly.ALWAYS);
        }

        /** It is given on creation and kept after (a reference code). */
        public Builder readonlyOnUpdate() {
            return readonly(FieldReadonly.ON_UPDATE);
        }

        /** It cannot be changed while {@code condition} holds over the record (a posted document). */
        public Builder readonlyWhen(FieldCondition condition) {
            return readonly(FieldReadonly.when(condition));
        }

        /** The value a new record takes without it; a number from a sequence makes the field read-only. */
        public Builder defaultValue(FieldDefault value) {
            this.defaultValue = Objects.requireNonNull(value, "value");
            if (value instanceof FieldDefault.Sequence && readonly == null) {
                this.readonly = FieldReadonly.ALWAYS;
            }
            return this;
        }

        /** The form shows it only while {@code condition} holds; hidden, it is not required and keeps no value. */
        public Builder visibleWhen(FieldCondition condition) {
            this.visibleWhen = Objects.requireNonNull(condition, "condition");
            return this;
        }

        /** The field's list flags; without them a listed field is a visible, filterable column. */
        public Builder list(ListPart part) {
            this.list = Objects.requireNonNull(part, "part");
            return this;
        }

        /** The field is in the list only. */
        public Builder listOnly(ListPart part) {
            this.listOnly = true;
            return list(part);
        }

        /** The field is on the form only. */
        public Builder formOnly() {
            this.formOnly = true;
            return this;
        }

        public EntityField build() {
            FieldSource from = source;
            if (from == null) {
                throw new IllegalArgumentException("Entity field " + key + " says where its value lives");
            }
            if (listOnly && formOnly) {
                throw new IllegalArgumentException("Entity field " + key + " is on neither the form nor the list");
            }
            boolean onForm = from.writable() || from instanceof FieldSource.Computed;
            FormPart form =
                    listOnly || !onForm ? null : new FormPart(required, rules, readonly, defaultValue, visibleWhen);
            if (form == null && (required || !rules.equals(FieldRules.NONE) || hasFlags())) {
                throw new IllegalArgumentException("Entity field " + key + ": form rules need a form field");
            }
            ListPart listed = formOnly ? null : (list == null ? ListPart.LISTED : list);
            return new EntityField(
                    key,
                    labelKey,
                    null,
                    type,
                    from,
                    form,
                    listed,
                    FieldAccess.OPEN,
                    options,
                    listed != null,
                    from.writable(),
                    form != null && from.writable());
        }

        private boolean hasFlags() {
            return readonly != null || defaultValue != null || visibleWhen != null;
        }

        private Builder readonly(FieldReadonly mode) {
            this.readonly = mode;
            return this;
        }

        private Builder from(FieldSource declared) {
            if (source != null) {
                throw new IllegalArgumentException("Entity field " + key + " has one source");
            }
            this.source = declared;
            return this;
        }
    }
}
