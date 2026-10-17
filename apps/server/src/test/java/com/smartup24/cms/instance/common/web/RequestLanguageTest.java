package com.smartup24.cms.instance.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The language names are written in (ADR-0041): the work's, else the request's, else Russian. */
class RequestLanguageTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theRequestNamesItsLanguageAndAnUnknownOneIsRussian() {
        assertThat(RequestLanguage.current()).isEqualTo("ru");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "uz-Latn-UZ,ru;q=0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(RequestLanguage.current()).isEqualTo("uz");
        MockHttpServletRequest french = new MockHttpServletRequest();
        french.addHeader("Accept-Language", "fr");
        assertThat(RequestLanguage.of(french)).isEqualTo("ru");
        assertThat(RequestLanguage.of(new MockHttpServletRequest())).isEqualTo("ru");
    }

    @Test
    void aJobNamesItsLanguageForItsWorkOnlyAndNestedWorkRestoresIt() {
        String[] seen = new String[3];
        RequestLanguage.run("en", () -> {
            seen[0] = RequestLanguage.current();
            seen[1] = RequestLanguage.with("UZ", RequestLanguage::current);
            seen[2] = RequestLanguage.current();
        });
        assertThat(seen).containsExactly("en", "uz", "en");
        assertThat(RequestLanguage.with(null, RequestLanguage::current)).isEqualTo("ru");
        assertThat(RequestLanguage.current()).isEqualTo("ru");
    }
}
