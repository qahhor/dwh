package com.smartup24.cms.instance.report.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.report.api.ReportMailingDtos;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Delivery;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.MailingRequest;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Options;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Run;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.RunDetail;
import com.smartup24.cms.instance.report.mailing.MailingAccess;
import com.smartup24.cms.instance.report.mailing.MailingRequests;
import com.smartup24.cms.instance.report.mailing.MailingRunner;
import com.smartup24.cms.instance.report.mailing.MailingViews;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.MailingRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.Values;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository.DeliveryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled mailings of saved reports (ADR-0045): kept by whoever keeps the report's mailings ({@link MailingAccess}),
 * every change named by its revision ({@code If-Match}) and audited. A send now goes to every recipient of an active
 * mailing; a test send goes to the person who asks, paused or not; both are bounded per hour. A run executes the report
 * as each recipient in the background ({@link MailingRunner}).
 */
@Service
public class ReportMailingService {
    public static final int MAX_PER_REPORT = 10;
    public static final int MAX_MAILINGS = 1000;
    public static final int MAX_REQUESTED_PER_HOUR = 10;
    private static final String TABLE = "report_mailings";
    private static final List<String> AUDITED = List.of(
            "name",
            "channels",
            "attach_xlsx",
            "include_link",
            "schedule",
            "parameters",
            "condition",
            "active",
            "recipients");

    private final ReportMailingRepository repo;
    private final ReportMailingRunRepository runs;
    private final MailingAccess access;
    private final MailingRequests requests;
    private final MailingViews views;
    private final MsNotificationService notifications;
    private final AuditLogService audit;
    private final JobQueue jobs;
    private final Clock clock;
    private final long maxAttachmentBytes;

    public ReportMailingService(
            ReportMailingRepository repo,
            ReportMailingRunRepository runs,
            MailingAccess access,
            MailingRequests requests,
            MailingViews views,
            MsNotificationService notifications,
            AuditLogService audit,
            @Lazy JobQueue jobs,
            ObjectProvider<Clock> clock,
            @Value("${smc.report-mailings.max-attachment-bytes:5242880}") long maxAttachmentBytes) {
        this.repo = repo;
        this.runs = runs;
        this.access = access;
        this.requests = requests;
        this.views = views;
        this.notifications = notifications;
        this.audit = audit;
        this.jobs = jobs;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    public Options options() {
        return new Options(
                requests.channels(), ReportMailingDtos.MAX_RECIPIENTS, maxAttachmentBytes, MailingAccess.managesAll());
    }

    @Transactional(readOnly = true)
    public KeysetPage<Mailing> list(long viewId, @Nullable Integer limit, @Nullable String cursor) {
        access.report(viewId);
        TimePage page = TimePage.of(limit, cursor, 20, 50);
        var rows = page.page(repo.page(viewId, page), row -> new TimePage.Position(row.createdAt(), row.id()));
        var ids = rows.items().stream().map(MailingRow::id).toList();
        var recipients = repo.recipients(ids);
        var latest = runs.latest(ids);
        return rows.map(row -> views.mailing(row, recipients.getOrDefault(row.id(), List.of()), latest.get(row.id())));
    }

    @Transactional(readOnly = true)
    public Mailing get(long id) {
        return view(row(id));
    }

    @Transactional
    public Mailing create(MailingRequest request) {
        if (request.viewId() == null) {
            throw ApiException.validation(
                    "error.report.mailing_invalid",
                    List.of(FieldErrorItem.keyed("viewId", "REPORT_MAILING_INVALID", "error.report.mailing_report")));
        }
        ReportViewItem report = access.report(request.viewId());
        var checked = requests.check(request, report, null);
        repo.lock();
        if (repo.countFor(report.id()) >= MAX_PER_REPORT || repo.count() >= MAX_MAILINGS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "error.report.mailing_quota",
                    Map.of("max", MAX_MAILINGS, "perReport", MAX_PER_REPORT));
        }
        long user = MailingAccess.currentUser();
        long id = repo.insert(report.entity(), report.id(), checked.values(), user);
        repo.replaceRecipients(id, checked.recipients());
        audit.logChange(
                TABLE, Long.toString(id), "I", AUDITED, Map.of(), audited(checked.values(), checked.recipients()));
        return get(id);
    }

    @Transactional
    public Mailing update(long id, @Nullable String ifMatch, MailingRequest request) {
        long revision = Revisions.required(ifMatch);
        MailingRow before = row(id);
        ReportViewItem report = access.report(before.viewId());
        var checked = requests.check(request, report, before);
        List<Long> previous = recipientIds(before.id());
        if (!repo.update(id, revision, checked.values(), MailingAccess.currentUser())) throw Revisions.conflict();
        repo.replaceRecipients(id, checked.recipients());
        audit.logChange(
                TABLE,
                Long.toString(id),
                "U",
                AUDITED,
                audited(values(before), previous),
                audited(checked.values(), checked.recipients()));
        return get(id);
    }

