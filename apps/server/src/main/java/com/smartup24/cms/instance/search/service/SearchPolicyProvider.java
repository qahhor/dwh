package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.SearchOwnerRateLimits;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.SettingsSnapshot;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Shared source of the active query policy and its owner-clamped rate budgets. */
@Service
public class SearchPolicyProvider {

    private static final Logger log = LoggerFactory.getLogger(SearchPolicyProvider.class);
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
        if (current == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "error.search_config_unavailable");
        return current;
    }

    @PostConstruct
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refresh() {
        try {
            publishCommitted(repository.current());
        } catch (RuntimeException failure) {
            log.warn("search_policy_refresh_failed error={}", failure.toString());
            degraded = true;
        }
    }

    public synchronized void publishCommitted(SettingsSnapshot candidate) {
        if (snapshot == null || candidate.version() >= snapshot.version()) {
            snapshot = candidate;
            degraded = false;
        }
    }

    public boolean degraded() {
        return degraded;
    }

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
