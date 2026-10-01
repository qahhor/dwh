package com.smartup24.cms.instance.common.web;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriTemplate;

/**
 * A resource the request created (plan 10/10, item 3.4): {@code 201 Created} with {@code Location} naming it by its
 * current path, whichever alias the request came through. The handler also carries
 * {@code @ResponseStatus(HttpStatus.CREATED)} so the API description states the status.
 */
public final class Created {

    private Created() {}

    /** {@code Created.at("/api/v1/tasks/{id}", task.id(), task)}: the variables fill the template in order. */
    public static <T> ResponseEntity<T> at(String pathTemplate, Object id, T body) {
        return ResponseEntity.created(location(pathTemplate, id)).body(body);
    }

    /** A template with several variables, such as a comment under its task. */
    public static <T> ResponseEntity<T> at(String pathTemplate, Object[] ids, T body) {
        return ResponseEntity.created(location(pathTemplate, ids)).body(body);
    }

    static URI location(String pathTemplate, Object... ids) {
        return new UriTemplate(pathTemplate).expand(ids);
    }
}
