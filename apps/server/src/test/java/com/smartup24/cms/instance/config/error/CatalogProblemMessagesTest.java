package com.smartup24.cms.instance.config.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdSettingService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/** Plan 10/10, item 3.1: the text of an error is rendered in the request's language and never fails in turn. */
class CatalogProblemMessagesTest {

    private static final String KEY = "error.request_param_missing";

    private final MdI18nCatalog catalog = new MdI18nCatalog(new ObjectMapper());
    private final MdI18nService i18n = mock(MdI18nService.class);
    private final MdSettingService settings = mock(MdSettingService.class);
    private final CatalogProblemMessages messages = new CatalogProblemMessages(i18n, settings);

    @BeforeEach
    void languages() {
        when(i18n.isActiveLanguage(anyString())).thenAnswer(call -> "ru en uz".contains(call.<String>getArgument(0)));
        when(i18n.effectiveDictionary(anyString())).thenAnswer(call -> catalog.bundled(call.getArgument(0)));
        when(settings.getInstanceSettings()).thenReturn(Map.of());
    }

    @Test
    @DisplayName("the first language of Accept-Language is used when it is active, with the parameters filled")
    void rendersInTheRequestedLanguage() {
        assertThat(messages.render(request("en-US,en;q=0.9,ru;q=0.5"), KEY, Map.of("name", "limit")))
                .isEqualTo("Missing request parameter: limit");
        assertThat(messages.render(request("uz"), KEY, Map.of("name", "limit")))
                .isEqualTo("Majburiy so'rov parametri yo'q: limit");
    }

    @Test
    @DisplayName("an inactive or missing language falls back to the system language, then to Russian")
    void fallsBackToSystemThenRussian() {
        assertThat(messages.render(request("fr"), KEY, Map.of("name", "limit")))
                .isEqualTo("Отсутствует обязательный параметр запроса: limit");

        when(settings.getInstanceSettings()).thenReturn(Map.of(CatalogProblemMessages.SYSTEM_LANGUAGE, "en"));
        assertThat(messages.render(request(null), KEY, Map.of("name", "limit")))
                .isEqualTo("Missing request parameter: limit");
    }

    @Test
    @DisplayName("without the database the packaged catalog answers; an unknown key comes back as it is")
    void neverFailsInTurn() {
        when(i18n.effectiveDictionary(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        when(i18n.isActiveLanguage(anyString())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(messages.render(request("en"), KEY, Map.of("name", "limit")))
                .isEqualTo("Missing request parameter: limit");
        assertThat(messages.render(request("en"), "error.no_such_key", Map.of()))
                .isEqualTo("error.no_such_key");
    }

    private static MockHttpServletRequest request(String acceptLanguage) {
        var request = new MockHttpServletRequest("GET", "/api/v1/x");
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        return request;
    }
}
