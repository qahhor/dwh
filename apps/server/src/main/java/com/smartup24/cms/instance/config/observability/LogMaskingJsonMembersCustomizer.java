package com.smartup24.cms.instance.config.observability;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks every member of a structured (JSON) log line through {@link LogMasking}: the message, the MDC and key-value
 * pairs, the stack trace (plan 10/10, item 7.1). Spring Boot creates it by name from
 * {@code logging.structured.json.customizer}, before the application context exists.
 */
public class LogMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(new MaskingValueProcessor());
    }

    private static final class MaskingValueProcessor implements JsonWriter.ValueProcessor<Object> {

        @Override
        public Object processValue(JsonWriter.MemberPath path, Object value) {
            return LogMasking.maskMember(path.name(), value);
        }
    }
}
