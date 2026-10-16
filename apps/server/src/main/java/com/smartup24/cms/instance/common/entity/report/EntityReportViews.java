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
     * What a saved report asks for, as the view holds it.
     *
     * @param groupBy  the grouping, a JSON array
     * @param measures the measures, a JSON array
     * @param filter   the filter DSL as JSON text, or null
     */
    record SavedReport(
            @Nullable JsonNode groupBy,
            @Nullable JsonNode measures,
            @Nullable String filter,
            @Nullable String dataset) {}
}
