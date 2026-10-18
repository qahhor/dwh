package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.instance.common.web.RequestZone;
import com.smartup24.cms.instance.kauth.service.KauthDeliveryAddresses;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.api.OutboxAttachment;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.report.export.AggregateExportService;
import com.smartup24.cms.instance.report.export.ExportPrincipals;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.MailingRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository.DeliveryValues;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository.RunRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers one run of a mailing to one recipient (ADR-0045).
 *
 * <p>The report runs as the recipient — their rights, data scope and field rights, in their language and the mailing's
 * time zone — never as the mailing's owner; a recipient who may no longer read the report gets nothing. The condition
 * is judged on that result. A letter carries the XLSX of the same result unless it is larger than the limit (then the
 * letter says so and links the report); Telegram carries a text and a link button; in-app a notification with the
 * path. Letters and messages go through the notification outbox with a key made of the run, the user and the channel,
 * and the deliveries are recorded in the same transaction, so a retried run neither sends nor records twice.
 */
@Component
public class MailingDispatcher {
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String EVENT_TYPE = "report_mailing";
    /** The kind of an in-app notification that only informs. */
    static final String INFO = "info";

    private static final Logger log = LoggerFactory.getLogger(MailingDispatcher.class);

    private final EntityReports reports;
    private final AggregateExportService exports;
    private final ExportPrincipals principals;
    private final MdUserService users;
    private final MdUserTexts texts;
    private final KauthDeliveryAddresses addresses;
    private final MsNotificationService notifications;
    private final MailingMessages messages;
    private final MailingRequests requests;
    private final MailingMetrics metrics;
    private final ReportMailingRunRepository runs;
    private final TransactionTemplate transaction;
    private final long maxAttachmentBytes;

