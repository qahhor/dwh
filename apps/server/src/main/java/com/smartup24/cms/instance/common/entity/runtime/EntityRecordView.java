package com.smartup24.cms.instance.common.entity.runtime;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A record as the runtime answers it (ADR-0032, 6.2): the system properties ({@code id}, {@code revision},
 * {@code createdAt}…, {@code archived}), the fields the viewer may see, the custom field values in {@code attributes}
 * and {@code actions} — what this viewer may do with this record. A {@link Revisioned} answer: its revision goes out as
 * {@code ETag}.
 */
public final class EntityRecordView implements Revisioned {

    /** The property of the actions the viewer may take on the record. */
    public static final String ACTIONS = "actions";

    private final Map<String, Object> properties;

    public EntityRecordView(Map<String, Object> record, List<String> actions) {
        Map<String, Object> properties = new LinkedHashMap<>(record);
        properties.put(ACTIONS, List.copyOf(actions));
        this.properties = Collections.unmodifiableMap(properties);
    }

    public long id() {
        return ((Number) Objects.requireNonNull(properties.get(SystemColumn.ID.key()))).longValue();
    }

    @Override
    public long revision() {
        return ((Number) Objects.requireNonNull(properties.get(SystemColumn.REVISION.key()))).longValue();
    }

    /** Every property, as the JSON object of the answer. */
    @JsonAnyGetter
    public Map<String, Object> properties() {
        return properties;
    }
}
