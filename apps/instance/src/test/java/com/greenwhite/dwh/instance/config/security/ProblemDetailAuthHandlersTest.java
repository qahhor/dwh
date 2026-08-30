package com.greenwhite.dwh.instance.config.security;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemDetailAuthHandlersTest {

    @Test
    @DisplayName("Security handler не переписывает уже committed response")
    void committedResponseIsNeverRewritten() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        ProblemDetailAuthHandlers handlers = new ProblemDetailAuthHandlers(new ObjectMapper());
        handlers.commence(request, response, new BadCredentialsException("invalid"));
        handlers.handle(request, response, new AccessDeniedException("denied"));

        verify(response, never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
        verify(response, never()).getWriter();
    }

    @Test
    @DisplayName("Security handler не пишет CSRF и cookie values в журнал")
    void csrfAndCookieValuesAreNeverLogged() throws Exception {
        String csrfSentinel = "csrf-secret-sentinel";
        String cookieSentinel = "cookie-secret-sentinel";
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/v1/security-test");
        when(request.getHeader("X-XSRF-TOKEN")).thenReturn(csrfSentinel);
        when(request.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[]{
                new jakarta.servlet.http.Cookie("DWH_SESSION", cookieSentinel)
        });
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ProblemDetailAuthHandlers.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var expected = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected-token");
            var exception = new InvalidCsrfTokenException(expected, csrfSentinel);

            new ProblemDetailAuthHandlers(new ObjectMapper()).handle(request, response, exception);

            String logMessages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logMessages)
                    .doesNotContain(csrfSentinel)
                    .doesNotContain(cookieSentinel)
                    .contains("csrfHeaderPresent=true")
                    .contains("cookieNames=[DWH_SESSION]");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
