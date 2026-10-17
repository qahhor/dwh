package com.smartup24.cms.instance.studio.api;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Live activation, the catalog state and the quarantine of stored forms (ADR-0038); the versions and lifecycle of a
 * live form (ADR-0041).
 */
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

    /** Bounded by the catalog: at most 501 stored forms are read, one quarantine row per form. */
    @Schema(name = "StudioCatalogStatus")
    public record CatalogStatus(
            int stored,
            int served,
            @ArraySchema(maxItems = 501) List<BrokenForm> broken,
            @ArraySchema(maxItems = 501) List<QuarantinedForm> quarantined) {}

    /**
     * A live form in the list of the publisher (ADR-0041): its active version and lifecycle, how many versions and
     * records it has, and whether it is quarantined or fails to load. {@code revision} is what a change names in
     * {@code If-Match}.
     */
    @Schema(name = "StudioFormSummary")
    public record FormSummary(
            String entityCode,
            Labels title,

            @Schema(allowableValues = {"active", "archived"})
            String status,

            int activeVersion,
            int versions,

            @Schema(description = "Records that are not archived")
            long records,

            boolean quarantined,

            @Schema(
                    nullable = true,
                    allowableValues = {"declaration", "code_mismatch", "declared_code", "translation_key", "form_limit"
                    })
            @Nullable
            String broken,

            long revision,
            Instant createdAt,
            Instant modifiedAt) {}

    /** One immutable version of a live form; version 1 is the activated form. */
    @Schema(name = "StudioFormVersion")
    public record FormVersion(int version, String checksum, Instant createdAt, long createdBy) {}

    /** A live form with the declaration of its active version and every version it has. */
    @Schema(name = "StudioFormDetail")
    public record FormDetail(
            FormSummary form,
            Declaration declaration,
            String checksum,
            @ArraySchema(maxItems = 100) List<FormVersion> versions) {}
}
