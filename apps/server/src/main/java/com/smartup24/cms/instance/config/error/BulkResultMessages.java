package com.smartup24.cms.instance.config.error;

import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Renders the reason of each failed record of a bulk action in the request's language, as the error handler renders
 * {@code detail} (plan 10/10, item 3.1). {@code BulkRunner} lives in {@code common}, which cannot see the catalogs, so
 * it hands over the key and its parameters and the text is filled in here, on the way out.
 */
@RestControllerAdvice
public class BulkResultMessages implements ResponseBodyAdvice<Object> {

    private final ProblemMessages messages;

    @Autowired
    public BulkResultMessages(ObjectProvider<ProblemMessages> messages) {
        this(messages.getIfAvailable(PackagedProblemMessages::new));
    }

    public BulkResultMessages(ProblemMessages messages) {
        this.messages = messages;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof BulkResult result && request instanceof ServletServerHttpRequest servlet) {
            return result.withMessages((key, params) -> messages.render(servlet.getServletRequest(), key, params));
        }
        return body;
    }
}