    @Transactional
    public void delete(long id, @Nullable String ifMatch) {
        long revision = Revisions.required(ifMatch);
        MailingRow before = row(id);
        access.report(before.viewId());
        List<Long> previous = recipientIds(id);
        if (!repo.delete(id, revision)) throw Revisions.conflict();
        audit.logChange(TABLE, Long.toString(id), "D", AUDITED, audited(values(before), previous), Map.of());
    }

    /**
     * A send now to every recipient ({@code all}, an active mailing only) or a test send to the person who asks
     * ({@code me}); at most {@value #MAX_REQUESTED_PER_HOUR} per mailing an hour.
     */
    @Transactional
    public Run request(long id, String scope) {
        MailingRow mailing = row(id);
        boolean test = "me".equals(scope);
        if (!test && !"all".equals(scope)) {
            throw ApiException.validation(
                    "error.report.mailing_invalid",
                    List.of(FieldErrorItem.keyed("scope", "REPORT_MAILING_INVALID", "error.report.mailing_scope")));
        }
        if (!test && !mailing.active()) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "error.report.mailing_paused");
        }
        Instant now = clock.instant();
        if (runs.countRequestedSince(id, now.minus(Duration.ofHours(1))) >= MAX_REQUESTED_PER_HOUR) {
            throw ApiException.rateLimited("error.report.mailing_rate_limited");
        }
        long runId = runs.insertRequested(id, test ? "test" : "manual", now, MailingAccess.currentUser());
        jobs.enqueueOnce(MailingRunner.JOB, Map.of("runId", runId));
        return MailingViews.run(runs.find(id, runId).orElseThrow(MailingAccess::mailingNotFound));
    }

    @Transactional(readOnly = true)
    public KeysetPage<Run> runs(long id, @Nullable Integer limit, @Nullable String cursor) {
        row(id);
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        return page.page(runs.page(id, page), run -> new TimePage.Position(run.createdAt(), run.id()))
                .map(MailingViews::run);
    }

    @Transactional(readOnly = true)
    public RunDetail run(long id, long runId) {
        row(id);
        var run = runs.find(id, runId).orElseThrow(MailingAccess::mailingNotFound);
        List<DeliveryRow> deliveries = runs.deliveries(runId);
        Map<UUID, String> outbox = notifications.deliveryStates(deliveries.stream()
                .map(DeliveryRow::outboxKey)
                .filter(Objects::nonNull)
                .toList());
        List<Delivery> items = deliveries.stream()
                .map(delivery -> MailingViews.delivery(delivery, outbox))
                .toList();
        return new RunDetail(MailingViews.run(run), items);
    }

    /**
     * The mailing, if it is still there: what a solution package looks for before it creates it again (ADR-0047). The
     * current person must keep it, as for {@link #get(long)}.
     */
    @Transactional(readOnly = true)
    public Optional<Mailing> find(long id) {
        return repo.find(id).map(row -> {
            access.report(row.viewId());
            return view(row);
        });
    }

    /** The stored mailing the current person keeps. */
    private MailingRow row(long id) {
        MailingRow row = repo.find(id).orElseThrow(MailingAccess::mailingNotFound);
        access.report(row.viewId());
        return row;
    }

    private Mailing view(MailingRow row) {
        var recipients = repo.recipients(List.of(row.id())).getOrDefault(row.id(), List.of());
        return views.mailing(row, recipients, runs.latest(List.of(row.id())).get(row.id()));
    }

    private List<Long> recipientIds(long id) {
        return repo.recipients(List.of(id)).getOrDefault(id, List.of()).stream()
                .map(ReportMailingRepository.RecipientRow::id)
                .toList();
    }

    private static Values values(MailingRow row) {
        return new Values(
                row.name(),
                row.channels(),
                row.attachXlsx(),
                row.includeLink(),
                row.frequency(),
                row.weekday(),
                row.monthDay(),
                row.time(),
                row.timeZone(),
                row.parameters(),
                row.condition(),
                row.active(),
                row.nextRunAt());
    }

    private static Map<String, Object> audited(Values values, List<Long> recipients) {
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("name", values.name());
        audited.put("channels", String.join(",", values.channels()));
        audited.put("attach_xlsx", values.attachXlsx());
        audited.put("include_link", values.includeLink());
        audited.put(
                "schedule",
                values.frequency() + " " + values.time() + " " + values.timeZone()
                        + (values.weekday() == null ? "" : " d" + values.weekday())
                        + (values.monthDay() == null ? "" : " m" + values.monthDay()));
        audited.put("parameters", values.parameters());
        audited.put("condition", values.condition());
        audited.put("active", values.active());
        audited.put(
                "recipients",
                recipients.stream().sorted().map(String::valueOf).toList().toString());
        return audited;
    }
}
