package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchPolicyProviderTest {

    @Test
    void effectiveBudgetClampsBothRateAndCapacityToOwnerLimit() {
        var provider = new SearchPolicyProvider(
                new OwnerLimits(7, 5), org.mockito.Mockito.mock(SearchSettingsRepository.class));
        provider.publishCommitted(new SearchManagementDtos.SettingsSnapshot(1, SearchQueryPolicy.defaults()));

        assertThat(provider.effectiveBudget(7).perMinute()).isEqualTo(7);
        assertThat(provider.effectiveBudget(7).capacity()).isEqualTo(7);
    }

    @Test
    void effectiveBudgetsUseOnePolicySnapshotAndConfiguredOwnerLimits() {
        var reads = new AtomicInteger();
        var provider =
                new SearchPolicyProvider(
                        new OwnerLimits(7, 5), org.mockito.Mockito.mock(SearchSettingsRepository.class)) {
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

    @Test
    void repeatedRateChecksUseTheApplicationSnapshotWithoutDatabaseReads() {
        var repository = org.mockito.Mockito.mock(SearchSettingsRepository.class);
        org.mockito.Mockito.when(repository.current())
                .thenReturn(new SearchManagementDtos.SettingsSnapshot(1, SearchQueryPolicy.defaults()));
        var provider = new SearchPolicyProvider(new OwnerLimits(600, 300), repository);
        provider.refresh();
        for (int i = 0; i < 100; i++)
            assertThat(provider.effectiveBudget(600).perMinute()).isEqualTo(120);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).current();
    }

    @Test
    void anOlderDelayedRefreshCannotReplaceANewerPublishedPolicy() throws Exception {
        var repository = org.mockito.Mockito.mock(SearchSettingsRepository.class);
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var old = new SearchManagementDtos.SettingsSnapshot(
                10,
                new SearchQueryPolicy(
                        4, 30, 10, "MIXED", SearchQueryPolicy.defaults().fields()));
        var fresh = new SearchManagementDtos.SettingsSnapshot(
                11,
                new SearchQueryPolicy(
                        5, 60, 15, "MIXED", SearchQueryPolicy.defaults().fields()));
        org.mockito.Mockito.when(repository.current()).thenAnswer(invocation -> {
            read.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture refresh timeout");
            return old;
        });
        var provider = new SearchPolicyProvider(new OwnerLimits(600, 300), repository);
        provider.publishCommitted(old);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var refreshing = executor.submit(provider::refresh);
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                provider.publishCommitted(fresh);
            } finally {
                release.countDown();
            }
            refreshing.get(10, TimeUnit.SECONDS);
        }
        assertThat(provider.current().globalLimit()).isEqualTo(5);
        assertThat(provider.effectiveBudget(600).perMinute()).isEqualTo(60);
    }

    @Test
    void onlyTheOriginalEmptyMigrationSeedCanResolveToDefaults() {
        assertThat(SearchManagementDtos.decodeStored("{}", 1).globalLimit()).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SearchManagementDtos.decodeStored("{}", 2))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private record OwnerLimits(int userPerMinute, int tokenPerMinute) implements SearchOwnerRateLimits {}
}
