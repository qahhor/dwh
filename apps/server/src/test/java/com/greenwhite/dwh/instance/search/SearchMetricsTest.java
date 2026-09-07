package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.service.*;
import com.greenwhite.dwh.instance.search.repository.*;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.*;
import com.greenwhite.dwh.instance.search.typesense.*;
import com.greenwhite.dwh.instance.common.security.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchMetricsTest {
    private final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    private final SearchMetrics metrics=new SearchMetrics(registry);
    @AfterEach void cleanup() { SecurityContext.clear();registry.close(); }
    @Test void actualQueryFallbackAndFailurePathsRecordOnlyFiniteLabels() {
        var client=mock(TypesenseClient.class);
        var fallback=mock(SearchFallbackRepository.class);
        var access=mock(SearchAccessPolicy.class);
        var policies=mock(SearchPolicyProvider.class);
        var snapshots=mock(SearchExecutionSnapshotReader.class);
        when(snapshots.read()).thenReturn(new SearchExecutionSnapshot(new SearchIndexStateRepository.IndexSnapshot(UUID.randomUUID(),1,
                Map.of("TASK","fixture_tasks","PROJECT","fixture_projects","USER","fixture_users"),"MIXED",true,false),new SettingsSnapshot(1,SearchQueryPolicy.defaults())));
        var service=new SearchService(client,fallback,access,new SearchResultBudget(),policies,snapshots,Optional.of(metrics));
        when(client.isEnabled()).thenReturn(true);
        when(client.multiSearch(anyString(),anyString(),anyInt(),anyMap(),any())).thenReturn(List.of(new TypesenseClient.CollectionSearch("TASK",List.of(),0,7)));
        assertThat(service.search("private-query-marker","TASK",10).source()).isEqualTo("TYPESENSE");
        when(client.multiSearch(anyString(),anyString(),anyInt(),anyMap(),any())).thenThrow(TypesenseException.unavailable());
        when(fallback.search(anyString(),anyString(),anyInt())).thenReturn(new SearchFallbackRepository.FallbackSearch(List.of()));
        assertThat(service.search("private-query-marker","TASK",10).degraded()).isTrue();
        when(fallback.search(anyString(),anyString(),anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("safe fixture"));
        assertThatThrownBy(() -> service.search("private-query-marker","TASK",10)).isInstanceOf(com.greenwhite.dwh.instance.common.error.ApiException.class);
        assertThat(registry.find("dwh.search.query.duration").timers()).hasSize(3);
        assertThat(registry.find("dwh.search.engine.duration").timer()).isNotNull();
        assertThat(registry.find("dwh.search.engine.duration").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(7);
        assertThat(registry.find("dwh.search.fallback").counter()).isNotNull();
        assertThat(registry.find("dwh.search.query.errors").counter()).isNotNull();
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("entity","source","outcome");
            assertThat(tag.getValue()).isIn("TASK","TYPESENSE","POSTGRES","UNKNOWN","SUCCESS","ERROR");
        }));
    }
    @Test void metricLabelInputsCannotCreateUserQueryJobOrCollectionCardinality() {
        metrics.query(UUID.randomUUID().toString(),"private-query-marker",true,true,1);
        metrics.job("private-action","private-state",java.time.Duration.ZERO);
        metrics.engine("collection_123",1);metrics.switched(UUID.randomUUID().toString());
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag ->
                assertThat(tag.getValue()).isIn("UNKNOWN","ERROR")));
    }
}
