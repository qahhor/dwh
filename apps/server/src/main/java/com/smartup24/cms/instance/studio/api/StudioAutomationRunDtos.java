package com.smartup24.cms.instance.studio.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The runs of automations of live forms (ADR-0046): what a run or a dry run did — each action's outcome, the values it
 * wrote, the record it created, who was told and what — and the problems that stopped it.
 */
public final class StudioAutomationRunDtos {
    private StudioAutomationRunDtos() {}

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
}
