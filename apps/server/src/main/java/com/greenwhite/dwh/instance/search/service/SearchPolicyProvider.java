package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.search.SearchOwnerRateLimits;
import com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.SettingsSnapshot;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.core.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Shared source of the active query policy and its owner-clamped rate budgets. */
@Service
public class SearchPolicyProvider {
    private final SearchOwnerRateLimits ownerRateLimits;
    private final SearchSettingsRepository repository;
    private volatile SettingsSnapshot snapshot;
    private volatile boolean degraded = true;

    public SearchPolicyProvider(SearchOwnerRateLimits ownerRateLimits, SearchSettingsRepository repository) {
        this.ownerRateLimits = ownerRateLimits;
        this.repository = repository;
    }

    public SearchQueryPolicy current() {
        return snapshot().policy();
    }

    public SettingsSnapshot snapshot() {
        SettingsSnapshot current = snapshot;
        if (current == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Search configuration is unavailable");
        return current;
    }

    @PostConstruct
    @Scheduled(fixedDelay=5000, initialDelay=5000)
    public void refresh() {
        try { publishCommitted(repository.current()); }
        catch (RuntimeException failure) { degraded = true; }
    }

    public synchronized void publishCommitted(SettingsSnapshot candidate) {
        if (snapshot == null || candidate.version() >= snapshot.version()) {
            snapshot = candidate;
            degraded = false;
        }
    }
    public boolean degraded() { return degraded; }

    public SearchRateBudget effectiveBudget(int ownerPerMinute) {
        return effectiveBudget(current(), ownerPerMinute);
    }

    public EffectiveBudgets effectiveBudgets() {
        return effectiveBudgets(current());
    }

    public EffectiveBudgets effectiveBudgets(SearchQueryPolicy policy) {
        return new EffectiveBudgets(
                effectiveBudget(policy, ownerRateLimits.userPerMinute()),
                effectiveBudget(policy, ownerRateLimits.tokenPerMinute()));
    }

    private static SearchRateBudget effectiveBudget(SearchQueryPolicy policy, int ownerPerMinute) {
        int perMinute = Math.min(policy.requestsPerMinute(), ownerPerMinute);
        return new SearchRateBudget(perMinute, Math.min(policy.burst(), perMinute));
    }

    public record EffectiveBudgets(SearchRateBudget user, SearchRateBudget api) {}
}
