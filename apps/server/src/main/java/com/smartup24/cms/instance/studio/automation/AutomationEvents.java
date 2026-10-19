package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.entity.EntityChangeImages;
import com.smartup24.cms.instance.common.entity.EntityChangeOrigin;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Trigger;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository.NewRun;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Queues the runs a change of a live form's record triggers (ADR-0046), in the transaction of the change: the run rows
 * and the job that takes them commit with the change or not at all, and a run is unique per automation and event, so
 * the same event never runs an automation twice. Nothing runs here: the runs execute after the commit.
 *
 * <p>Loops are cut where they start: an automation is never triggered by its own changes; a chain of automations stops
 * after {@value #MAX_DEPTH} runs; one automation runs at most {@value #MAX_PER_RECORD_HOUR} times an hour for one record,
 * and all automations together at most {@code smc.automations.max-runs-per-minute} times a minute. What a limit holds
 * back is counted ({@code smc_automation_events_withheld_total}) and logged, never queued.
 */
@Component
public class AutomationEvents {
    public static final int MAX_DEPTH = 3;
    public static final int MAX_PER_RECORD_HOUR = 20;

    private static final Logger log = LoggerFactory.getLogger(AutomationEvents.class);
    private static final String PREFIX = "studio.";

    private final StudioAutomationRepository automations;
    private final StudioAutomationRunRepository runs;
    private final JobQueue jobs;
    private final AutomationJson json;
    private final AutomationMetrics metrics;
    private final Clock clock;
    private final int maxPerMinute;

    public AutomationEvents(
            StudioAutomationRepository automations,
            StudioAutomationRunRepository runs,
            @Lazy JobQueue jobs,
            AutomationJson json,
            AutomationMetrics metrics,
            ObjectProvider<Clock> clock,
            @Value("${smc.automations.max-runs-per-minute:600}") int maxPerMinute) {
        this.automations = automations;
        this.runs = runs;
        this.jobs = jobs;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.maxPerMinute = maxPerMinute;
    }

    @EventListener
    public void changed(EntityChangeImages images) {
        EntityChanged change = images.change();
        if (!change.entity().startsWith(PREFIX)) return;
        Set<String> kinds = kinds(change, images);
        if (kinds.isEmpty()) return;
        List<AutomationRow> candidates = automations.active(change.entity(), kinds);
        if (candidates.isEmpty()) return;
        Optional<EntityChangeOrigin.Origin> origin = EntityChangeOrigin.current();
        int depth = origin.map(from -> from.depth() + 1).orElse(1);
        Instant now = clock.instant();
        boolean queued = false;
        for (AutomationRow automation : candidates) {
            if (origin.isPresent() && origin.get().automationId() == automation.id()) {
                withhold("self", automation, change);
            } else if (matches(automation, change, images)) {
                queued |= queue(automation, change, images, depth, now);
            }
        }
        if (queued) {
            jobs.enqueueOnce(AutomationRunner.JOB, Map.of("form", change.entity(), "recordId", change.id()));
        }
    }

    private boolean queue(
            AutomationRow automation, EntityChanged change, EntityChangeImages images, int depth, Instant now) {
        if (depth > MAX_DEPTH) {
            withhold("depth", automation, change);
            return false;
        }
        if (runs.countForRecord(automation.id(), change.id(), now.minus(Duration.ofHours(1))) >= MAX_PER_RECORD_HOUR) {
            withhold("record_rate", automation, change);
            return false;
        }
        if (runs.countSince(now.minus(Duration.ofMinutes(1))) >= maxPerMinute) {
            withhold("installation_rate", automation, change);
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", change.type().name());
        if (change.action() != null) payload.put("action", change.action());
        payload.put("changed", change.changedFields());
        payload.put("actor", change.actorId());
        payload.put("at", change.occurredAt().toString());
        if (images.before() != null) payload.put("old", scalars(Objects.requireNonNull(images.before())));
        return runs.insert(new NewRun(
                automation.id(),
                change.eventId().toString(),
                "event",
                automation.triggerKind(),
                change.entity(),
                change.id(),
                depth,
                json.write(payload)));
    }

    /** The trigger kinds a change may start: a create or an update is also a save; a transition moves the status. */
    static Set<String> kinds(EntityChanged change, EntityChangeImages images) {
        return switch (change.type()) {
            case CREATED -> Set.of(AutomationSpec.CREATED, AutomationSpec.SAVED);
            case UPDATED -> Set.of(AutomationSpec.UPDATED, AutomationSpec.SAVED);
            case DELETED -> Set.of(AutomationSpec.DELETED);
            case ACTION -> moved(images) ? Set.of(AutomationSpec.TRANSITION) : Set.of();
            default -> Set.of();
        };
    }

    private boolean matches(AutomationRow automation, EntityChanged change, EntityChangeImages images) {
        Trigger trigger;
        try {
            trigger = json.trigger(automation.trigger());
        } catch (RuntimeException unreadable) {
            log.warn("studio_automation_trigger_unreadable automation={}", automation.id(), unreadable);
            return false;
        }
        String kind = automation.triggerKind();
        if (AutomationSpec.UPDATED.equals(kind) || AutomationSpec.SAVED.equals(kind)) {
            List<String> fields = trigger.fields();
            return fields == null || fields.isEmpty() || fields.stream().anyMatch(change.changedFields()::contains);
        }
        if (AutomationSpec.TRANSITION.equals(kind)) {
            return within(trigger.from(), status(images.before())) && within(trigger.to(), status(images.after()));
        }
        return true;
    }

    private static boolean within(@Nullable List<String> allowed, @Nullable Object status) {
        return allowed == null || allowed.isEmpty() || (status != null && allowed.contains(String.valueOf(status)));
    }

    private static boolean moved(EntityChangeImages images) {
        Object before = status(images.before());
        Object after = status(images.after());
        return after != null && !Objects.equals(before, after);
    }

    private static @Nullable Object status(@Nullable Map<String, Object> record) {
        return record == null ? null : record.get(MdSimpleFormDocumentLogic.STATUS);
    }

    /** The values of a record a run reads as {@code old.*}: its fields, without its rows and custom fields. */
    static Map<String, Object> scalars(Map<String, Object> record) {
        Map<String, Object> values = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (value != null && !(value instanceof List<?>) && !(value instanceof Map<?, ?>)) {
                values.put(key, plain(value));
            }
        });
        return values;
    }

    /** A value as JSON keeps it without loss: a flag or a text as it is, a moment as an instant, others as text. */
    static Object plain(Object value) {
        return switch (value) {
            case Boolean flag -> flag;
            case String text -> text;
            case java.sql.Timestamp timestamp -> timestamp.toInstant().toString();
            case java.time.OffsetDateTime offset -> offset.toInstant().toString();
            default -> value.toString();
        };
    }

    private void withhold(String reason, AutomationRow automation, EntityChanged change) {
        metrics.withheld(reason);
        log.info(
                "studio_automation_withheld reason={} automation={} form={} record={}",
                reason,
                automation.id(),
                change.entity(),
                change.id());
    }
}
