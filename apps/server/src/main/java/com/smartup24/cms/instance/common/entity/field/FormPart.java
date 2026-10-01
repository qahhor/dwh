package com.smartup24.cms.instance.common.entity.field;

import java.util.Objects;

/**
 * What an entity field is on the form (ADR-0032, 3.1): whether a value must be given and the rules it must meet. The
 * read-only flags, defaults and conditional visibility of ADR-0032, 4.3–4.4 join it with plan 10/10, item 5.2.
 *
 * @param required a value must be given
 * @param rules    length, range and pattern
 */
public record FormPart(boolean required, FieldRules rules) {

    /** An optional field without rules. */
    public static final FormPart OPTIONAL = new FormPart(false, FieldRules.NONE);

    public FormPart {
        Objects.requireNonNull(rules, "rules");
    }

    public FormPart asRequired() {
        return new FormPart(true, rules);
    }

    public FormPart withRules(FieldRules changed) {
        return new FormPart(required, changed);
    }
}
