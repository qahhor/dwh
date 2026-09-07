package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.repository.SearchFallbackRepository;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository;
import com.greenwhite.dwh.instance.search.service.SearchAccessPolicy;
import com.greenwhite.dwh.instance.search.service.SearchPolicyProvider;
import com.greenwhite.dwh.instance.search.service.SearchResultBudget;
import com.greenwhite.dwh.instance.search.service.SearchService;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchServiceWiringTest {

    @Test
    void springSelectsTheProductionConstructorWhenTheExtensionSeamIsPresent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TypesenseClient.class, () -> mock(TypesenseClient.class));
            context.registerBean(SearchFallbackRepository.class, () -> mock(SearchFallbackRepository.class));
            context.registerBean(SearchIndexStateRepository.class, () -> mock(SearchIndexStateRepository.class));
            context.registerBean(com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository.class,
                    () -> mock(com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository.class));
            context.registerBean(com.greenwhite.dwh.instance.search.service.SearchExecutionSnapshotReader.class);
            context.registerBean(SearchAccessPolicy.class, () -> mock(SearchAccessPolicy.class));
            context.registerBean(SearchResultBudget.class, SearchResultBudget::new);
            context.registerBean(SearchOwnerRateLimits.class, () -> new SearchOwnerRateLimits() {
                @Override public int userPerMinute() { return 600; }
                @Override public int tokenPerMinute() { return 300; }
            });
            context.registerBean(SearchPolicyProvider.class);
            context.registerBean(SearchService.class);

            context.refresh();

            assertThat(context.getBean(SearchService.class)).isNotNull();
        }
    }

    @Test
    void fallbackOwnsTheBoundedReadOnlyTransactionAndSearchServiceDoesNot() throws Exception {
        Transactional ordinary = SearchFallbackRepository.class
                .getMethod("search", String.class, String.class, int.class)
                .getAnnotation(Transactional.class);
        Transactional exact = SearchFallbackRepository.class
                .getMethod("searchExact", long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(ordinary).isNotNull();
        assertThat(ordinary.readOnly()).isTrue();
        assertThat(ordinary.timeout()).isEqualTo(2);
        assertThat(exact).isNotNull();
        assertThat(exact.readOnly()).isTrue();
        assertThat(exact.timeout()).isEqualTo(2);
        assertThat(SearchService.class.getMethod("search", String.class, String.class, int.class)
                .getAnnotation(Transactional.class)).isNull();
    }
}
