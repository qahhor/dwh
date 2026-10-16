package com.smartup24.cms.instance.common.entity.report;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** A trusted, one-hop N:1 projection; declarations contain keys, never SQL (ADR-0039). */
public record EntityReportDataset(
        String code, String rootEntity, String titleKey, String reference, List<Projection> projections) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){1,3}");
    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]{0,63}");

    public EntityReportDataset {
        if (code == null
                || code.length() > 128
                || !CODE.matcher(code).matches()
                || rootEntity == null
                || !CODE.matcher(rootEntity).matches())
            throw new IllegalArgumentException("Invalid report dataset code");
        if (titleKey == null || titleKey.isBlank()) throw new IllegalArgumentException("A dataset needs a title");
        key(reference);
        projections = List.copyOf(projections);
        if (projections.isEmpty() || projections.size() > 16)
            throw new IllegalArgumentException("A dataset needs 1 to 16 projections");
        var keys = new HashSet<String>();
        for (Projection projection : projections) {
            if (!keys.add(projection.key())) throw new IllegalArgumentException("Duplicate dataset projection key");
        }
    }

    public record Projection(
            String key, String field, @Nullable String labelKey) {
        public Projection(String key, String field) {
            this(key, field, null);
        }

        public Projection {
            EntityReportDataset.key(key);
            EntityReportDataset.key(field);
            if (labelKey != null && (labelKey.isBlank() || labelKey.length() > 128))
                throw new IllegalArgumentException("Invalid dataset field label key");
        }
    }

    private static void key(String value) {
        if (!KEY.matcher(Objects.requireNonNull(value)).matches())
            throw new IllegalArgumentException("Invalid report dataset field key");
    }
}
