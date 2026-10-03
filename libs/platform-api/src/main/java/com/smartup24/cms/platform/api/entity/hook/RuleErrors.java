package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The problems a rule or a hook reports (ADR-0032, 6.6 and 6.12): each addressed to a field ({@code endDate},
 * {@code attributes.cfRegion}) or to the whole record ({@code ""}), with a code and the catalog key of its text.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public final class RuleErrors {

    /** The address of a problem of the whole record. */
    public static final String RECORD = "";

    private final List<Problem> items = new ArrayList<>();

    /**
     * One problem: the field it is addressed to ({@link #RECORD} for the whole record), its code, the catalog key of
     * its text and the parameters of that text.
     */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public record Problem(String field, String code, String messageKey, Map<String, Object> params) {
        public Problem {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(messageKey, "messageKey");
            params = Map.copyOf(params);
        }
    }

    public void field(String key, String code, String messageKey) {
        items.add(new Problem(key, code, messageKey, Map.of()));
    }

    public void field(String key, String code, String messageKey, Map<String, ?> params) {
        items.add(new Problem(key, code, messageKey, Map.copyOf(params)));
    }

    /** A problem of the record as a whole. */
    public void record(String code, String messageKey) {
        field(RECORD, code, messageKey);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The problems in the order they were reported. */
    public List<Problem> items() {
        return List.copyOf(items);
    }
}
