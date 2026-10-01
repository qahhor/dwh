package com.smartup24.cms.instance.common.entity.hook;

import com.smartup24.cms.core.error.FieldErrorItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The problems a rule or a hook reports (ADR-0032, 6.6 and 6.12): each addressed to a field ({@code endDate},
 * {@code attributes.cfRegion}) or to the whole record ({@code ""}), with a code and the catalog key of its text.
 */
public final class RuleErrors {

    /** The address of a problem of the whole record. */
    public static final String RECORD = "";

    private final List<FieldErrorItem> items = new ArrayList<>();

    public void field(String key, String code, String messageKey) {
        items.add(FieldErrorItem.keyed(key, code, messageKey));
    }

    public void field(String key, String code, String messageKey, Map<String, ?> params) {
        items.add(FieldErrorItem.keyed(key, code, messageKey, params));
    }

    /** A problem of the record as a whole. */
    public void record(String code, String messageKey) {
        field(RECORD, code, messageKey);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The problems in the order they were reported. */
    public List<FieldErrorItem> items() {
        return List.copyOf(items);
    }
}
