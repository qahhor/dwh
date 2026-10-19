package com.smartup24.cms.instance.studio.api;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Placeholder;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The export, the check and the application of solution packages, and the template gallery (ADR-0047). An import is
 * checked by applying it in a transaction that always rolls back, so its outcome per item is what the application would
 * do; the application repeats the same steps in one transaction that keeps everything or nothing.
 */
public final class StudioPackageImportDtos {
    private StudioPackageImportDtos() {}

    /**
     * What to put into a package: the chosen forms, datasets, reports, mailings and automations; the forms and lists
     * they use are added by the server.
     *
     * @param listItems the lists carry their items; true by default
     */
    @Schema(name = "SolutionExportRequest")
    public record ExportRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = StudioPackageDtos.CODE)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = StudioPackageDtos.VERSION)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @Nullable Labels description,

            @ArraySchema(maxItems = StudioPackageDtos.MAX_FORMS, schema = @Schema(example = "studio.orders")) @Nullable
            List<String> forms,

            @ArraySchema(maxItems = StudioPackageDtos.MAX_DATASETS) @Nullable
            List<String> datasets,

            @ArraySchema(maxItems = StudioPackageDtos.MAX_REPORTS) @Nullable
            List<Long> reports,

            @ArraySchema(maxItems = StudioPackageDtos.MAX_MAILINGS) @Nullable
            List<Long> mailings,

            @ArraySchema(maxItems = StudioPackageDtos.MAX_AUTOMATIONS) @Nullable
            List<Long> automations,

            @Nullable Boolean listItems) {}

    /**
     * An item of a package being made: {@code selected} by the administrator or added as a {@code dependency} of the
     * item {@code of}; {@code related} items use the selection and may be added.
     */
    @Schema(name = "SolutionExportItem")
    public record ExportItem(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"list", "form", "dataset", "report", "mailing", "automation"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A code or an id")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"selected", "dependency", "related"})
            String reason,

            @Schema(description = "The item that needs it, as kind:key") @Nullable
            String of) {}

    /** What a package would hold and what else uses its forms, before it is downloaded. */
    @Schema(name = "SolutionExportPlan")
    public record ExportPlan(
            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ExportItem> included,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ExportItem> related,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Placeholder> placeholders) {}

    /** A problem of an item: the text's catalog key, its values and the part of the item it is about. */
    @Schema(name = "SolutionItemProblem")
    public record Problem(
            @Nullable String field,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String message,

            @Nullable Map<String, Object> params) {}

    /**
     * What applying the package does to one item: {@code create}, {@code upgrade} (a new version of a form, items and
     * names of a list, a changed dataset, report, mailing or automation), {@code unchanged}, {@code skip} (an optional
     * placeholder is not mapped) or {@code refused} with the problems; notes are catalog keys of what the importer
     * should know (a paused automation, a channel this installation lacks).
     */
    @Schema(name = "SolutionImportItem")
    public record ImportItem(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"list", "form", "grant", "dataset", "report", "mailing", "automation"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A code, or the key in the package")
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"create", "upgrade", "unchanged", "skip", "refused"})
            String outcome,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Problem> problems,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> notes) {}

    @Schema(name = "SolutionImportCounts")
    public record Counts(int create, int upgrade, int unchanged, int skip, int refused) {}

    /**
     * An uploaded package or template being checked ({@code preview}) or applied ({@code applied}): the items with their
     * outcome at the last check, the placeholders to map and the mapping given.
     */
    @Schema(name = "SolutionImport")
    public record ImportView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @Nullable Labels description,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String checksum,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"upload", "template"})
            String source,

            @Nullable String suffix,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"preview", "applied"})
            String status,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String producedBy,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Placeholder> placeholders,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Placeholder key: role or user ids")
            Map<String, List<Long>> mapping,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ImportItem> items,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Counts counts,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long createdBy,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant checkedAt,

            @Nullable Instant appliedAt,
            @Nullable Long appliedBy) {}

    /** An import in the history, newest first. */
    @Schema(name = "SolutionImportSummary")
    public record ImportSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"upload", "template"})
            String source,

            @Nullable String suffix,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"preview", "applied"})
            String status,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Counts counts,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Nullable Instant appliedAt) {}

    /**
     * The mapping of the placeholders and whether only to check it: a role placeholder takes one role id, a users
     * placeholder active user ids; an optional placeholder may stay unmapped.
     */
    @Schema(name = "SolutionApplyRequest")
    public record ApplyRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
            Map<String, List<Long>> mapping,

            @Schema(description = "Check the mapping without applying; false by default") @Nullable
            Boolean dryRun) {}

    /** A template the product ships: a package to create a solution from. */
    @Schema(name = "SolutionTemplate")
    public record Template(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels description,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int forms,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int lists,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int reports,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int mailings,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int automations) {}
}
