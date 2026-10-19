package com.smartup24.cms.instance.studio.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Measure;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.report.api.ReportMailingDtos;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.FieldValue;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Trigger;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A solution package (ADR-0047): live forms with their active declarations, the reference lists they use, report
 * datasets, saved reports, mailings and automations over them, portable between installations. The roles and users of
 * the installation it came from are never named by id: they are placeholders the importer maps. The checksum is the
 * SHA-256 of the package's canonical JSON without the checksum itself; it proves integrity, not authorship.
 */
public final class StudioPackageDtos {
    private StudioPackageDtos() {}

    public static final int FORMAT = 1;
    public static final String KIND = "solution-package";
    /** The code of a package. */
    public static final String CODE = "^[a-z][a-z0-9-]{1,39}$";
    /** Three numbers: major, minor and patch. */
    public static final String VERSION = "^[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,6}$";
    /** The key of an item without a code of its own, or of a placeholder. */
    public static final String KEY = "^[a-z][a-z0-9_-]{0,39}$";
    /** What an import appends to every form, list and dataset code: a second copy under other codes. */
    public static final String SUFFIX = "^_[a-z0-9]{1,5}$";

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_FORMS = 20;
    public static final int MAX_LISTS = 50;
    public static final int MAX_DATASETS = 20;
    public static final int MAX_REPORTS = 50;
    public static final int MAX_MAILINGS = 20;
    public static final int MAX_AUTOMATIONS = 50;
    public static final int MAX_PLACEHOLDERS = 50;
    public static final int MAX_GRANTS = 20;

    /** What the importing installation must read: the newest simple-form format of the package's forms. */
    @Schema(name = "SolutionPackageRequires")
    public record Requires(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "7")
            int simpleFormFormat) {}

    /**
     * A role or a set of users of the installation the package came from, which the importer maps to this
     * installation's: {@code role} — one role, {@code users} — active users. A required placeholder must be mapped.
     */
    @Schema(name = "SolutionPackagePlaceholder")
    public record Placeholder(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = KEY)
            String key,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"role", "users"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels labels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean required) {}

    @Schema(name = "SolutionPackageListItem")
    public record ListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = StudioListDtos.ITEM_CODE)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9999")
            int sortOrder,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean active) {}

    /** A reference list; without its items when the package was made without them. */
    @Schema(name = "SolutionPackageList")
    public record PackageList(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = StudioListDtos.CODE)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels name,

            @ArraySchema(
                    maxItems = StudioListDtos.MAX_ITEMS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ListItem> items) {}

    /** A report dataset of an administrator, as its request names it (ADR-0044). */
    @Schema(name = "SolutionPackageDataset")
    public record PackageDataset(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String entity,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String lines,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> fields,

            @ArraySchema(
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(type = "object", description = "A condition of the list's filter DSL"))
            List<Map<String, Object>> filter,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Measure> measures) {}

    /**
     * A saved report on the list {@code list} (ADR-0036): its state as the report stores it and the role placeholders
     * it is shared with. The importer becomes its owner.
     */
    @Schema(name = "SolutionPackageReport")
    public record PackageReport(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = KEY)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String list,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "object")
            JsonNode state,

            @ArraySchema(
                    maxItems = 20,
                    arraySchema =
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Role placeholders"))
            List<String> share) {}

    /** A mailing of a report of the package (ADR-0045); its recipients are a placeholder of users. */
    @Schema(name = "SolutionPackageMailing")
    public record PackageMailing(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = KEY)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The key of the report")
            String report,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ReportMailingDtos.MAX_NAME)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A placeholder of users")
            String recipients,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 3,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"email", "telegram", "in_app"}))
            List<String> channels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean attachXlsx,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean includeLink,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            ReportMailingDtos.Schedule schedule,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> parameters,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            ReportMailingDtos.Condition condition) {}

    /** Who an automation's notification goes to; fixed users are a placeholder of users. */
    @Schema(name = "SolutionPackageRecipients")
    public record Recipients(
            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<String> fields,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Boolean author,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "A placeholder of users") @Nullable
            String users,

            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<Right> rights) {}

    /** An action of an automation (ADR-0046), as its request names it but for its recipients. */
    @Schema(name = "SolutionPackageAction")
    public record Action(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"set_field", "create_record", "transition", "notify"})
            String type,

            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<FieldValue> values,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String form,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String transition,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String comment,

            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<String> channels,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Recipients recipients,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Texts title,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Texts body) {}

    /** An automation of a form of the package; it is created paused and runs as whoever switches it on. */
    @Schema(name = "SolutionPackageAutomation")
    public record PackageAutomation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = KEY)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String form,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = StudioAutomationDtos.MAX_NAME)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Trigger trigger,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String condition,

            @ArraySchema(
                    minItems = 1,
                    maxItems = StudioAutomationDtos.MAX_ACTIONS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Action> actions,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String timeZone) {}

    /** A right of a form of the package. */
    @Schema(name = "SolutionPackagePermission")
    public record Permission(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String form,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String action) {}

    /**
     * Rights of the package's forms that a role placeholder held where the package came from; they are granted only to
     * the role the importer maps it to, and only by an importer who may grant rights.
     */
    @Schema(name = "SolutionPackageGrant")
    public record Grant(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A role placeholder")
            String role,

            @ArraySchema(
                    minItems = 1,
                    maxItems = 100,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Permission> permissions) {}

    @Schema(name = "SolutionPackage")
    public record SolutionPackage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "1")
            int formatVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = KIND)
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = CODE)
            String code,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = VERSION)
            String version,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Labels title,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Labels description,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Requires requires,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The version of the application")
            String producedBy,

            @ArraySchema(
                    maxItems = MAX_PLACEHOLDERS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Placeholder> placeholders,

            @ArraySchema(maxItems = MAX_LISTS, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<PackageList> lists,

            @ArraySchema(
                    maxItems = MAX_FORMS,
                    arraySchema =
                            @Schema(
                                    requiredMode = Schema.RequiredMode.REQUIRED,
                                    description = "Simple-form packages, a form after the forms it refers to"))
            List<DeliveryPackage> forms,

            @ArraySchema(maxItems = MAX_DATASETS, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<PackageDataset> datasets,

            @ArraySchema(maxItems = MAX_REPORTS, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<PackageReport> reports,

            @ArraySchema(maxItems = MAX_MAILINGS, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<PackageMailing> mailings,

            @ArraySchema(maxItems = MAX_AUTOMATIONS, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<PackageAutomation> automations,

            @ArraySchema(maxItems = MAX_GRANTS, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Grant> grants,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$")
            String checksum) {}
}
