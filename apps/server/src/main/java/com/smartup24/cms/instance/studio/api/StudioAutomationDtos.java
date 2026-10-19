package com.smartup24.cms.instance.studio.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Automations of live forms (ADR-0046): a trigger on the records of one live form, an optional condition formula and
 * ordered actions, run with the rights of the person who last saved or switched the automation on; the log of its runs
 * and a dry run over a chosen record. The request is checked against the active version of the form, each problem at
 * its part.
 */
public final class StudioAutomationDtos {
    public static final int MAX_NAME = 120;
    public static final int MAX_ACTIONS = 10;

    private StudioAutomationDtos() {}

    /**
     * When a scheduled automation looks at the records: every day, on a weekday ({@code 1} Monday … {@code 7} Sunday)
     * or on a day of the month (past the month's end, its last day), at a local time ({@code HH:mm}) of the
     * automation's time zone.
     */
    @Schema(name = "StudioAutomationSchedule")
    public record Schedule(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"daily", "weekly", "monthly"})
            String frequency,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "([01][0-9]|2[0-3]):[0-5][0-9]")
            String time,

            @Schema(minimum = "1", maximum = "7") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Integer weekday,

            @Schema(minimum = "1", maximum = "31") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Integer monthDay) {}

    /**
     * What starts a run: a record {@code created}, {@code updated}, {@code saved} (either), moved by a
     * {@code transition} (from and to states, each optional), {@code deleted}, or a {@code scheduled} look at every
     * record that meets the condition.
     *
     * @param fields updated or saved: only when one of these fields changed
     */
    @Schema(name = "StudioAutomationTrigger")
    public record Trigger(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"created", "updated", "saved", "transition", "deleted", "scheduled"})
            String kind,

            @ArraySchema(maxItems = 20) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<String> fields,

            @ArraySchema(maxItems = 20) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<String> from,

            @ArraySchema(maxItems = 20) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<String> to,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Schedule schedule) {}

    /** A field of the record an action writes and the formula of its value over the triggering record. */
    @Schema(name = "StudioAutomationValue")
    public record FieldValue(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String field,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000)
            String formula) {}

    /** A text in each language; {@code {field}} placeholders take the values of the record. */
    @Schema(name = "StudioAutomationTexts")
    public record Texts(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000)
            String ru,

            @Schema(maxLength = 2000) @Nullable String uz,
            @Schema(maxLength = 2000) @Nullable String en) {}

    /** The holders of a right of the permission catalog. */
    @Schema(name = "StudioAutomationRight")
    public record Right(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String form,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String action) {}

    /**
     * Who a notification goes to: the users of user fields of the record, the record's author, fixed users and the
     * holders of rights; each must be active and may see the record.
     */
    @Schema(name = "StudioAutomationRecipients")
    public record Recipients(
            @ArraySchema(maxItems = 10) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<String> fields,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Boolean author,

            @ArraySchema(maxItems = 50) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<Long> users,

            @ArraySchema(maxItems = 5) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<Right> rights) {}

    /**
     * One action: {@code set_field} writes {@code values} on the record; {@code create_record} creates a record of the
     * live form {@code form} with {@code values}; {@code transition} runs the transition {@code transition} with an
     * optional {@code comment}; {@code notify} sends {@code title} and {@code body} on {@code channels} to
     * {@code recipients}.
     */
    @Schema(name = "StudioAutomationAction")
    public record Action(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"set_field", "create_record", "transition", "notify"})
            String type,

            @ArraySchema(maxItems = 30) @JsonInclude(JsonInclude.Include.NON_EMPTY) @Valid @Nullable
            List<FieldValue> values,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String form,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String transition,

            @Schema(maxLength = 1000) @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String comment,

            @ArraySchema(schema = @Schema(allowableValues = {"in_app", "email", "telegram"}))
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @Nullable
            List<String> channels,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid @Nullable
            Recipients recipients,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid @Nullable
            Texts title,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid @Nullable
            Texts body) {}

    /**
     * An automation to create or change; its live form is named on creation only.
     *
     * @param timeZone the time zone of its schedule and of {@code today()} in its formulas; the request's by default
     */
    @Schema(name = "StudioAutomationRequest")
    public record AutomationRequest(
            @Schema(description = "The live form, on creation only", example = "studio.orders") @Nullable
            String form,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = MAX_NAME)
            String name,

            @Schema(description = "Switched on; true by default") @Nullable
            Boolean active,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
            Trigger trigger,

            @Schema(maxLength = 1000, description = "A yes/no formula over the record") @Nullable
            String condition,

            @ArraySchema(
                    minItems = 1,
                    maxItems = MAX_ACTIONS,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            @NotNull
            @Valid
            List<Action> actions,

            @Schema(example = "Asia/Tashkent") @Nullable String timeZone) {}

    /** A user as an automation names it. */
    @Schema(name = "StudioAutomationUser")
    public record UserRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name) {}

    /** The last run of an automation. */
    @Schema(name = "StudioAutomationRunSummary")
    public record RunSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"queued", "done", "skipped", "failed"})
            String status,

            @Nullable String reason,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Nullable Instant finishedAt) {}

    /**
     * An automation as stored.
     *
     * @param formArchived the form is archived: the automation waits until it is restored
     * @param author       whose rights the automation runs with: who last saved or switched it on
     */
    @Schema(name = "StudioAutomation")
    public record Automation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String form,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean active,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean formArchived,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Trigger trigger,

            @Nullable String condition,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<Action> actions,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String timeZone,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UserRef author,

            @Nullable Instant nextRunAt,
            @Nullable RunSummary lastRun,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long revision,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant modifiedAt) {}

    /** A problem of a field of a record an action wrote, with the dictionary key of its text. */
    @Schema(name = "StudioAutomationFieldProblem")
    public record FieldProblem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String field,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> params) {}

    /** Why a run or an action failed: the dictionary key of the text, its values and the problems of fields. */
    @Schema(name = "StudioAutomationProblem")
    public record Problem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String key,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> params,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<FieldProblem> fields) {}

    /** A recipient of a notification and the channels it goes on, or why the person was left out. */
    @Schema(name = "StudioAutomationRecipient")
    public record Recipient(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> channels,

            @Nullable String skipped) {}

    /** A notification as its first recipient reads it, or in the author's language when none. */
    @Schema(name = "StudioAutomationMessage")
    public record Message(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String title,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String body) {}

    /**
     * What one action did — or, in a dry run, would do: the values it writes, the record it creates or changes, the
     * recipients and the text of a notification, or its problem.
     */
    @Schema(name = "StudioAutomationActionResult")
    public record ActionResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int index,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String type,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"done", "would_apply", "skipped", "failed"})
            String status,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String reason,

            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            Map<String, Object> values,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String form,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Long recordId,

            @JsonInclude(JsonInclude.Include.NON_EMPTY) @Nullable
            List<Recipient> recipients,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Message message,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Problem error) {}

    /** One run of an automation for one record. */
    @Schema(name = "StudioAutomationRun")
    public record Run(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long id,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"event", "scheduled", "manual"})
            String kind,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String trigger,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long recordId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int depth,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"queued", "done", "skipped", "failed"})
            String status,

            @Nullable String reason,
            @Nullable Problem error,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ActionResult> results,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int attempts,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant createdAt,

            @Nullable Instant finishedAt) {}

    /** A dry run: the automation as the editor holds it, over one record of its form. */
    @Schema(name = "StudioAutomationDryRunRequest")
    public record DryRunRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long recordId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid
            AutomationRequest automation) {}

    /**
     * What a run over the record would do now, nothing applied: whether the condition holds and each action's
     * outcome. The trigger is taken as met; {@code old.*} values are the record's own, nothing counts as changed.
     */
    @Schema(name = "StudioAutomationDryRun")
    public record DryRun(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean conditionHolds,

            @Schema(description = "Why the run would stop: condition, form_archived, author_access, action_failed")
            @Nullable
            String reason,

            @Nullable Problem error,

            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<ActionResult> actions) {}

    /** The runs a run now of a scheduled automation queued: one per record meeting the condition. */
    @Schema(name = "StudioAutomationRunNow")
    public record RunNow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int queued,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean truncated) {}

    /** A formula of an automation to check while the editor types it. */
    @Schema(name = "StudioAutomationCheckRequest")
    public record CheckRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String form,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"created", "updated", "saved", "transition", "deleted", "scheduled"})
            String trigger,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000)
            String formula,

            @Schema(description = "The live form of the field a value is computed for; the automation's by default")
            @Nullable
            String targetForm,

            @Schema(description = "The field a value is computed for; without it the formula is a condition") @Nullable
            String targetField) {}

    /** What the editor may offer: the channels configured here and the limits. */
    @Schema(name = "StudioAutomationOptions")
    public record Options(
            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> channels,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int maxPerForm,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int maxActions,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int maxDepth,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int maxRunsPerRecordHour) {}
}
