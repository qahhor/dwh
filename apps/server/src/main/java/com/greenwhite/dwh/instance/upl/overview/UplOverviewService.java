package com.greenwhite.dwh.instance.upl.overview;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.upl.overview.UplOverviewRepository.PackageAttentionRow;
import com.greenwhite.dwh.instance.upl.overview.UplOverviewRepository.SourceFreshnessRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The data overview (roadmap wave 5): what came in over a period and what came
 * of it, how fresh each source's data is, and what needs somebody's hand. A
 * person picks the period from a short fixed list, so every figure on the page
 * is about the same days.
 *
 * <p>Freshness follows each source's periodicity and deadline: after the last
 * period that reached the warehouse comes the next one; once it has closed the
 * data is due, and once the deadline ({@code sla_days} after the close) has
 * passed it is overdue. A source that never delivered is "never"; an ad hoc
 * source has no schedule.
 */
@Service
public class UplOverviewService {

    /** Periods the overview offers, in days. */
    public static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    public static final String OVERVIEW_PERIOD_INVALID = "UPL_OVERVIEW_PERIOD_INVALID";
    /** At most this many items of each kind reach the attention block; the lists say where the rest is. */
    static final int ATTENTION_PER_KIND = 10;

    private final UplOverviewRepository repo;
    private final Clock clock;

    @Autowired
    public UplOverviewService(UplOverviewRepository repo) {
        this(repo, Clock.systemUTC());
    }

    UplOverviewService(UplOverviewRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public record Overview(int days, Instant generatedAt, UplOverviewRepository.Totals totals,
                           List<SourceFreshness> freshness, List<AttentionItem> attention) {
    }

    /** How fresh a source's data is: {@code fresh}, {@code due}, {@code overdue}, {@code never} or {@code adhoc}. */
    public record SourceFreshness(long sourceId, String code, String name, String periodicity, String state,
                                  LocalDate lastPeriodTo, Instant lastAppliedAt, LocalDate expectedPeriodTo,
                                  LocalDate dueBy) {
    }

    /**
     * Something to act on: an {@code overdue} source, a {@code rejected} upload nobody replaced yet, or a
     * checked upload {@code waiting} to be applied.
     */
    public record AttentionItem(String kind, long sourceId, String sourceCode, String sourceName, String packageId, String fileName,
                                LocalDate periodFrom, LocalDate periodTo, Instant uploadedAt, LocalDate dueBy,
                                Integer daysLate) {
    }

    @Transactional(readOnly = true)
    public Overview overview(int days) {
        if (!PERIODS.contains(days)) {
            throw ApiException.validation(OVERVIEW_PERIOD_INVALID,
                    List.of(new FieldErrorItem("days", OVERVIEW_PERIOD_INVALID, "days must be one of " + PERIODS)));
        }
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(days));
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<SourceFreshness> freshness = repo.sourceFreshness().stream().map(row -> freshness(row, today)).toList();
        return new Overview(days, now, repo.totals(since), freshness, attention(freshness, since, today));
    }

    private List<AttentionItem> attention(List<SourceFreshness> freshness, Instant since, LocalDate today) {
        List<AttentionItem> items = new ArrayList<>();
        freshness.stream()
                .filter(source -> "overdue".equals(source.state()))
                .sorted(Comparator.comparing(SourceFreshness::dueBy))
                .limit(ATTENTION_PER_KIND)
                .forEach(source -> items.add(new AttentionItem("overdue", source.sourceId(), source.code(), source.name(), null, null,
                        null, source.expectedPeriodTo(), null, source.dueBy(),
                        (int) ChronoUnit.DAYS.between(source.dueBy(), today))));
        for (PackageAttentionRow row : repo.rejectedNotReplaced(since, ATTENTION_PER_KIND)) {
            items.add(packageItem("rejected", row));
        }
        for (PackageAttentionRow row : repo.waitingToApply(ATTENTION_PER_KIND)) {
            items.add(packageItem("waiting", row));
        }
        return items;
    }

    private static AttentionItem packageItem(String kind, PackageAttentionRow row) {
        return new AttentionItem(kind, row.sourceId(), row.sourceCode(), row.sourceName(), row.publicId().toString(), row.fileName(),
                row.periodFrom(), row.periodTo(), row.uploadedAt(), null, null);
    }

    /** The source's state on a day, from its last delivered period, periodicity and deadline. */
    static SourceFreshness freshness(SourceFreshnessRow row, LocalDate today) {
        if ("adhoc".equals(row.periodicity())) {
            return of(row, "adhoc", null, null);
        }
        if (row.lastPeriodTo() == null) {
            return of(row, "never", null, null);
        }
        LocalDate expected = nextPeriodEnd(row.lastPeriodTo(), row.periodicity());
        LocalDate dueBy = expected.plusDays(row.slaDays());
        String state = !today.isAfter(expected) ? "fresh" : !today.isAfter(dueBy) ? "due" : "overdue";
        return of(row, state, expected, dueBy);
    }

    /** The end of the period that follows the one ending on {@code last}. */
    static LocalDate nextPeriodEnd(LocalDate last, String periodicity) {
        int months = switch (periodicity) {
            case "quarter" -> 3;
            case "year" -> 12;
            default -> 1;
        };
        return last.plusMonths(months).with(TemporalAdjusters.lastDayOfMonth());
    }

    private static SourceFreshness of(SourceFreshnessRow row, String state, LocalDate expected, LocalDate dueBy) {
        return new SourceFreshness(row.id(), row.code(), row.name(), row.periodicity(), state, row.lastPeriodTo(),
                row.lastAppliedAt(), expected, dueBy);
    }
}
