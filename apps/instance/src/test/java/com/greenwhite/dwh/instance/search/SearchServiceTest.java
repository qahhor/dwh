package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.service.SearchService;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchResult;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final JdbcClient jdbcClient = Mockito.mock(JdbcClient.class);
    private final TypesenseClient typesenseClient = Mockito.mock(TypesenseClient.class);
    private final SearchService service = new SearchService(jdbcClient, typesenseClient);

    @Test
    @DisplayName("Поиск по строке короче 2 символов должен отклоняться ошибкой EMPTY_QUERY")
    void shouldRejectShortQuery() {
        assertThatThrownBy(() -> service.search("a", "ALL", 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Поисковый запрос должен содержать минимум 2 символа");
    }

    @Test
    @DisplayName("Поиск через Typesense должен успешно возвращать результаты при доступности кластера")
    void shouldSearchViaTypesenseWhenAvailable() {
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.search("Kafka", "ALL", 10)).thenReturn(List.of(
                new SearchHit("TASK", "101", "Setup Kafka CDC connector", "Data replication task", "/tasks/items/101")
        ));

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result).isNotNull();
        assertThat(result.totalHits()).isEqualTo(1);
        assertThat(result.hits().get(0).title()).isEqualTo("Setup Kafka CDC connector");
        assertThat(result.hits().get(0).targetUrl()).isEqualTo("/tasks/items/101");

        verify(typesenseClient, times(1)).search("Kafka", "ALL", 10);
    }
}
