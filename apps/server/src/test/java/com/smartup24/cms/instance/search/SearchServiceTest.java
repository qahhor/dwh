package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.DataScopeRules;
import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.api.SearchManagementDtos;
import com.smartup24.cms.instance.search.api.SearchOwnerRateLimits;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository.FallbackHit;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchAccessPolicy;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchFieldPolicies;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import com.smartup24.cms.instance.search.service.SearchResultBudget;
import com.smartup24.cms.instance.search.service.SearchScopes;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.service.SearchService.SearchHit;
import com.smartup24.cms.instance.search.service.SearchService.SearchResult;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionQuery;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionSearch;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The global search over the entities with the SEARCH capability (ADR-0032, 10.3; ADR-0013, 2.5 as question 8 of
 * ADR-0032, 19 changes it): who may search, which entities a caller searches, the scope filter of the index query, the
 * database check of every hit and the PostgreSQL fallback.
 */
class SearchServiceTest {

    private static final long CALLER = 42L;

    private final TypesenseSearch typesenseClient = mock(TypesenseSearch.class);
    private final SearchFallbackRepository fallbackRepository = mock(SearchFallbackRepository.class);
    private final RoleMembershipAuthorizer roleMembershipAuthorizer = mock(RoleMembershipAuthorizer.class);
    private final DataScopeRules dataScopeRules = mock(DataScopeRules.class);
    private final SearchIndexStateRepository indexState = mock(SearchIndexStateRepository.class);
    private final SearchEntities entities = SearchTestEntities.unscoped();
    private final SearchService service = service(defaultProvider());

