package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchAccessPolicy;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchFieldPolicies;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchResultBudget;
import com.smartup24.cms.instance.search.service.SearchScopes;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.annotation.Transactional;

class SearchServiceWiringTest {

    @Test
    void springSelectsTheProductionConstructorWhenTheExtensionSeamIsPresent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TypesenseSearch.class, () -> mock(TypesenseSearch.class));
            context.registerBean(SearchFallbackRepository.class, () -> mock(SearchFallbackRepository.class));
            context.registerBean(SearchIndexStateRepository.class, () -> mock(SearchIndexStateRepository.class));
            context.registerBean(SearchSettingsRepository.class, () -> mock(SearchSettingsRepository.class));
            context.registerBean(SearchExecutionSnapshotReader.class);
            context.registerBean(SearchAccessPolicy.class, () -> mock(SearchAccessPolicy.class));
            context.registerBean(SearchResultBudget.class, SearchResultBudget::new);
            context.registerBean(SearchOwnerRateLimits.class, () -> new SearchOwnerRateLimits() {
                @Override
                public int userPerMinute() {
                    return 600;
                }

                @Override
                public int tokenPerMinute() {
                    return 300;
                }
            });
            context.registerBean(SearchPolicyProvider.class);
            context.registerBean(SearchEntities.class, SearchTestEntities::unscoped);
            context.registerBean(SearchScopes.class, SearchAccessFixtures::unrestrictedScopes);
            context.registerBean(SearchFieldPolicies.class);
            context.registerBean(SearchService.class);

            context.refresh();

            assertThat(context.getBean(SearchService.class)).isNotNull();
        }
    }

    @Test
    void fallbackOwnsTheBoundedReadOnlyTransactionAndSearchServiceDoesNot() throws Exception {
        Transactional ordinary = SearchFallbackRepository.class
                .getMethod(
                        "search", SearchEntity.class, String.class, List.class, int.class, QueryPlan.SqlFragment.class)
                .getAnnotation(Transactional.class);
        Transactional exact = SearchFallbackRepository.class
                .getMethod("exact", SearchEntity.class, long.class, QueryPlan.SqlFragment.class)
                .getAnnotation(Transactional.class);
        Transactional check = SearchFallbackRepository.class
                .getMethod("visible", SearchEntity.class, Collection.class, QueryPlan.SqlFragment.class)
                .getAnnotation(Transactional.class);

        for (Transactional bounded : List.of(ordinary, exact, check)) {
            assertThat(bounded).isNotNull();
            assertThat(bounded.readOnly()).isTrue();
            assertThat(bounded.timeout()).isEqualTo(2);
        }
        assertThat(SearchService.class
                        .getMethod("search", String.class, String.class, int.class)
                        .getAnnotation(Transactional.class))
                .isNull();
    }
}
