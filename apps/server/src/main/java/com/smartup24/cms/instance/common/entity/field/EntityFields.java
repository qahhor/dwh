package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.query.QueryRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The way a module declares an entity field (ADR-0032, 3.2): a type, then where the value lives, then the form rules
 * and the list flags.
 *
 * <pre>{@code
 * text("title", "notes.col.title").column("title").required().length(1, 255).list(sortable().searchable())
 * text("rank", "notes.col.rank").expression(RANK).listOnly(sortable().notFilterable().hidden())
 * }</pre>
 *
 * <p>A written field (a column or an attribute) is on the form and in the list unless {@link Builder#listOnly} or
 * {@link Builder#formOnly} says otherwise; a read-only one (an expression, a computed value, a system column) is in
 * the list only.
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
        return new Builder(key, labelKey, FieldType.SELECT, new FieldOptions(options, optionLabelPrefix, null));
    }

    /** The key of a row of another list, picked by name from {@code source} (ADR-0019, 2.4). */
    public static Builder ref(String key, String labelKey, QueryRef source) {
        return new Builder(key, labelKey, FieldType.REF, new FieldOptions(List.of(), null, source));
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

        /** A read-only computed value. */
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
            FormPart form = listOnly || !from.writable() ? null : new FormPart(required, rules);
            if (form == null && (required || !rules.equals(FieldRules.NONE))) {
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
                    form != null);
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
