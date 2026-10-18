package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.entity.report.ReportParameters;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryAggregateSpec;
import com.smartup24.cms.instance.kauth.service.KauthDeliveryAddresses;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.report.api.ReportMailingDtos;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Condition;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.MailingRequest;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Schedule;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.MailingRow;
import com.smartup24.cms.instance.report.repository.ReportMailingRepository.Values;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks a mailing as its keeper asks for it (ADR-0045) and answers what to store; every problem is a 422 at the part
 * to fix. Recipients are active users; channels are the ones this installation delivers; the parameters are checked
 * the way a reader's are (ADR-0044), with presets counted in the mailing's time zone; a threshold needs a grand total.
 */
@Component
public class MailingRequests {
    public static final String IN_APP = "in_app";
    public static final String EMAIL = "email";
    public static final String TELEGRAM = "telegram";
    public static final List<String> CHANNELS = List.of(EMAIL, TELEGRAM, IN_APP);
    static final String INVALID = "REPORT_MAILING_INVALID";
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
    private static final Set<String> CONDITIONS =
            Set.of(MailingCondition.ALWAYS, MailingCondition.HAS_ROWS, MailingCondition.THRESHOLD);

    private final KauthDeliveryAddresses addresses;
    private final EntityReports reports;
    private final ReportMailingRepository repo;
    private final ObjectMapper json;
    private final JsonColumns columns;
    private final Clock clock;

    public MailingRequests(
            KauthDeliveryAddresses addresses,
            EntityReports reports,
            ReportMailingRepository repo,
            ObjectMapper json,
            ObjectProvider<Clock> clock) {
        this.addresses = addresses;
        this.reports = reports;
        this.repo = repo;
        this.json = json;
        this.columns = new JsonColumns(json, "report_mailings");
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** What a checked request stores: the values, the recipients and the schedule. */
    public record Checked(Values values, List<Long> recipients, MailingSchedule schedule) {}

    /** The channels this installation delivers mailings on: in-app always, email and Telegram when configured. */
    public List<String> channels() {
        return CHANNELS.stream()
                .filter(channel -> IN_APP.equals(channel) || addresses.deliverable(channel))
                .toList();
    }

    /** Checks the request for the report; {@code before} is the stored mailing of a change, null for a new one. */
    public Checked check(MailingRequest request, ReportViewItem report, @Nullable MailingRow before) {
        List<FieldErrorItem> errors = new ArrayList<>();
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > ReportMailingDtos.MAX_NAME) {
            errors.add(invalid("name", "error.report.mailing_name"));
        }
        List<Long> recipients = recipients(request.recipientIds(), errors);
        List<String> channels = channels(request.channels(), errors);
        if (!request.attachXlsx() && !request.includeLink()) {
            errors.add(invalid("attachXlsx", "error.report.mailing_format"));
        }
        MailingSchedule schedule = schedule(request.schedule(), errors);
        Map<String, Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
        if (schedule != null) parameters(report, parameters, schedule.zone(), errors);
        Condition condition = condition(request.condition(), report.state(), errors);
        if (!errors.isEmpty() || schedule == null) {
            throw ApiException.validation("error.report.mailing_invalid", errors);
        }
        Instant next = request.active() ? next(schedule, before) : null;
        Values values = new Values(
                name,
                channels,
                request.attachXlsx(),
                request.includeLink(),
                schedule.frequency(),
                schedule.weekday(),
                schedule.monthDay(),
                schedule.time(),
                schedule.zone().getId(),
                columns.object(parameters),
                columns.write(condition),
                request.active(),
                next);
        return new Checked(values, recipients, schedule);
    }

    /** The next send: kept when an active mailing keeps its schedule, else the first slot from now. */
    private @Nullable Instant next(MailingSchedule schedule, @Nullable MailingRow before) {
        if (before != null && before.active() && before.nextRunAt() != null && schedule.equals(schedule(before))) {
            return before.nextRunAt();
        }
        return schedule.nextAfter(clock.instant());
    }

    /** The schedule of a stored mailing. */
    public static MailingSchedule schedule(MailingRow row) {
        return new MailingSchedule(
                row.frequency(), row.time(), ZoneId.of(row.timeZone()), row.weekday(), row.monthDay());
    }

    private List<Long> recipients(@Nullable List<Long> ids, List<FieldErrorItem> errors) {
        Set<Long> unique = new LinkedHashSet<>();
        if (ids != null) ids.stream().filter(Objects::nonNull).forEach(unique::add);
        if (unique.isEmpty() || unique.size() > ReportMailingDtos.MAX_RECIPIENTS) {
            errors.add(FieldErrorItem.keyed(
                    "recipientIds",
                    INVALID,
                    "error.report.mailing_recipients",
                    Map.of("max", ReportMailingDtos.MAX_RECIPIENTS)));
            return List.copyOf(unique);
        }
        Map<Long, String> states = repo.userStates(unique);
        for (Long id : unique) {
            if (!"A".equals(states.get(id))) {
                errors.add(FieldErrorItem.keyed(
                        "recipientIds", INVALID, "error.report.mailing_recipient_inactive", Map.of("id", id)));
            }
        }
        return List.copyOf(unique);
    }

