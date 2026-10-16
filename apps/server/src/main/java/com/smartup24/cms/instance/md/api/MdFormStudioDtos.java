package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutDeclaration;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Stored authoring state and sealed packages; none is an active runtime definition (ADR-0035). */
public final class MdFormStudioDtos {
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;

    private MdFormStudioDtos() {}

    public record FormStudioDraft(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String entityCode,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormLayoutDeclaration declaration) {}

    public record FormStudioDraftResponse(
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            @Nullable
            FormStudioDraft draft,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormMeta meta) {}

    public record FormStudioDependencies(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1")
            int contractVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$")
            String metadataChecksum) {}

    public record FormStudioPublicationPackage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1")
            int formatVersion,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"form-layout-package"})
            String kind,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"example.products"})
            String entityCode,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
            long version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
            long sourceRevision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormStudioDependencies dependencies,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormLayoutDeclaration declaration) {}

    public record FormStudioVersionPackage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
            long version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9007199254740991")
            long sourceRevision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$")
            String checksum,

            @JsonProperty("package") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            FormStudioPublicationPackage artifact) {}

    public record FormStudioVersionSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long sourceRevision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String checksum,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt) {}

    public record FormStudioVerification(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean valid,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long sourceRevision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String checksum) {}
}
