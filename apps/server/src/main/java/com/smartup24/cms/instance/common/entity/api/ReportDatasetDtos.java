package com.smartup24.cms.instance.common.entity.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Available trusted sources contain display metadata only, never SQL (ADR-0039). */
public final class ReportDatasetDtos {
    public static final int MAX_ITEMS = 16;

    private ReportDatasetDtos() {}

    @Schema(name = "ReportDatasetItem")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String titleKey) {}

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
