package com.smartup24.cms.instance.config.web;

import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.common.web.Revisions;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Every answer that is a {@link Revisioned} record carries its revision as {@code ETag} (plan 10/10, item 3.6), the
 * value a following change sends back in {@code If-Match}.
 */
@RestControllerAdvice
public class RevisionETagAdvice implements ResponseBodyAdvice<Object> {

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
        if (body instanceof Revisioned record) {
            response.getHeaders().setETag(Revisions.etag(record.revision()));
        }
        return body;
    }
}