    public MailingDispatcher(
            EntityReports reports,
            AggregateExportService exports,
            ExportPrincipals principals,
            MdUserService users,
            MdUserTexts texts,
            KauthDeliveryAddresses addresses,
            MsNotificationService notifications,
            MailingMessages messages,
            MailingRequests requests,
            MailingMetrics metrics,
            ReportMailingRunRepository runs,
            PlatformTransactionManager transactions,
            @Value("${smc.report-mailings.max-attachment-bytes:5242880}") long maxAttachmentBytes) {
        this.reports = reports;
        this.exports = exports;
        this.principals = principals;
        this.users = users;
        this.texts = texts;
        this.addresses = addresses;
        this.notifications = notifications;
        this.messages = messages;
        this.requests = requests;
        this.metrics = metrics;
        this.runs = runs;
        this.transaction = new TransactionTemplate(transactions);
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    /** The channels a recipient can be reached on now, each with its address; the others with the reason why not. */
    private record Targets(Map<String, String> ready, List<DeliveryValues> skipped) {}

    public void deliver(RunRow run, MailingRow mailing, long userId) {
        Targets targets = targets(mailing, userId);
        if (targets.ready().isEmpty()) {
            record(run.id(), targets.skipped());
            return;
        }
        String lang = texts.language(userId);
        ZoneId zone = ZoneId.of(mailing.timeZone());
        EntityReports.Execution execution;
        try {
            execution = principals.callAs(
                    userId,
                    () -> RequestLanguage.with(
                            lang,
                            () -> RequestZone.with(
                                    zone,
                                    () -> reports.executeSaved(
                                            mailing.entity(), mailing.viewId(), null, mailing.parameters()))));
        } catch (ApiException refused) {
            boolean denied = refused.getErrorCode() == ErrorCode.NOT_FOUND
                    || refused.getErrorCode() == ErrorCode.PERMISSION_DENIED;
            String reason = denied
                    ? "no_access"
                    : refused.getErrorCode() == ErrorCode.VALIDATION_FAILED ? "parameters" : "report_failed";
            outcome(run.id(), userId, targets, denied ? "skipped" : "failed", reason);
            return;
        } catch (RuntimeException failure) {
            log.warn("report_mailing_report_failed mailing={} run={} user={}", mailing.id(), run.id(), userId, failure);
            outcome(run.id(), userId, targets, "failed", "report_failed");
            return;
        }
        boolean holds = condition(mailing).holds(execution.data());
        if (!holds && !"test".equals(run.kind())) {
            outcome(run.id(), userId, targets, "skipped", "condition");
            return;
        }
        var subject = new MailingMessages.Subject(
                userId,
                mailing.name(),
                mailing.entity(),
                mailing.viewId(),
                mailing.parameters(),
                run.slot(),
                zone,
                execution.data().rows().size());
        send(
                run,
                mailing,
                targets,
                subject,
                holds ? null : "condition_ignored",
                workbook(mailing, targets, execution, lang));
    }

    private Targets targets(MailingRow mailing, long userId) {
        Map<String, String> ready = new LinkedHashMap<>();
        List<DeliveryValues> skipped = new ArrayList<>();
        String state;
        try {
            state = users.getUserIdentity(userId).state();
        } catch (ApiException gone) {
            log.info("report_mailing_recipient_missing user={} code={}", userId, gone.getErrorCode());
            state = "";
        }
        for (String channel : mailing.channels()) {
            String reason = null;
            String address = null;
            if (!"A".equals(state)) {
                reason = "inactive";
            } else if (!notifications.isNotificationEnabled(userId, EVENT_TYPE, channel)) {
                reason = "opted_out";
            } else if (MailingRequests.IN_APP.equals(channel)) {
                address = Long.toString(userId);
            } else if (!addresses.deliverable(channel)) {
                reason = "channel_unavailable";
            } else {
                address = addresses.confirmedAddress(userId, channel).orElse(null);
                if (address == null) reason = "no_address";
            }
            if (address != null) ready.put(channel, address);
            else skipped.add(new DeliveryValues(userId, channel, "skipped", reason, null, null));
        }
        return new Targets(ready, skipped);
    }

    private MailingCondition condition(MailingRow mailing) {
        var stored = requests.condition(mailing);
        return new MailingCondition(stored.kind(), stored.measure(), stored.op(), stored.value());
    }

    /** The XLSX for a letter, or null: no letter, no attachment asked, or a file larger than the limit (empty array). */
    private byte @Nullable [] workbook(
            MailingRow mailing, Targets targets, EntityReports.Execution execution, String lang) {
        if (!mailing.attachXlsx() || !targets.ready().containsKey(MailingRequests.EMAIL)) return null;
        try {
            byte[] file = exports.workbook(mailing.entity(), execution, lang);
            return file.length > maxAttachmentBytes ? new byte[0] : file;
        } catch (IOException e) {
            log.warn("report_mailing_workbook_failed mailing={}", mailing.id(), e);
            return new byte[0];
        }
    }

    private void send(
            RunRow run,
            MailingRow mailing,
            Targets targets,
            MailingMessages.Subject subject,
            @Nullable String note,
            byte @Nullable [] file) {
        transaction.executeWithoutResult(status -> {
            List<DeliveryValues> done = new ArrayList<>(targets.skipped());
            for (var target : targets.ready().entrySet()) {
                done.add(sendOne(run, mailing, subject, target.getKey(), target.getValue(), note, file));
            }
            insert(run.id(), done);
        });
    }

    private DeliveryValues sendOne(
            RunRow run,
            MailingRow mailing,
            MailingMessages.Subject subject,
            String channel,
            String address,
            @Nullable String note,
            byte @Nullable [] file) {
        long user = subject.userId();
        int rows = subject.rows();
        if (MailingRequests.IN_APP.equals(channel)) {
            notifications.sendInAppNotification(
                    user,
                    INFO,
                    messages.inAppTitle(subject),
                    messages.inAppBody(subject),
                    MailingMessages.path(mailing.entity(), mailing.viewId(), mailing.parameters()),
                    "report_mailing:" + mailing.id());
            return new DeliveryValues(user, channel, "sent", note, null, rows);
        }
        UUID key = key(run.id(), user, channel);
        Map<String, Object> payload = new LinkedHashMap<>();
        OutboxAttachment attachment = null;
        String reason = note;
        if (MailingRequests.EMAIL.equals(channel)) {
            boolean attached = file != null && file.length > 0;
            boolean tooLarge = file != null && file.length == 0;
            if (tooLarge) reason = "attachment_too_large";
            payload.put("subject", messages.subject(subject));
            payload.put(
                    "body", messages.letter(subject, attached, tooLarge, mailing.includeLink(), maxAttachmentBytes));
            if (attached) attachment = new OutboxAttachment(fileName(subject), XLSX, file);
        } else {
            payload.put("body", messages.telegram(subject));
            String link = mailing.includeLink() ? messages.link(subject) : null;
            if (link != null) {
                payload.put("buttonText", messages.buttonText(subject));
                payload.put("buttonUrl", link);
            }
        }
        notifications.enqueueExternalNotification(channel, address, EVENT_TYPE, payload, key, attachment);
        return new DeliveryValues(user, channel, "queued", reason, key, rows);
    }

    /** Every channel the recipient could be reached on gets the same outcome: the report gave nothing to send. */
    private void outcome(long runId, long userId, Targets targets, String status, String reason) {
        List<DeliveryValues> all = new ArrayList<>(targets.skipped());
        targets.ready()
                .keySet()
                .forEach(channel -> all.add(new DeliveryValues(userId, channel, status, reason, null, null)));
        record(runId, all);
    }

    private void record(long runId, List<DeliveryValues> deliveries) {
        transaction.executeWithoutResult(tx -> insert(runId, deliveries));
    }

    private void insert(long runId, List<DeliveryValues> deliveries) {
        runs.insertDeliveries(runId, deliveries);
        deliveries.forEach(delivery -> metrics.delivery(delivery.channel(), delivery.status()));
    }

    /** The outbox key of a run, a user and a channel: the same every time, so the outbox takes it once. */
    static UUID key(long runId, long userId, String channel) {
        return UUID.nameUUIDFromBytes(
                ("report-mailing:" + runId + ":" + userId + ":" + channel).getBytes(StandardCharsets.UTF_8));
    }

    private static String fileName(MailingMessages.Subject subject) {
        String safe = subject.name().replaceAll("[^\\p{L}\\p{N} ._-]", "_").strip();
        if (safe.isEmpty()) safe = "report";
        return (safe.length() > 80 ? safe.substring(0, 80) : safe) + " "
                + MailingMessages.DATE.format(subject.slot().atZone(subject.zone())) + ".xlsx";
    }
}
