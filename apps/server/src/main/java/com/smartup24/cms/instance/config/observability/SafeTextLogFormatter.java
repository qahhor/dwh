package com.smartup24.cms.instance.config.observability;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * Readable development logs with one physical line per event (ADR-0009). Spring Boot creates the formatter before
 * the application context; encoding covers every field and the full exception chain, not only request URIs.
 */
public final class SafeTextLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final ThrowableProxyConverter exceptions;

    public SafeTextLogFormatter(ThrowableProxyConverter exceptions) {
        this.exceptions = exceptions;
    }

    @Override
    public String format(ILoggingEvent event) {
        var text = new StringBuilder()
                .append(event.getInstant())
                .append(' ')
                .append(event.getLevel())
                .append(" [")
                .append(LogMasking.maskText(event.getThreadName()))
                .append("] ")
                .append(LogMasking.maskText(event.getLoggerName()))
                .append(" : ")
                .append(LogMasking.maskText(event.getFormattedMessage()));
        event.getMDCPropertyMap().forEach((name, value) -> appendMember(text, name, value));
        var pairs = event.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(pair -> appendMember(text, pair.key, pair.value));
        }
        if (event.getThrowableProxy() != null) {
            text.append(" | ").append(LogMasking.maskText(exceptions.convert(event)));
        }
        return escape(text.toString()) + System.lineSeparator();
    }

    private static void appendMember(StringBuilder text, String name, @Nullable Object value) {
        String label = switch (name) {
            case "traceId" -> "trace_id";
            case "spanId" -> "span_id";
            default -> name;
        };
        String masked = String.valueOf(LogMasking.maskMember(name, value));
        text.append(' ').append(LogMasking.maskText(label)).append('=').append(LogMasking.maskText(masked));
    }

    /** Escapes after formatting so arguments, metadata and throwable messages share the same boundary. */
    private static String escape(String text) {
        var escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(value) || value == '\u2028' || value == '\u2029') {
                        escaped.append("\\u").append(HexFormat.of().toHexDigits(value));
                    } else {
                        escaped.append(value);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
