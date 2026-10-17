package com.smartup24.cms.instance.studio.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Live create-only activation, the catalog state and the quarantine of stored forms (ADR-0038). */
public final class StudioFormDtos {
    private StudioFormDtos() {}

    /** The code of a live form. */
    public static final String CODE = "^studio\\.[a-z][a-z0-9_]{0,19}$";

    @Schema(name = "StudioAvailability")
    public record Availability(boolean enabled, String namespace, int maxForms) {}

    @Schema(name = "StudioActivatedForm")
    public record Activated(String entityCode, String checksum) {}

    @Schema(name = "StudioQuarantineRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record QuarantineRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = CODE) @NotBlank @Pattern(regexp = CODE)
            String entityCode,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
            @NotBlank
            @Size(max = 500)
            String reason) {}

    @Schema(name = "StudioQuarantinedForm")
    public record QuarantinedForm(String entityCode, String reason, Instant quarantinedAt, long quarantinedBy) {}

    @Schema(name = "StudioBrokenForm")
    public record BrokenForm(
            String entityCode,

            @Schema(
                    allowableValues = {"declaration", "code_mismatch", "declared_code", "translation_key", "form_limit"
                    })
            String reason) {}

    /** Bounded by the catalog: at most 101 stored forms are read, one quarantine row per form. */
    @Schema(name = "StudioCatalogStatus")
    public record CatalogStatus(
            int stored,
            int served,
            @ArraySchema(maxItems = 101) List<BrokenForm> broken,
            @ArraySchema(maxItems = 101) List<QuarantinedForm> quarantined) {}
}
