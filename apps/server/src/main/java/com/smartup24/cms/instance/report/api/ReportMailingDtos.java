package com.smartup24.cms.instance.report.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Scheduled mailings of saved reports (ADR-0045): who gets a report, on which channels, in which format, when and on
 * which condition. Each run executes the report as each recipient.
 */
public final class ReportMailingDtos {
    public static final int MAX_RECIPIENTS = 50;
    public static final int MAX_NAME = 120;

    private ReportMailingDtos() {}

    /**
     * When a mailing goes out: every day, on a weekday ({@code 1} Monday … {@code 7} Sunday) or on a day of the month
     * (a day past the month's end means its last day), at a local time ({@code HH:mm}) of a time zone of the zone
     * database.
     */
    @Schema(name = "ReportMailingSchedule")
    public record Schedule(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"daily", "weekly", "monthly"})
            String frequency,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "([01][0-9]|2[0-3]):[0-5][0-9]")
            String time,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Asia/Tashkent")
            String timeZone,

            @Schema(minimum = "1", maximum = "7", description = "Weekly: 1 Monday … 7 Sunday") @Nullable
            Integer weekday,

            @Schema(minimum = "1", maximum = "31", description = "Monthly: the day; past the month's end, its last day")
            @Nullable
            Integer monthDay) {}

    /**
     * When a run sends to a recipient: always, only when the recipient's report has rows, or when a grand total of a
     * measure — the measure's position in the saved report — compares to a value.
     */
    @Schema(name = "ReportMailingCondition")
    public record Condition(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"always", "has_rows", "threshold"})
            String kind,

            @Schema(minimum = "0", maximum = "3", description = "Threshold: the measure's position in the report")
            @Nullable
            Integer measure,

            @Schema(allowableValues = {"gt", "gte", "lt", "lte", "eq"}) @Nullable
            String op,

            @Nullable BigDecimal value) {}

    /**
     * A mailing to create or change. The saved report is named on creation only.
     *
     * @param viewId       the saved report
     * @param recipientIds the active users it goes to
     * @param channels     {@code email}, {@code telegram}, {@code in_app}: only channels configured here
     * @param attachXlsx   letters carry the report as an XLSX file
     * @param includeLink  messages carry a link that opens the report in the application
     * @param parameters   values of the report's parameters, as a reader passes them; a period may be a preset
     *                     ({@code {"preset": "last_month"}}) counted when the mailing runs
     */
    @Schema(name = "ReportMailingRequest")
    public record MailingRequest(
            @Nullable Long viewId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = MAX_NAME)
            String name,

            @ArraySchema(
                    minItems = 1,
                    maxItems = MAX_RECIPIENTS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Long> recipientIds,

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

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Valid @NotNull
            Schedule schedule,

            @Schema(description = "Values of the report's parameters") @Nullable
            Map<String, Object> parameters,

            @Valid @Nullable Condition condition,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean active) {}

    /** A recipient as the mailing names it; {@code state} {@code A} is active. */
    @Schema(name = "ReportMailingRecipient")
    public record Recipient(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String login,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String state) {}

    /** The outcome of the latest run, for the list. */
    @Schema(name = "ReportMailingLastRun")
    public record LastRun(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"queued", "running", "done", "partial", "failed", "skipped"})
            String status,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt) {}

    @Schema(name = "ReportMailing")
    public record Mailing(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long viewId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String entity,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Recipient> recipients,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> channels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean attachXlsx,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean includeLink,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Schedule schedule,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> parameters,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Condition condition,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean active,

            @Schema(description = "The next scheduled send; none while paused") @Nullable
            Instant nextRunAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long ownerId,

            @Nullable LastRun lastRun,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant modifiedAt) {}

    /** Channels this installation delivers mailings on, and the bounds of a mailing. */
    @Schema(name = "ReportMailingOptions")
    public record Options(
            @ArraySchema(
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"email", "telegram", "in_app"}))
            List<String> channels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int maxRecipients,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long maxAttachmentBytes,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canManageAll) {}

    /** A send now: to every recipient ({@code all}) or a test to the person who asks ({@code me}). */
    @Schema(name = "ReportMailingRunRequest")
    public record RunRequest(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"all", "me"})
            String scope) {}

    @Schema(name = "ReportMailingRun")
    public record Run(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"scheduled", "manual", "test"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant slot,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"queued", "running", "done", "partial", "failed", "skipped"})
            String status,

            @Nullable Long requestedBy,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int recipients,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int sent,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int skipped,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int failed,

            @Schema(description = "Why the whole run failed or was skipped") @Nullable
            String error,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Nullable Instant finishedAt) {}

    /**
     * What one recipient got on one channel. {@code outbox} is the state of a queued letter or message in the outbox:
     * {@code PENDING}, {@code PROCESSING}, {@code SENT} or {@code DEAD_LETTER}.
     */
    @Schema(name = "ReportMailingDelivery")
    public record Delivery(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long userId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String userName,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"email", "telegram", "in_app"})
            String channel,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"sent", "queued", "skipped", "failed"})
            String status,

            @Schema(
                    description = "inactive, no_access, no_address, channel_unavailable, opted_out, condition,"
                            + " condition_ignored, parameters, report_failed, attachment_too_large")
            @Nullable
            String reason,

            @Nullable String outbox,

            @Nullable Integer rows) {}

    /** A run with what each recipient got. */
    @Schema(name = "ReportMailingRunDetail")
    public record RunDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Run run,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Delivery> deliveries) {}
}
