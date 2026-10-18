package com.smartup24.cms.instance.common.entity.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Available trusted sources contain display metadata only, never SQL (ADR-0039). */
public final class ReportDatasetDtos {
    public static final int MAX_ITEMS = 16;

    private ReportDatasetDtos() {}

    /**
     * A dataset a report may read.
     *
     * @param name the name an administrator gave the dataset (ADR-0044), in the language of the request; null for a
     *             dataset declared in code, named by its title key
     */
    @Schema(name = "ReportDatasetItem")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String titleKey,

            @Schema(nullable = true) @Nullable String name,

            @ArraySchema(
                    maxItems = 8,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(implementation = ItemMeasure.class))
            List<ItemMeasure> measures) {
        public Item {
            measures = List.copyOf(measures);
        }
    }

    /**
     * A measure an administrator's dataset computes by a formula over aggregates (ADR-0044), named by a report as
     * {@code {"op":"formula","field":"<key>"}}.
     */
    @Schema(name = "ReportDatasetItemMeasure")
    public record ItemMeasure(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String label) {}

    @Schema(name = "ReportDatasetCatalog")
    public record Catalog(
            @ArraySchema(
                    maxItems = MAX_ITEMS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(implementation = Item.class))
            List<Item> items) {
        public Catalog {
            items = List.copyOf(items);
            if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("Too many report datasets");
        }
    }
}
