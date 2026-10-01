package com.smartup24.cms.instance.common.entity.field;

import com.smartup24.cms.instance.common.query.QueryRef;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The parameters of a field's type (ADR-0032, 3.1): the options of a select and the dictionary prefix of their labels,
 * the source of a reference. The enumeration source, currency and file rules of plan 10/10, item 5.2 join them.
 *
 * @param options           the values a select offers, in order
 * @param optionLabelPrefix dictionary prefix of the option labels ({@code notes.color_}); null shows the value
 * @param ref               where a reference field's rows come from (ADR-0019, 2.4), or null
 */
public record FieldOptions(
        List<String> options,
        @Nullable String optionLabelPrefix,
        @Nullable QueryRef ref) {

    /** No parameters. */
    public static final FieldOptions NONE = new FieldOptions(List.of(), null, null);

    public FieldOptions {
        options = List.copyOf(options);
    }
}
