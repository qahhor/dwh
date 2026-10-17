package com.smartup24.cms.instance.studio.api;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/**
 * The reference lists an administrator keeps without a release (ADR-0041): a list is a named set of items, each a
 * permanent code with its names in ru, uz and en, an order and whether new values may still take it. A live form field
 * refers to a list by its code and stores the code of an item.
 */
public final class StudioListDtos {
    private StudioListDtos() {}

    /** The code of a list. */
    public static final String CODE = "^[a-z][a-z0-9_]{0,39}$";

    /** The code of an item: what the records keep. */
    public static final String ITEM_CODE = "^[a-z0-9][a-z0-9_-]{0,63}$";

    /** Items of one list: as many as an enumeration reads (ADR-0032, 4.5). */
    public static final int MAX_ITEMS = 500;

    @Schema(name = "StudioListCreateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateList(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = CODE) @NotBlank @Pattern(regexp = CODE)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
            Labels name) {}

    @Schema(name = "StudioListUpdateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record UpdateList(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
            Labels name) {}

    @Schema(name = "StudioListItemCreateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CreateItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = ITEM_CODE)
            @NotBlank
            @Pattern(regexp = ITEM_CODE)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
            Labels name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9999") @Min(1) @Max(9999)
            int sortOrder) {}

    @Schema(name = "StudioListItemUpdateRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record UpdateItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
            Labels name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9999") @Min(1) @Max(9999)
            int sortOrder,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "False: kept for the records that hold it, no longer offered to new values")
            boolean active) {}

    /** A list in the catalog; {@code revision} is what a change of its names takes in {@code If-Match}. */
    @Schema(name = "StudioListSummary")
    public record ListSummary(
            String code,
            Labels name,

            @Schema(description = "Every item, inactive ones too")
            int items,

            int activeItems,
            long revision,
            Instant createdAt,
            Instant modifiedAt) {}

    /** One item; {@code revision} is what its change takes in {@code If-Match}. */
    @Schema(name = "StudioListItem")
    public record Item(String code, Labels name, int sortOrder, boolean active, long revision, Instant modifiedAt) {}

    /** A list with every item, in order. */
    @Schema(name = "StudioListDetail")
    public record ListDetail(
            ListSummary list,
            @ArraySchema(maxItems = MAX_ITEMS) List<Item> items) {}
}
