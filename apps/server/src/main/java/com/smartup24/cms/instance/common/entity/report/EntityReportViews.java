package com.smartup24.cms.instance.common.entity.report;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Where the reports people saved live (ADR-0032, 10.2): a report or a widget is a saved view of a list, kept by the md
 * module ({@code md_list_views}); {@code common} knows no module, so md implements this.
 */
public interface EntityReportViews {

    /**
     * An owned or explicitly role-shared report or widget, revalidated for the current reader (ADR-0036).
     * Missing access, unavailable fields or state, another list and a table view all answer empty.
     */
    Optional<SavedReport> report(long userId, String listCode, long viewId);

    /**
     * What a saved report asks for, as the view holds it: its canonical state (ADR-0032, 10.2; ADR-0044) — groups,
     * measures, filter, order, top, pivot, totals, comparison and parameters — and the dataset it reads.
     */
    record SavedReport(JsonNode state, @Nullable String dataset) {
        /** The saved filter DSL as JSON text, or null. */
        public @Nullable String filter() {
            JsonNode filter = state.get("filter");
            return filter == null || filter.isNull() ? null : filter.toString();
        }
    }
}
