package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.RoleMembershipAuthorizer;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.search.service.SearchService;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.service.SearchService.SearchResult;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final JdbcClient jdbcClient = Mockito.mock(JdbcClient.class);
    private final TypesenseClient typesenseClient = Mockito.mock(TypesenseClient.class);
    private final RoleMembershipAuthorizer roleMembershipAuthorizer = Mockito.mock(RoleMembershipAuthorizer.class);
    private final SearchService service = new SearchService(jdbcClient, typesenseClient, roleMembershipAuthorizer);

    @BeforeEach
    void authenticateAsAdministrator() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("*.*")));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

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

    @Test
    @DisplayName("Глобальный поиск должен быть недоступен без неограниченного административного scope")
    void shouldRejectSearchWithoutUnrestrictedScope() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("platform.search.view")));
        when(roleMembershipAuthorizer.hasActiveRole(42L, "admin")).thenReturn(false);

        assertThatThrownBy(() -> service.search("Kafka", "ALL", 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Глобальный поиск доступен только администраторам");

        verifyNoInteractions(typesenseClient, jdbcClient);
    }

    @Test
    @DisplayName("Администратор из системной роли может искать без синтетического wildcard-разрешения")
    void shouldAllowAdministratorRoleWithoutWildcardPermission() {
        SecurityContext.setPrincipal(principalWithPermissions(Set.of("platform.search.view")));
        when(roleMembershipAuthorizer.hasActiveRole(42L, "admin")).thenReturn(true);
        when(typesenseClient.isEnabled()).thenReturn(true);
        when(typesenseClient.search("Kafka", "ALL", 10)).thenReturn(List.of());

        SearchResult result = service.search("Kafka", "ALL", 10);

        assertThat(result.totalHits()).isZero();
        verify(typesenseClient).search("Kafka", "ALL", 10);
    }

    private SecurityContext.KauthPrincipal principalWithPermissions(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                42L,
                "tester",
                "tester@example.com",
                100L,
                false,
                permissions,
                1L
        );
    }
}
