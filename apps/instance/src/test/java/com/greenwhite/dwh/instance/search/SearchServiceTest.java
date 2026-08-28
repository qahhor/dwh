package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchServiceTest {

    private final JdbcClient jdbcClient = Mockito.mock(JdbcClient.class);
    private final SearchService service = new SearchService(jdbcClient);

    @Test
    @DisplayName("Поиск по строке короче 2 символов должен отклоняться ошибкой EMPTY_QUERY")
    void shouldRejectShortQuery() {
        assertThatThrownBy(() -> service.search("a", "ALL", 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Поисковый запрос должен содержать минимум 2 символа");
    }
}
