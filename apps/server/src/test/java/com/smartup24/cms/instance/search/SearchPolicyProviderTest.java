package com.smartup24.cms.instance.search;

import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPolicyProviderTest {

    @Test
    void effectiveBudgetClampsBothRateAndCapacityToOwnerLimit() {
        var provider = new SearchPolicyProvider(new OwnerLimits(7, 5), org.mockito.Mockito.mock(com.smartup24.cms.instance.search.repository.SearchSettingsRepository.class));
        provider.publishCommitted(new com.smartup24.cms.instance.search.dto.SearchManagementDtos.SettingsSnapshot(1,SearchQueryPolicy.defaults()));

        assertThat(provider.effectiveBudget(7).perMinute()).isEqualTo(7);
        assertThat(provider.effectiveBudget(7).capacity()).isEqualTo(7);
    }

    @Test
    void effectiveBudgetsUseOnePolicySnapshotAndConfiguredOwnerLimits() {
        var reads = new AtomicInteger();
        var provider = new SearchPolicyProvider(new OwnerLimits(7, 5), org.mockito.Mockito.mock(com.smartup24.cms.instance.search.repository.SearchSettingsRepository.class)) {
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

    @Test void repeatedRateChecksUseTheApplicationSnapshotWithoutDatabaseReads() {
        var repository=org.mockito.Mockito.mock(com.smartup24.cms.instance.search.repository.SearchSettingsRepository.class);
        org.mockito.Mockito.when(repository.current()).thenReturn(new com.smartup24.cms.instance.search.dto.SearchManagementDtos.SettingsSnapshot(1,SearchQueryPolicy.defaults()));
        var provider=new SearchPolicyProvider(new OwnerLimits(600,300),repository);
        provider.refresh();
        for (int i=0;i<100;i++) assertThat(provider.effectiveBudget(600).perMinute()).isEqualTo(120);
        org.mockito.Mockito.verify(repository,org.mockito.Mockito.times(1)).current();
    }

    @Test void anOlderDelayedRefreshCannotReplaceANewerPublishedPolicy() throws Exception {
        var repository=org.mockito.Mockito.mock(com.smartup24.cms.instance.search.repository.SearchSettingsRepository.class);
        var read=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var old=new com.smartup24.cms.instance.search.dto.SearchManagementDtos.SettingsSnapshot(10,
                new SearchQueryPolicy(4,30,10,"MIXED",SearchQueryPolicy.defaults().fields()));
        var fresh=new com.smartup24.cms.instance.search.dto.SearchManagementDtos.SettingsSnapshot(11,
                new SearchQueryPolicy(5,60,15,"MIXED",SearchQueryPolicy.defaults().fields()));
        org.mockito.Mockito.when(repository.current()).thenAnswer(invocation -> {
            read.countDown();
            if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("fixture refresh timeout");
            return old;
        });
        var provider=new SearchPolicyProvider(new OwnerLimits(600,300),repository);
        provider.publishCommitted(old);
        try (var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var refreshing=executor.submit(provider::refresh);
            try {
                assertThat(read.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                provider.publishCommitted(fresh);
            } finally { release.countDown(); }
            refreshing.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(provider.current().globalLimit()).isEqualTo(5);
        assertThat(provider.effectiveBudget(600).perMinute()).isEqualTo(60);
    }

    @Test void onlyTheOriginalEmptyMigrationSeedCanResolveToDefaults() {
        assertThat(com.smartup24.cms.instance.search.dto.SearchManagementDtos.decodeStored("{}",1).globalLimit()).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.smartup24.cms.instance.search.dto.SearchManagementDtos.decodeStored("{}",2))
                .isInstanceOfSatisfying(com.smartup24.cms.instance.common.error.ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(com.smartup24.cms.core.error.ErrorCode.SERVICE_UNAVAILABLE));
    }

    private record OwnerLimits(int userPerMinute, int tokenPerMinute) implements SearchOwnerRateLimits {}
}
