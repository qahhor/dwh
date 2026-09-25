package com.greenwhite.dwh.instance.upl.overview;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The data overview (roadmap wave 5): what came in over a period and what came
 * of it. A person picks the period from a short fixed list, so every figure on
 * the page is about the same days.
 */
@Service
public class UplOverviewService {

    /** Periods the overview offers, in days. */
    public static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    public static final String OVERVIEW_PERIOD_INVALID = "UPL_OVERVIEW_PERIOD_INVALID";

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

    public record Overview(int days, Instant generatedAt, UplOverviewRepository.Totals totals) {
    }

    @Transactional(readOnly = true)
    public Overview overview(int days) {
        if (!PERIODS.contains(days)) {
            throw ApiException.validation(OVERVIEW_PERIOD_INVALID,
                    List.of(new FieldErrorItem("days", OVERVIEW_PERIOD_INVALID, "days must be one of " + PERIODS)));
        }
        Instant now = clock.instant();
        return new Overview(days, now, repo.totals(now.minus(Duration.ofDays(days))));
    }
}