    private List<String> channels(@Nullable List<String> asked, List<FieldErrorItem> errors) {
        List<String> chosen =
                asked == null ? List.of() : asked.stream().distinct().toList();
        if (chosen.isEmpty() || !CHANNELS.containsAll(chosen)) {
            errors.add(invalid("channels", "error.report.mailing_channels"));
            return chosen;
        }
        List<String> available = channels();
        for (String channel : chosen) {
            if (!available.contains(channel)) {
                errors.add(FieldErrorItem.keyed(
                        "channels", INVALID, "error.report.mailing_channel_unavailable", Map.of("channel", channel)));
            }
        }
        return CHANNELS.stream().filter(chosen::contains).toList();
    }

    private static @Nullable MailingSchedule schedule(@Nullable Schedule asked, List<FieldErrorItem> errors) {
        if (asked == null) {
            errors.add(invalid("schedule", "error.report.mailing_schedule"));
            return null;
        }
        if (asked.time() == null || !TIME.matcher(asked.time()).matches()) {
            errors.add(invalid("schedule.time", "error.report.mailing_time"));
            return null;
        }
        if (asked.timeZone() == null || !ZoneId.getAvailableZoneIds().contains(asked.timeZone())) {
            errors.add(invalid("schedule.timeZone", "error.report.mailing_time_zone"));
            return null;
        }
        ZoneId zone = ZoneId.of(asked.timeZone());
        String frequency = asked.frequency() == null ? "" : asked.frequency();
        Integer weekday = MailingSchedule.WEEKLY.equals(frequency) ? asked.weekday() : null;
        Integer monthDay = MailingSchedule.MONTHLY.equals(frequency) ? asked.monthDay() : null;
        try {
            return new MailingSchedule(frequency, LocalTime.parse(asked.time()), zone, weekday, monthDay);
        } catch (IllegalArgumentException e) {
            String field = switch (e.getMessage()) {
                case "weekday" -> "schedule.weekday";
                case "monthDay" -> "schedule.monthDay";
                default -> "schedule.frequency";
            };
            errors.add(invalid(field, "error.report.mailing_schedule"));
            return null;
        }
    }

    /** The values are checked as a reader's would be, today counted in the mailing's zone (ADR-0044, 5). */
    private void parameters(
            ReportViewItem report, Map<String, Object> values, ZoneId zone, List<FieldErrorItem> errors) {
        JsonNode state = report.state();
        JsonNode dataset = state.get("dataset");
        try {
            ReportParameters.filter(
                    reports.list(report.entity(), dataset == null || dataset.isNull() ? null : dataset.asString()),
                    state.get("parameters"),
                    json.valueToTree(values),
                    new QueryAggregateSpec.Anchor(zone, LocalDate.now(clock.withZone(zone))));
        } catch (ApiException refused) {
            if (refused.getFieldErrors() == null || refused.getFieldErrors().isEmpty()) throw refused;
            errors.addAll(refused.getFieldErrors());
        }
    }

    private static Condition condition(@Nullable Condition asked, JsonNode state, List<FieldErrorItem> errors) {
        if (asked == null) return new Condition(MailingCondition.ALWAYS, null, null, null);
        if (asked.kind() == null || !CONDITIONS.contains(asked.kind())) {
            errors.add(invalid("condition.kind", "error.report.mailing_condition"));
            return asked;
        }
        if (!MailingCondition.THRESHOLD.equals(asked.kind())) return new Condition(asked.kind(), null, null, null);
        int measures = state.path("measures").size();
        if (asked.measure() == null || asked.measure() < 0 || asked.measure() >= measures) {
            errors.add(invalid("condition.measure", "error.report.mailing_condition_measure"));
        }
        if (asked.op() == null || !MailingCondition.OPS.contains(asked.op())) {
            errors.add(invalid("condition.op", "error.report.mailing_condition"));
        }
        if (asked.value() == null) errors.add(invalid("condition.value", "error.report.mailing_condition"));
        boolean grand = state.path("groupBy").isEmpty() || state.path("totals").asBoolean(false);
        if (!grand) errors.add(invalid("condition.kind", "error.report.mailing_condition_totals"));
        return asked;
    }

    private static FieldErrorItem invalid(String field, String key) {
        return FieldErrorItem.keyed(field, INVALID, key);
    }

    /** The parameters of a stored mailing as a JSON object. */
    public Map<String, Object> parameters(MailingRow row) {
        return new LinkedHashMap<>(columns.readObject(row.parameters()));
    }

    /** The condition of a stored mailing. */
    public Condition condition(MailingRow row) {
        Map<String, Object> stored = columns.readObject(row.condition());
        Object measure = stored.get("measure");
        Object value = stored.get("value");
        return new Condition(
                String.valueOf(stored.getOrDefault("kind", MailingCondition.ALWAYS)),
                measure instanceof Number number ? number.intValue() : null,
                stored.get("op") == null ? null : String.valueOf(stored.get("op")),
                value == null ? null : new java.math.BigDecimal(value.toString()));
    }
}
