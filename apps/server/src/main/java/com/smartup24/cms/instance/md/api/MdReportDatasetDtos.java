package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** The datasets an administrator defines for reports (ADR-0044): keys and a filter of the list's DSL, never SQL. */
public final class MdReportDatasetDtos {
    public static final String CODE = "[a-z][a-z0-9_]{0,39}";
    public static final int MAX_FIELDS = 40;
    public static final int MAX_MEASURES = 8;

    private MdReportDatasetDtos() {}

    /**
     * A measure computed by a formula over the aggregates of the dataset's fields: {@code count}, {@code sum_<field>},
     * {@code avg_<field>}, {@code min_<field>}, {@code max_<field>}, {@code distinct_<field>}.
     */
    @Schema(name = "ReportDatasetMeasure")
    public record Measure(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[a-z][a-zA-Z0-9]{0,63}")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Valid @NotNull
            Labels labels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000, example = "sum_total / count")
            String formula) {}

    /**
     * A dataset to create or change. The code is given on creation only and never changes.
     *
     * @param entity the source entity — a live form or an entity declared in code
     * @param lines  the key of a table of the entity's document lines: one row per line; none — one per record
     * @param fields the keys of the fields reports may use; a line's field as {@code line<Key>}
     * @param filter the fixed filter in the list's DSL (a JSON array), applied to every report
     */
    @Schema(name = "ReportDatasetRequest")
    public record DatasetRequest(
            @Schema(pattern = CODE) @Nullable String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Valid @NotNull
            Labels name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String entity,

            @Nullable String lines,

            @ArraySchema(
                    minItems = 1,
                    maxItems = MAX_FIELDS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> fields,

            @Schema(types = {"array", "null"}) @Nullable JsonNode filter,

            @ArraySchema(maxItems = MAX_MEASURES) @Nullable List<@Valid Measure> measures) {}

    @Schema(name = "ReportDataset")
    public record Dataset(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String entity,

            @Nullable String lines,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> fields,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = "array")
            JsonNode filter,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Measure> measures,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant modifiedAt) {}
}
