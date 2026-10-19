package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The record before and after a change (ADR-0046): published right after the {@link EntityChanged} event it carries, in
 * the transaction of the change, so the automations of live forms can judge a trigger by the old and the new values and
 * queue their runs with the change — a rollback takes them back. The maps are the records as the runtime read them,
 * every value of them; a create has no {@code before}, a deletion no {@code after}. {@code common} names no module,
 * so the event lives here.
 *
 * @param change the change as every listener sees it
 * @param before the record before the change, or null for a create
 * @param after  the record after the change, or null for a deletion
 */
public record EntityChangeImages(
        EntityChanged change,
        @Nullable Map<String, Object> before,
        @Nullable Map<String, Object> after) {

    public EntityChangeImages {
        before = before == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = after == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }
}
