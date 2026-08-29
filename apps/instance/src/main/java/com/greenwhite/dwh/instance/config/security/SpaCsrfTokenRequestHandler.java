package com.greenwhite.dwh.instance.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Документированный Spring Security паттерн CSRF для SPA:
 * - значение из заголовка (SPA шлёт cookie как есть) резолвится plain-обработчиком;
 * - значение из параметра формы — Xor-обработчиком (BREACH-защита);
 * - csrfToken.get() форсирует выдачу cookie XSRF-TOKEN на каждом ответе,
 *   чтобы SPA всегда имела актуальный токен.
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.delegate.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        if (!StringUtils.hasText(headerValue)) {
            headerValue = request.getHeader("X-XSRF-TOKEN");
        }
        if (!StringUtils.hasText(headerValue)) {
            headerValue = request.getHeader("X-CSRF-TOKEN");
        }
        return StringUtils.hasText(headerValue)
                ? super.resolveCsrfTokenValue(request, csrfToken)
                : this.delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}

