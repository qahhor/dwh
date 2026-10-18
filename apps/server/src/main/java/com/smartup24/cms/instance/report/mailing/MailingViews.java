package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.report.api.ReportMailingDtos.Delivery;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.LastRun;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Recipient;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Run;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Schedule;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.MailingRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.RecipientRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository.DeliveryRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRunRepository.RunRow;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The API shapes of stored mailings, runs and deliveries (ADR-0045). */
@Component
public class MailingViews {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final MailingRequests requests;

    public MailingViews(MailingRequests requests) {
        this.requests = requests;
    }

    public Mailing mailing(MailingRow row, List<RecipientRow> recipients, @Nullable RunRow last) {
        return new Mailing(
                row.id(),
                row.viewId(),
                row.entity(),
                row.name(),
                recipients.stream()
                        .map(user -> new Recipient(user.id(), user.name(), user.login(), user.state()))
                        .toList(),
                row.channels(),
                row.attachXlsx(),
                row.includeLink(),
                new Schedule(row.frequency(), row.time().format(TIME), row.timeZone(), row.weekday(), row.monthDay()),
                requests.parameters(row),
                requests.condition(row),
                row.active(),
                row.nextRunAt(),
                row.ownerId(),
                last == null ? null : new LastRun(last.id(), last.status(), last.createdAt()),
                row.revision(),
                row.createdAt(),
                row.modifiedAt());
    }

    public static Run run(RunRow row) {
        return new Run(
                row.id(),
                row.kind(),
                row.slot(),
                row.status(),
                row.requestedBy(),
                row.recipients(),
                row.sent(),
                row.skipped(),
                row.failed(),
                row.error(),
                row.createdAt(),
                row.finishedAt());
    }

    public static Delivery delivery(DeliveryRow row, Map<UUID, String> outbox) {
        return new Delivery(
                row.userId(),
                row.userName(),
                row.channel(),
                row.status(),
                row.reason(),
                row.outboxKey() == null ? null : outbox.get(row.outboxKey()),
                row.rows());
    }
}
