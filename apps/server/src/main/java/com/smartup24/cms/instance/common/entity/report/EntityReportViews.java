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
     * The report or widget {@code viewId} that the user saved on the list; empty when there is none of theirs — a view
     * of another person, of another list and a table view answer the same.
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
            @Nullable String filter) {}
}
