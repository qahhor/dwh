package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.*;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.repository.*;
import com.smartup24.cms.instance.search.service.*;
import com.smartup24.cms.instance.search.typesense.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;

class SearchMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SearchMetrics metrics = new SearchMetrics(registry);

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
        registry.close();
    }

    @Test
    void actualQueryFallbackAndFailurePathsRecordOnlyFiniteLabels() {
        var client = mock(TypesenseSearch.class);
        var fallback = mock(SearchFallbackRepository.class);
        var access = mock(SearchAccessPolicy.class);
        var policies = mock(SearchPolicyProvider.class);
        var snapshots = mock(SearchExecutionSnapshotReader.class);
        when(access.requireSearchAccess()).thenReturn(1L);
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "admin", "admin@example.invalid", 1L, false, Set.of("*.*"), 1L, false, 0, null));
        when(snapshots.read())
                .thenReturn(new SearchExecutionSnapshot(
                        new SearchIndexStateRepository.IndexSnapshot(
                                UUID.randomUUID(), 1, Map.of("ms.tasks", "fixture_tasks"), "MIXED", true, false),
                        new SettingsSnapshot(1, SearchQueryPolicy.defaults())));
        var entities = SearchTestEntities.unscoped();
        var service = new SearchService(
                client,
                fallback,
                access,
                new SearchResultBudget(),
                policies,
                snapshots,
                entities,
                SearchAccessFixtures.unrestrictedScopes(),
                new SearchFieldPolicies(entities),
                Optional.empty(),
                Optional.of(metrics));
        when(client.isEnabled()).thenReturn(true);
        when(client.multiSearch(anyString(), anyList()))
                .thenReturn(List.of(new TypesenseSearch.CollectionSearch("ms.tasks", List.of(), 0, 7)));
        assertThat(service.search("private-query-marker", "ms.tasks", 10).source())
                .isEqualTo("TYPESENSE");
        when(client.multiSearch(anyString(), anyList())).thenThrow(TypesenseException.unavailable());
        when(fallback.search(any(SearchEntity.class), anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of());
        assertThat(service.search("private-query-marker", "ms.tasks", 10).degraded())
                .isTrue();
        when(fallback.search(any(SearchEntity.class), anyString(), anyList(), anyInt(), any()))
                .thenThrow(new DataAccessResourceFailureException("safe fixture"));
        assertThatThrownBy(() -> service.search("private-query-marker", "ms.tasks", 10))
                .isInstanceOf(ApiException.class);
        assertThat(registry.find("smc.search.query.duration").timers()).hasSize(3);
        assertThat(registry.find("smc.search.engine.duration").timer()).isNotNull();
        assertThat(registry.find("smc.search.engine.duration").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(7);
        assertThat(registry.find("smc.search.fallback").counter()).isNotNull();
        assertThat(registry.find("smc.search.query.errors").counter()).isNotNull();
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("entity", "source", "outcome");
                    assertThat(tag.getValue()).isIn("ms.tasks", "TYPESENSE", "POSTGRES", "UNKNOWN", "SUCCESS", "ERROR");
                }));
    }

    @Test
    void metricLabelInputsCannotCreateUserQueryJobOrCollectionCardinality() {
        metrics.query(UUID.randomUUID().toString(), "private-query-marker", true, true, 1);
        metrics.job("private-action", "private-state", Duration.ZERO);
        metrics.engine("collection_123", 1);
        metrics.switched(UUID.randomUUID().toString());
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getValue()).isIn("UNKNOWN", "ERROR")));
    }
}
