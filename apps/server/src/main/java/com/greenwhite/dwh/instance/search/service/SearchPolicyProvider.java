package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.search.SearchOwnerRateLimits;
import org.springframework.stereotype.Service;

/** Shared source of the active query policy and its owner-clamped rate budgets. */
@Service
public class SearchPolicyProvider {
    private final SearchOwnerRateLimits ownerRateLimits;

    public SearchPolicyProvider(SearchOwnerRateLimits ownerRateLimits) {
        this.ownerRateLimits = ownerRateLimits;
    }

    public SearchQueryPolicy current() {
        return SearchQueryPolicy.defaults();
    }

    public SearchRateBudget effectiveBudget(int ownerPerMinute) {
        return effectiveBudget(current(), ownerPerMinute);
    }

    public EffectiveBudgets effectiveBudgets() {
        SearchQueryPolicy policy = current();
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