    @BeforeEach
    void authenticateWithLegacyWildcard() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("*.*")));
        rule(DataScopeRules.RULE_ALL, List.of());
        snapshot(SearchQueryPolicy.defaults(), true);
        // The database still finds every candidate the index answers, unless a test says otherwise.
        when(fallbackRepository.visible(any(), anyCollection(), any()))
                .thenAnswer(invocation -> new HashSet<>(invocation.<Collection<Long>>getArgument(1)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void indexStateReadFailureStillUsesStructuredPostgresFallback() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(indexState.executionSnapshot()).thenThrow(new DataAccessResourceFailureException("state unavailable"));
        when(fallbackRepository.search(any(), anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of());
        assertThat(service.search("Kafka", "ALL", 10).degraded()).isTrue();
    }

    @Test
    void typesenseMetadataDistinguishesReturnedHitsFromFoundHits() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), anyList()))
                .thenReturn(List.of(
                        group(SearchTestEntities.TASKS, 7, "11", "12", "13"),
                        group(SearchTestEntities.PROJECTS, 4, "21", "22"),
                        group(SearchTestEntities.USERS, 1, "31")));

        SearchResult result = service.search("  Kafka  ", null, 4);

        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("11", "12", "21", "31");
        assertThat(result.totalHits()).isEqualTo(4);
        assertThat(result.foundHits()).isEqualTo(12L);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.source()).isEqualTo("TYPESENSE");
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void successfulTypesenseZeroDoesNotFallBack() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("none"), anyList()))
                .thenReturn(List.of(group(SearchTestEntities.TASKS, 0)));

        SearchResult result = service.search("none", SearchTestEntities.TASKS, 50);

        assertThat(result.totalHits()).isZero();
        assertThat(result.foundHits()).isZero();
        assertThat(result.source()).isEqualTo("TYPESENSE");
        // The policy's limit of 10, and twice the candidates for the database check.
        assertThat(sentQueries("none")).singleElement().satisfies(query -> {
            assertThat(query.collection()).isEqualTo("c_entity_ms_tasks");
            assertThat(query.perPage()).isEqualTo(20);
            assertThat(query.filterBy()).isNull();
        });
        verifyNoInteractions(fallbackRepository);
    }

    @Test
    void productionServiceReadsCurrentPolicyForEachRequest() {
        SearchQueryPolicy twoResults = new SearchQueryPolicy(2, 120, 20, "MIXED", Map.of());
        SearchQueryPolicy oneResult = new SearchQueryPolicy(1, 120, 20, "MIXED", Map.of());
        var reads = new AtomicInteger();
        var index = indexState.executionSnapshot().index();
        when(indexState.executionSnapshot())
                .thenAnswer(invocation -> new SearchManagementDtos.SearchExecutionSnapshot(
                        index,
                        new SearchManagementDtos.SettingsSnapshot(
                                1, reads.getAndIncrement() == 0 ? twoResults : oneResult)));
        when(typesenseClient.isEnabled()).thenReturn(true);

        service.search("first", SearchTestEntities.TASKS, 10);
        service.search("second", SearchTestEntities.TASKS, 10);

        assertThat(sentQueries("first"))
                .singleElement()
                .satisfies(query -> assertThat(query.perPage()).isEqualTo(4));
        assertThat(sentQueries("second"))
                .singleElement()
                .satisfies(query -> assertThat(query.perPage()).isEqualTo(2));
        assertThat(reads).hasValue(2);
    }

    @Test
    void failedTypesenseUsesMarkedPostgresFallback() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), anyList())).thenThrow(TypesenseException.unavailable());
        fallbackFinds(SearchTestEntities.TASKS, "11");

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("11");
        assertThat(result.foundHits()).isNull();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void disabledTypesenseUsesTheSameMarkedFallback() {
        when(typesenseClient.isEnabled()).thenReturn(false);
        fallbackFinds(SearchTestEntities.TASKS);

        SearchResult result = service.search("Kafka", null, 10);

        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
        verify(typesenseClient, never()).multiSearch(any(), anyList());
    }

    @Test
    void uninitializedIndexUsesTheSameMarkedFallback() {
        snapshot(SearchQueryPolicy.defaults(), false);
        when(typesenseClient.isEnabled()).thenReturn(true);
        fallbackFinds(SearchTestEntities.TASKS);

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
        verify(typesenseClient, never()).multiSearch(any(), anyList());
    }

    @Test
    void anEntityWithoutACollectionYetIsSearchedInPostgresNextToTheIndex() {
        // ADR-0032, 10.3: an entity that declares the search after the last rebuild has no collection yet.
        Map<String, String> collections = new LinkedHashMap<>(collections());
        collections.remove(SearchTestEntities.ORDERS);
        when(indexState.executionSnapshot())
                .thenReturn(new SearchManagementDtos.SearchExecutionSnapshot(
                        new IndexSnapshot(UUID.randomUUID(), 1, collections, "MIXED", true, false),
                        new SearchManagementDtos.SettingsSnapshot(1, SearchQueryPolicy.defaults())));
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), anyList()))
                .thenReturn(List.of(group(SearchTestEntities.TASKS, 1, "11")));
        fallbackFinds(SearchTestEntities.ORDERS, "5");

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.source()).isEqualTo("TYPESENSE");
        assertThat(result.degraded()).isFalse();
        assertThat(result.hits())
                .extracting(SearchHit::entityType)
                .containsExactly(SearchTestEntities.TASKS, SearchTestEntities.ORDERS);
        assertThat(sentQueries("Kafka"))
                .extracting(query -> query.entity().code())
                .doesNotContain(SearchTestEntities.ORDERS);
    }

    @Test
    void fallbackFailurePropagatesAsStructuredServiceUnavailable() {
        when(typesenseClient.isEnabled()).thenReturn(false);
        when(fallbackRepository.search(any(), anyString(), anyList(), anyInt(), any()))
                .thenThrow(new IllegalStateException("database detail"));

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain("database detail", "Kafka");
                });
    }

    @Test
    void exactPositiveIdUsesParameterizedRepositoryLookupWithoutTypesense() {
        exactFinds(123L, SearchTestEntities.TASKS);

        SearchResult result = service.search("#123", SearchTestEntities.TASKS, 10);

        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("123");
        assertThat(result.foundHits()).isEqualTo(1L);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(typesenseClient);
    }

    @Test
    void exactLookupReportsKnownCountAndHasMoreWhenPolicyCapsMultipleEntityMatches() {
        snapshot(new SearchQueryPolicy(1, 120, 20, "MIXED", Map.of()), true);
        exactFinds(123L, SearchTestEntities.TASKS, SearchTestEntities.PROJECTS, SearchTestEntities.USERS);

        SearchResult result = service.search("#123", "ALL", 10);

        // The first entity in the order of the codes that has the id.
        assertThat(result.hits()).extracting(SearchHit::entityType).containsExactly(SearchTestEntities.USERS);
        assertThat(result.foundHits()).isEqualTo(3L);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(typesenseClient);
    }

    @Test
    void rejectsInvalidInputBeforeAnySearchIo() {
        for (Object[] invalid : List.of(
                new Object[] {"a", "ALL", 10},
                new Object[] {"x".repeat(201), "ALL", 10},
                new Object[] {"valid", "OTHER", 10},
                new Object[] {"valid", "TASK", 10},
                new Object[] {"valid", "no.such", 10},
                new Object[] {"valid", "ALL", 0},
                new Object[] {"valid", "ALL", 51},
                new Object[] {"#0", "ALL", 10},
                new Object[] {"#999999999999999999999999999", "ALL", 10})) {
            assertThatThrownBy(() -> service.search((String) invalid[0], (String) invalid[1], (int) invalid[2]))
                    .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(typesenseClient, fallbackRepository);
    }

    @Test
    void unauthenticatedRequestStopsBeforeRoleOrSearchIo() {
        SecurityContext.clear();

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(roleMembershipAuthorizer, typesenseClient, fallbackRepository);
    }

    @Test
    void searchPermissionWithoutAdministratorRoleSearchesTheEntitiesTheCallerMayView() {
        // ADR-0032, 19 (question 8): the search is no administrator's privilege any more; the view right of each
        // entity and the caller's scope decide what it finds.
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("search.view", "tasks.items.view")));
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), anyList()))
                .thenReturn(List.of(group(SearchTestEntities.TASKS, 1, "11")));

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.hits()).extracting(SearchHit::entityType).containsExactly(SearchTestEntities.TASKS);
        assertThat(sentQueries("Kafka"))
                .extracting(query -> query.entity().code())
                .containsExactly(SearchTestEntities.TASKS);
        verifyNoInteractions(roleMembershipAuthorizer);
    }

    @Test
    void anEntityTheCallerMayNotViewIsAnUnknownCategory() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("search.view", "tasks.items.view")));

        assertThatThrownBy(() -> service.search("Kafka", SearchTestEntities.USERS, 10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getMessageKey()).isEqualTo("error.search.category_unknown"));
        verifyNoInteractions(typesenseClient, fallbackRepository);
    }

    @Test
    void theIndexQueryFiltersByTheCallersScopeKeys() {
        when(typesenseClient.isEnabled()).thenReturn(true);

        rule("UNITS", List.of(5L, 6L));
        service.search("units", "ALL", 10);
        rule("SELF", List.of());
        service.search("self", "ALL", 10);
        rule("UNITS", List.of());
        service.search("none", "ALL", 10);

        Map<String, String> units = filters("units");
        assertThat(units.get(SearchTestEntities.TASKS)).isEqualTo("scope_units:[5,6]");
        assertThat(units.get(SearchTestEntities.ORDERS)).isEqualTo("scope_units:[5,6]");
        // A note is its owner's alone, whatever the rule (ADR-0013, 2.5).
        assertThat(units.get(SearchTestEntities.NOTES)).isEqualTo("scope_users:=" + CALLER);
        Map<String, String> self = filters("self");
        assertThat(self.get(SearchTestEntities.USERS)).isEqualTo("scope_users:=" + CALLER);
        assertThat(self.get(SearchTestEntities.PROJECTS)).isEqualTo("scope_users:=" + CALLER);
        // A rule without a unit opens no record of a scoped entity: no query is sent for it.
        assertThat(filters("none").keySet()).containsExactly(SearchTestEntities.NOTES);
    }

    @Test
    void anIndexHitTheDatabaseNoLongerFindsInTheScopeIsNeverAnswered() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), anyList()))
                .thenReturn(List.of(group(SearchTestEntities.TASKS, 7, "11", "12", "13")));
        when(fallbackRepository.visible(any(), anyCollection(), any())).thenReturn(Set.of(12L));

        SearchResult result = service.search("Kafka", SearchTestEntities.TASKS, 10);

        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("12");
        assertThat(result.foundHits()).isEqualTo(5L);
        assertThat(result.source()).isEqualTo("TYPESENSE");
    }

    @Test
    void searchPermissionIsStillNeeded() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("tasks.items.view")));

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verifyNoInteractions(roleMembershipAuthorizer, typesenseClient, fallbackRepository);
    }

    @Test
    void thePreviewIsTheAdministratorsAndSearchesInTheirOwnScope() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("search.view", "tasks.items.view")));
        when(roleMembershipAuthorizer.hasActiveRole(CALLER, "admin")).thenReturn(false);

        assertThatThrownBy(() -> service.preview(new SearchManagementDtos.PreviewRequest("Kafka", null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getMessageKey()).isEqualTo("error.search.admin_only"));

        when(roleMembershipAuthorizer.hasActiveRole(CALLER, "admin")).thenReturn(true);
        rule("SELF", List.of());
        when(typesenseClient.isEnabled()).thenReturn(true);
        service.preview(new SearchManagementDtos.PreviewRequest("preview", SearchTestEntities.TASKS, null));
        assertThat(filters("preview")).containsEntry(SearchTestEntities.TASKS, "scope_users:=" + CALLER);
    }

    private SearchService service(SearchPolicyProvider provider) {
        return new SearchService(
                typesenseClient,
                fallbackRepository,
                new SearchAccessPolicy(roleMembershipAuthorizer),
                new SearchResultBudget(),
                provider,
                new SearchExecutionSnapshotReader(indexState),
                entities,
                new SearchScopes(new EntityScopes(SearchAccessFixtures.unrestrictedUnits()), dataScopeRules),
                new SearchFieldPolicies(entities),
                Optional.empty(),
                Optional.empty());
    }

    private Map<String, String> collections() {
        Map<String, String> collections = new LinkedHashMap<>();
        for (SearchEntity entity : entities.all()) collections.put(entity.code(), entity.collection("c_"));
        return collections;
    }

    private void snapshot(SearchQueryPolicy policy, boolean initialized) {
        when(indexState.executionSnapshot())
                .thenReturn(new SearchManagementDtos.SearchExecutionSnapshot(
                        new IndexSnapshot(UUID.randomUUID(), 1, collections(), "MIXED", initialized, false),
                        new SearchManagementDtos.SettingsSnapshot(1, policy)));
    }

    private void rule(String rule, List<Long> units) {
        when(dataScopeRules.viewer(anyLong())).thenReturn(new DataScopeRules.Viewer(rule, units));
    }

    /** The queries the search sent to the index for {@code query}, the last time. */
    private List<CollectionQuery> sentQueries(String query) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CollectionQuery>> captor = ArgumentCaptor.forClass(List.class);
        verify(typesenseClient, org.mockito.Mockito.atLeastOnce()).multiSearch(eq(query), captor.capture());
        return captor.getValue();
    }

    /** The scope filter of each entity queried for {@code query}, by its code. */
    private Map<String, String> filters(String query) {
        Map<String, String> filters = new LinkedHashMap<>();
        sentQueries(query).forEach(one -> filters.put(one.entity().code(), one.filterBy()));
        return filters;
    }

    private void fallbackFinds(String code, String... ids) {
        when(fallbackRepository.search(any(), anyString(), anyList(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    SearchEntity entity = invocation.getArgument(0);
                    return entity.code().equals(code) ? hits(code, ids) : List.of();
                });
    }

    private void exactFinds(long id, String... codes) {
        when(fallbackRepository.exact(any(), eq(id), any())).thenAnswer(invocation -> {
            SearchEntity entity = invocation.getArgument(0);
            return Arrays.asList(codes).contains(entity.code()) ? hits(entity.code(), Long.toString(id)) : List.of();
        });
    }

    private static List<FallbackHit> hits(String code, String... ids) {
        return Arrays.stream(ids)
                .map(id -> new FallbackHit(code, id, code + " " + id, "", "/" + id))
                .toList();
    }

    private static CollectionSearch group(String type, long found, String... ids) {
        return new CollectionSearch(
                type,
                Arrays.stream(ids)
                        .map(id -> new SearchHit(type, id, type + " " + id, "", "/" + id))
                        .toList(),
                found,
                1);
    }

    private static SecurityContext.KauthPrincipal principalWithPermissions(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                CALLER, "tester", "tester@example.com", 100L, false, permissions, 1L, false, 0, null);
    }

    private static SearchPolicyProvider defaultProvider() {
        var provider = new SearchPolicyProvider(
                new SearchOwnerRateLimits() {
                    @Override
                    public int userPerMinute() {
                        return 600;
                    }

                    @Override
                    public int tokenPerMinute() {
                        return 300;
                    }
                },
                mock(SearchSettingsRepository.class));
        provider.publishCommitted(new SearchManagementDtos.SettingsSnapshot(1, SearchQueryPolicy.defaults()));
        return provider;
    }
}
