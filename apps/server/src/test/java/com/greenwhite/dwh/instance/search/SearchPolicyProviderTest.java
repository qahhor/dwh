package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.service.SearchPolicyProvider;
import com.greenwhite.dwh.instance.search.service.SearchQueryPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPolicyProviderTest {

    @Test
    void effectiveBudgetClampsBothRateAndCapacityToOwnerLimit() {
        var provider = new SearchPolicyProvider(new OwnerLimits(7, 5));

        assertThat(provider.effectiveBudget(7).perMinute()).isEqualTo(7);
        assertThat(provider.effectiveBudget(7).capacity()).isEqualTo(7);
    }

    @Test
    void effectiveBudgetsUseOnePolicySnapshotAndConfiguredOwnerLimits() {
        var reads = new AtomicInteger();
        var provider = new SearchPolicyProvider(new OwnerLimits(7, 5)) {
            @Override
            public SearchQueryPolicy current() {
                reads.incrementAndGet();
                return SearchQueryPolicy.defaults();
            }
        };

        var budgets = provider.effectiveBudgets();

        assertThat(budgets.user().perMinute()).isEqualTo(7);
        assertThat(budgets.user().capacity()).isEqualTo(7);
        assertThat(budgets.api().perMinute()).isEqualTo(5);
        assertThat(budgets.api().capacity()).isEqualTo(5);
        assertThat(reads).hasValue(1);
    }

    private record OwnerLimits(int userPerMinute, int tokenPerMinute) implements SearchOwnerRateLimits {}
}
