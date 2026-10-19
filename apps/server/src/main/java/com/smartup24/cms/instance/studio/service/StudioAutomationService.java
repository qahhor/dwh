package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.RequestZone;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.kauth.service.KauthDeliveryAddresses;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.AutomationRequest;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Options;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.Run;
import com.smartup24.cms.instance.studio.api.StudioAutomationRunDtos.RunNow;
import com.smartup24.cms.instance.studio.automation.AutomationChecks;
import com.smartup24.cms.instance.studio.automation.AutomationEvents;
import com.smartup24.cms.instance.studio.automation.AutomationJson;
import com.smartup24.cms.instance.studio.automation.AutomationScans;
import com.smartup24.cms.instance.studio.automation.AutomationSpec;
import com.smartup24.cms.instance.studio.automation.AutomationViews;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.Values;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automations of live forms (ADR-0046): kept by holders of {@code studio.automations.manage}, each checked against its
 * forms and against the rights of the person who saves it — who becomes its author, the person it runs as. Every change
 * names the revision it was made from and is audited; the run log is paged; a scheduled automation may be run now.
 */
@Service
public class StudioAutomationService {
    public static final int MAX_PER_FORM = 20;
    public static final int MAX_TOTAL = 500;
    public static final int MAX_MANUAL_PER_HOUR = 10;
    private static final String TABLE = "studio_automations";
    private static final List<String> AUDITED =
            List.of("name", "trigger", "condition", "actions", "time_zone", "active", "author");
    private static final List<String> CHANNELS = List.of("in_app", "email", "telegram");

    private final StudioAutomationRepository automations;
    private final StudioAutomationRunRepository runs;
    private final AutomationChecks checks;
    private final AutomationJson json;
    private final AutomationViews views;
    private final AutomationScans scans;
    private final StudioAutomationRights rights;
    private final AuditLogService audit;
    private final MdPermissionService permissions;
    private final KauthDeliveryAddresses addresses;
    private final Clock clock;

