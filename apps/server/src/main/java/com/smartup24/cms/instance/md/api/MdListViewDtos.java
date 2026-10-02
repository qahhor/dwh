package com.smartup24.cms.instance.md.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Wire format of {@code /api/v1/list-views/{listCode}} and of the dashboard's widgets. */
public final class MdListViewDtos {

    /** A table view: columns, sort and filter (ADR-0016). */
    public static final String TABLE = "table";

    /** A report: grouping, measures, filter and chart (ADR-0032, 10.2). */
    public static final String REPORT = "report";

    /** A report shown on the viewer's dashboard. */
    public static final String WIDGET = "widget";

    private MdListViewDtos() {}

    /**
     * A view to save.
     *
     * @param kind {@code table} when absent
     */
    public record ViewRequest(
            String name,
            JsonNode state,
            Boolean isDefault,
            Integer lockVersion,

            @Schema(allowableValues = {TABLE, REPORT, WIDGET}) @Nullable
            String kind) {}

    public record ViewResponse(
            long id,

            @Schema(allowableValues = {TABLE, REPORT, WIDGET})
            String kind,

            String name,
            JsonNode state,
            boolean isDefault,
            int lockVersion,
            Instant modifiedAt) {}

    /**
     * A widget of the viewer's dashboard: a report saved on an entity's list.
     *
     * @param listCode the list it was saved on
     * @param entity   the entity whose list it is: the report runs at {@code /api/v1/entities/{entity}/reports/{id}}
     */
    public record WidgetResponse(
            long id,
            String listCode,
            String entity,
            String name,
            JsonNode state,
            int lockVersion,
            Instant modifiedAt) {}
}
