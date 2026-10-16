package com.smartup24.cms.platform.api.entity;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import java.util.Set;

/** The immutable server-owned row partition of a shared entity table (ADR-0038). */
@PlatformApi(since = "2.0", stability = Stability.EXPERIMENTAL)
public record EntityPartition(String column, long value) {
    private static final Set<String> SYSTEM = Set.of(
            "id",
            "revision",
            "created_at",
            "created_by",
            "modified_at",
            "modified_by",
            "archived_at",
            "archived_by",
            "attributes");

    public EntityPartition {
        if (column == null
                || !FieldSource.IDENTIFIER.matcher(column).matches()
                || SYSTEM.contains(column)
                || value < 1) {
            throw new IllegalArgumentException("A partition needs a non-system column and a positive key");
        }
    }
}