    public StudioAutomationService(
            StudioAutomationRepository automations,
            StudioAutomationRunRepository runs,
            AutomationChecks checks,
            AutomationJson json,
            AutomationViews views,
            AutomationScans scans,
            StudioAutomationRights rights,
            AuditLogService audit,
            MdPermissionService permissions,
            KauthDeliveryAddresses addresses,
            ObjectProvider<Clock> clock) {
        this.automations = automations;
        this.runs = runs;
        this.checks = checks;
        this.json = json;
        this.views = views;
        this.scans = scans;
        this.rights = rights;
        this.audit = audit;
        this.permissions = permissions;
        this.addresses = addresses;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public Options options() {
        return new Options(
                channels(),
                MAX_PER_FORM,
                com.smartup24.cms.instance.studio.api.StudioAutomationDtos.MAX_ACTIONS,
                AutomationEvents.MAX_DEPTH,
                AutomationEvents.MAX_PER_RECORD_HOUR);
    }

    @Transactional(readOnly = true)
    public KeysetPage<Automation> list(@Nullable String form, @Nullable Integer limit, @Nullable String cursor) {
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        var rows = page.page(automations.page(form, page), row -> new TimePage.Position(row.createdAt(), row.id()));
        var latest = runs.latest(rows.items().stream().map(AutomationRow::id).toList());
        return rows.map(row -> views.automation(row, latest.get(row.id())));
    }

    @Transactional(readOnly = true)
    public Automation get(long id) {
        AutomationRow row = row(id);
        return views.automation(row, runs.latest(List.of(id)).get(id));
    }

    @Transactional
    public Automation create(AutomationRequest request) {
        String form = request.form();
        if (form == null || form.isBlank()) {
            throw ApiException.validation(
                    "error.studio.automation_invalid",
                    List.of(FieldErrorItem.keyed("form", "automation_invalid", "error.studio.automation_form")));
        }
        AutomationRequest zoned = zoned(request);
        AutomationSpec spec = checks.check(form, zoned, context());
        rights.requireAuthor(spec);
        automations.lock();
        if (automations.countFor(spec.form()) >= MAX_PER_FORM || automations.count() >= MAX_TOTAL) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.studio.automation_quota",
                    Map.of("max", MAX_TOTAL, "perForm", MAX_PER_FORM));
        }
        Values values = values(zoned, spec);
        long id = automations.insert(spec.form(), values, SecurityContext.getCurrentUserId());
        audit.logChange(TABLE, Long.toString(id), "I", AUDITED, Map.of(), audited(values, currentUser()));
        return get(id);
    }

    @Transactional
    public Automation update(long id, @Nullable String ifMatch, AutomationRequest request) {
        long revision = Revisions.required(ifMatch);
        AutomationRow before = row(id);
        AutomationRequest zoned = zoned(request);
        AutomationSpec spec = checks.check(before.form(), zoned, context());
        rights.requireAuthor(spec);
        Values values = values(zoned, spec);
        if (!automations.update(id, revision, values, currentUser())) throw Revisions.conflict();
        audit.logChange(
                TABLE,
                Long.toString(id),
                "U",
                AUDITED,
                audited(stored(before), before.authorId()),
                audited(values, currentUser()));
        return get(id);
    }

    @Transactional
    public void delete(long id, @Nullable String ifMatch) {
        long revision = Revisions.required(ifMatch);
        AutomationRow before = row(id);
        if (!automations.delete(id, revision)) throw Revisions.conflict();
        audit.logChange(TABLE, Long.toString(id), "D", AUDITED, audited(stored(before), before.authorId()), Map.of());
    }

    @Transactional(readOnly = true)
    public KeysetPage<Run> runs(long id, @Nullable Integer limit, @Nullable String cursor) {
        row(id);
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        return page.page(runs.page(id, page), run -> new TimePage.Position(run.createdAt(), run.id()))
                .map(views::run);
    }

    /**
     * Looks at the records of a scheduled automation now, as its author, and queues a run for each that meets its
     * condition; at most {@value #MAX_MANUAL_PER_HOUR} an hour.
     */
    public RunNow runNow(long id) {
        AutomationRow row = row(id);
        if (!AutomationSpec.SCHEDULED.equals(row.triggerKind())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "error.studio.automation_not_scheduled");
        }
        if (!row.active()) throw ApiException.conflict(ErrorCode.CONFLICT, "error.studio.automation_paused");
        if (runs.countManualSince(id, clock.instant().minus(Duration.ofHours(1))) >= MAX_MANUAL_PER_HOUR) {
            throw ApiException.rateLimited("error.studio.automation_rate_limited");
        }
        return scans.scan(id, "manual", UUID.randomUUID().toString());
    }

    private AutomationRow row(long id) {
        return automations
                .find(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.automation_not_found"));
    }

    /** The request with the time zone of the request when it names none. */
    private static AutomationRequest zoned(AutomationRequest request) {
        if (request.timeZone() != null && !request.timeZone().isBlank()) return request;
        return new AutomationRequest(
                request.form(),
                request.name(),
                request.active(),
                request.trigger(),
                request.condition(),
                request.actions(),
                zoneName(RequestZone.current()));
    }

    /** A region of the zone database names the zone; a fixed offset (the default UTC) is named UTC. */
    static String zoneName(java.time.ZoneId zone) {
        return zone instanceof java.time.ZoneOffset ? "UTC" : zone.getId();
    }

    private AutomationChecks.Context context() {
        return new AutomationChecks.Context(java.util.Set.copyOf(channels()), permissions.getGrantablePairs(), true);
    }

    private List<String> channels() {
        return CHANNELS.stream()
                .filter(channel -> "in_app".equals(channel) || addresses.deliverable(channel))
                .toList();
    }

    private Values values(AutomationRequest request, AutomationSpec spec) {
        boolean active = request.active() == null || request.active();
        Instant next = null;
        if (active && spec.trigger().schedule() != null) {
            next = java.util.Objects.requireNonNull(spec.trigger().schedule()).nextAfter(clock.instant());
        }
        String condition = request.condition() == null || request.condition().isBlank() ? null : request.condition();
        return new Values(
                request.name().strip(),
                spec.trigger().kind(),
                json.write(request.trigger()),
                condition,
                json.write(request.actions()),
                spec.zone().getId(),
                active,
                next);
    }

    private static Values stored(AutomationRow row) {
        return new Values(
                row.name(),
                row.triggerKind(),
                row.trigger(),
                row.condition(),
                row.actions(),
                row.timeZone(),
                row.active(),
                row.nextRunAt());
    }

    private static Map<String, Object> audited(Values values, long author) {
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("name", values.name());
        audited.put("trigger", values.trigger());
        audited.put("condition", values.condition() == null ? "" : values.condition());
        audited.put("actions", values.actions());
        audited.put("time_zone", values.timeZone());
        audited.put("active", values.active());
        audited.put("author", author);
        return audited;
    }

    private static long currentUser() {
        return SecurityContext.getCurrentUserId();
    }
}
