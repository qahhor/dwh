package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.RoleMembershipAuthorizer;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository.IndexSnapshot;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository.FallbackGroup;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository.FallbackHit;
import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository.FallbackSearch;
import com.greenwhite.dwh.instance.search.service.SearchAccessPolicy;
import com.greenwhite.dwh.instance.search.service.SearchQueryPolicy;
import com.greenwhite.dwh.instance.search.service.SearchResultBudget;
import com.greenwhite.dwh.instance.search.service.SearchService;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchResult;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient.CollectionSearch;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private final TypesenseClient typesenseClient = mock(TypesenseClient.class);
    private final SearchFallbackRepository fallbackRepository = mock(SearchFallbackRepository.class);
    private final RoleMembershipAuthorizer roleMembershipAuthorizer = mock(RoleMembershipAuthorizer.class);
    private final SearchIndexStateRepository indexState = mock(SearchIndexStateRepository.class);
    private final SearchService service = new SearchService(typesenseClient, fallbackRepository,
            new SearchAccessPolicy(roleMembershipAuthorizer), new SearchResultBudget(), indexState);

    @BeforeEach
    void authenticateWithLegacyWildcard() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("*.*")));
        when(indexState.snapshot()).thenReturn(new IndexSnapshot(java.util.UUID.randomUUID(), 1,
                Map.of("TASK", "tasks", "PROJECT", "projects", "USER", "users"), "MIXED", true, false));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void indexStateReadFailureStillUsesStructuredPostgresFallback() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(indexState.snapshot()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("state unavailable"));
        when(fallbackRepository.search("Kafka", "ALL", 10)).thenReturn(fallback());
        assertThat(service.search("Kafka", "ALL", 10).degraded()).isTrue();
    }

    @Test
    void typesenseMetadataDistinguishesReturnedHitsFromFoundHits() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), eq("ALL"), eq(4), anyMap(), any()))
                .thenReturn(List.of(
                        group("TASK", 7, "11", "12", "13"),
                        group("PROJECT", 4, "21", "22"),
                        group("USER", 1, "31")));

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
        when(typesenseClient.multiSearch(eq("none"), eq("TASK"), eq(10), anyMap(), any()))
                .thenReturn(List.of(group("TASK", 0)));

        SearchResult result = service.search("none", "task", 50);

        assertThat(result.totalHits()).isZero();
        assertThat(result.foundHits()).isZero();
        assertThat(result.source()).isEqualTo("TYPESENSE");
        verify(typesenseClient).multiSearch(eq("none"), eq("TASK"), eq(10), anyMap(), any());
        verifyNoInteractions(fallbackRepository);
    }

    @Test
    void failedTypesenseUsesMarkedPostgresFallback() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), eq("ALL"), eq(10), anyMap(), any()))
                .thenThrow(TypesenseException.unavailable());
        when(fallbackRepository.search("Kafka", "ALL", 10)).thenReturn(fallback(
                fallbackGroup("TASK", false, "11")));

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("11");
        assertThat(result.foundHits()).isNull();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void disabledTypesenseUsesTheSameMarkedFallback() {
        when(typesenseClient.isEnabled()).thenReturn(false);
        when(fallbackRepository.search("Kafka", "ALL", 10)).thenReturn(fallback());

        SearchResult result = service.search("Kafka", null, 10);

        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
        verify(typesenseClient, never()).multiSearch(any(), any(), anyInt(), anyMap(), any());
    }

    @Test
    void uninitializedCollectionResolutionUsesTheSameMarkedFallback() {
        SearchService uninitialized = new SearchServiceWithSeams(typesenseClient, fallbackRepository,
                new SearchAccessPolicy(roleMembershipAuthorizer), new SearchResultBudget(),
                SearchQueryPolicy.defaults(), Map.of());
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(fallbackRepository.search("Kafka", "ALL", 10)).thenReturn(fallback());

        SearchResult result = uninitialized.search("Kafka", "ALL", 10);

        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isTrue();
        verify(typesenseClient, never()).multiSearch(any(), any(), anyInt(), anyMap(), any());
    }

    @Test
    void fallbackFailurePropagatesAsStructuredServiceUnavailable() {
        when(typesenseClient.isEnabled()).thenReturn(false);
        when(fallbackRepository.search("Kafka", "ALL", 10)).thenThrow(new IllegalStateException("database detail"));

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain("database detail", "Kafka");
                });
    }

    @Test
    void exactPositiveIdUsesParameterizedRepositoryLookupWithoutTypesense() {
        when(fallbackRepository.searchExact(123L, "TASK")).thenReturn(fallback(
                fallbackGroup("TASK", false, "123")));

        SearchResult result = service.search("#123", "TASK", 10);

        assertThat(result.hits()).extracting(SearchHit::id).containsExactly("123");
        assertThat(result.foundHits()).isEqualTo(1L);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(typesenseClient);
    }

    @Test
    void exactLookupReportsKnownCountAndHasMoreWhenPolicyCapsMultipleEntityMatches() {
        SearchQueryPolicy oneResultPolicy = new SearchQueryPolicy(
                1, 120, 20, "MIXED", SearchQueryPolicy.defaults().fields());
        SearchService capped = new SearchServiceWithSeams(typesenseClient, fallbackRepository,
                new SearchAccessPolicy(roleMembershipAuthorizer), new SearchResultBudget(), oneResultPolicy,
                Map.of("TASK", "tasks", "PROJECT", "projects", "USER", "users"));
        when(fallbackRepository.searchExact(123L, "ALL")).thenReturn(fallback(
                fallbackGroup("TASK", false, "123"),
                fallbackGroup("PROJECT", false, "123"),
                fallbackGroup("USER", false, "123")));

        SearchResult result = capped.search("#123", "ALL", 10);

        assertThat(result.hits()).extracting(SearchHit::entityType).containsExactly("TASK");
        assertThat(result.foundHits()).isEqualTo(3L);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.source()).isEqualTo("POSTGRES");
        assertThat(result.degraded()).isFalse();
        verifyNoInteractions(typesenseClient);
    }

    @Test
    void rejectsInvalidInputBeforeAnySearchIo() {
        for (Object[] invalid : List.of(
                new Object[]{"a", "ALL", 10},
                new Object[]{"x".repeat(201), "ALL", 10},
                new Object[]{"valid", "OTHER", 10},
                new Object[]{"valid", "ALL", 0},
                new Object[]{"valid", "ALL", 51},
                new Object[]{"#0", "ALL", 10},
                new Object[]{"#999999999999999999999999999", "ALL", 10})) {
            assertThatThrownBy(() -> service.search((String) invalid[0], (String) invalid[1], (int) invalid[2]))
                    .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(typesenseClient, fallbackRepository);
    }

    @Test
    void unauthenticatedRequestStopsBeforeRoleOrSearchIo() {
        SecurityContext.clear();

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(roleMembershipAuthorizer, typesenseClient, fallbackRepository);
    }

    @Test
    void delegatedSearchPermissionAloneDoesNotExpandLegacyScope() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("platform.search.view")));
        when(roleMembershipAuthorizer.hasActiveRole(42L, "admin")).thenReturn(false);

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(typesenseClient, fallbackRepository);
    }

    @Test
    void activeAdministratorWithSearchPermissionCanSearch() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("platform.search.view")));
        when(roleMembershipAuthorizer.hasActiveRole(42L, "admin")).thenReturn(true);
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.multiSearch(eq("Kafka"), eq("ALL"), eq(10), anyMap(), any()))
                .thenReturn(List.of(group("TASK", 0), group("PROJECT", 0), group("USER", 0)));

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.totalHits()).isZero();
        assertThat(result.source()).isEqualTo("TYPESENSE");
    }

    @Test
    void activeAdministratorStillNeedsSearchPermission() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("tasks.items.view")));

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verifyNoInteractions(roleMembershipAuthorizer, typesenseClient, fallbackRepository);
    }

    private static CollectionSearch group(String type, long found, String... ids) {
        return new CollectionSearch(type, java.util.Arrays.stream(ids)
                .map(id -> new SearchHit(type, id, type + " " + id, "", "/" + id)).toList(), found, 1);
    }

    private static FallbackGroup fallbackGroup(String type, boolean hasMore, String... ids) {
        return new FallbackGroup(type, java.util.Arrays.stream(ids)
                .map(id -> new FallbackHit(type, id, type + " " + id, "", "/" + id)).toList(), hasMore);
    }

    private static FallbackSearch fallback(FallbackGroup... groups) {
        return new FallbackSearch(List.of(groups));
    }

    private static SecurityContext.KauthPrincipal principalWithPermissions(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                42L, "tester", "tester@example.com", 100L, false, permissions,
                1L, false, 0, null);
    }

    private static final class SearchServiceWithSeams extends SearchService {
        private SearchServiceWithSeams(TypesenseClient typesenseClient, SearchFallbackRepository fallbackRepository,
                                       SearchAccessPolicy accessPolicy, SearchResultBudget resultBudget,
                                       SearchQueryPolicy queryPolicy, Map<String, String> collections) {
            super(typesenseClient, fallbackRepository, accessPolicy, resultBudget, queryPolicy,
                    () -> new IndexSnapshot(java.util.UUID.randomUUID(), 1, collections, "MIXED", !collections.isEmpty(), false));
        }
    }
}
