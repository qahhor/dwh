package com.smartup24.cms.instance.common.entity.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Plan 10/10, item 3.4: every report handler declares the status it answers. */
class EntityReportControllerStatusTest {
    @Test
    void everyHandlerDeclaresItsStatus() {
        var handlers = Arrays.stream(EntityReportController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .toList();
        assertThat(handlers).hasSize(4);
        for (Method handler : handlers) {
            ResponseStatus status = handler.getAnnotation(ResponseStatus.class);
            assertThat(status).as(handler.getName()).isNotNull();
            assertThat(status.value()).as(handler.getName()).isEqualTo(HttpStatus.OK);
        }
    }
}
