package com.smartup24.cms.instance.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** ADR-0009: untrusted text cannot introduce another physical log record in either supported console format. */
class LogInjectionTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodedQueryValueCannotForgeALogLine(boolean dev) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.setContext(logger.getLoggerContext());
        captured.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(captured);
        try (var output = new ConfiguredLogOutput(dev, "console")) {
            var firewall = new StrictHttpFirewall();
            var mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                    .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                    .addFilter((request, response, chain) ->
                            chain.doFilter(firewall.getFirewalledRequest((HttpServletRequest) request), response))
                    .build();
            mvc.perform(get("/log-probe").param("limit", "7\r\nFORGED_EVENT user_id=999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.messageKey").value("error.request_param_invalid"));
            mvc.perform(get("/log-probe").param("limit", "7")).andExpect(status().isOk());
            assertThat(captured.list).hasSize(1);
            String line = output.encode(captured.list.getFirst());
            assertThat(line).contains("argument_type_mismatch", "parameter=limit", "FORGED_EVENT");
            assertOneRecord(line);
        } finally {
            logger.detachAppender(captured);
            logger.setAdditive(additive);
            captured.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unicodeSeparatorsAndExceptionChainsStayInOneRecord(boolean dev) throws Exception {
        var failure = new IllegalStateException("outer\r\nFORGED_EXCEPTION");
        failure.initCause(new IllegalArgumentException("cause\u2028FORGED_CAUSE"));
        failure.addSuppressed(new IllegalArgumentException("suppressed\u2029FORGED_SUPPRESSED"));
        var event = event("message {}", new Object[] {"value\u0085FORGED_VALUE\u001b[2J"}, failure);
        try (var output = new ConfiguredLogOutput(dev, "console")) {
            String line = output.encode(event);
            assertOneRecord(line);
            assertThat(line).doesNotContain("\u001b");
            assertThat(line)
                    .contains("IllegalStateException", "IllegalArgumentException", "FORGED_CAUSE", "FORGED_SUPPRESSED");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legitimateDiagnosticsAndMaskingSurvive(boolean dev) throws Exception {
        var event = event("job_done user_id={} password={}", new Object[] {42, "synthetic-secret"}, null);
        event.setMDCPropertyMap(Map.of("traceId", "4bf92f3577b34da6a3ce929d0e0e4736", "spanId", "00f067aa0ba902b7"));
        event.addKeyValuePair(new KeyValuePair("note", "ordinary Unicode: \u041f\u0440\u0438\u0432\u0435\u0442"));
        try (var output = new ConfiguredLogOutput(dev, "console")) {
            String line = output.encode(event);
            assertOneRecord(line);
            assertThat(line)
                    .contains(
                            "job_done user_id=42 password=***", "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
            assertThat(line).doesNotContain("synthetic-secret");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fileRemainsStructuredAndPreservesTheMessage(boolean dev) throws Exception {
        var event = event("request {}", new Object[] {"/items?name=ordinary%20text\r\nnext"}, null);
        try (var output = new ConfiguredLogOutput(dev, "file")) {
            String line = output.encode(event);
            assertOneRecord(line);
            var json = new ObjectMapper().readTree(line);
            assertThat(json.path("message").asString()).isEqualTo("request /items?name=ordinary%20text\r\nnext");
            assertThat(json.path("log").path("level").asString()).isEqualTo("WARN");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataAndObjectArgumentsCannotBypassEncoding(boolean dev) throws Exception {
        String payload = "text\r\n\t\b\f\u0000\u000b\u007f\u0085\u009b\u2028\u2029\\literal";
        var event = event("argument={}", new Object[] {new StringBuilder(payload)}, null);
        event.setLoggerName("logger-" + payload);
        event.setThreadName("thread-" + payload);
        event.setMDCPropertyMap(Map.of("note", payload, "password", "synthetic-mdc-secret"));
        event.addKeyValuePair(new KeyValuePair("extra", new StringBuilder(payload)));
        event.addKeyValuePair(new KeyValuePair("token", "synthetic-kv-secret"));
        event.addKeyValuePair(new KeyValuePair("object", Map.of("password", "synthetic-object-secret")));
        try (var output = new ConfiguredLogOutput(dev, "console")) {
            String line = output.encode(event);
            assertOneRecord(line);
            assertThat(line)
                    .doesNotContain("\u0000", "\u007f", "\u009b", "synthetic-mdc-secret", "synthetic-kv-secret");
            assertThat(line).doesNotContain("synthetic-object-secret");
            assertThat(line).contains("logger-", "thread-", "argument=", "extra", "literal");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void throwableSecretsAreMaskedWithoutDroppingTheStack(boolean dev) throws Exception {
        var failure = new IllegalArgumentException("password=synthetic-outer-secret");
        failure.initCause(new IllegalStateException("token=synthetic-cause-secret"));
        failure.addSuppressed(new IllegalStateException("api_key=synthetic-suppressed-secret"));
        try (var output = new ConfiguredLogOutput(dev, "console")) {
            String line = output.encode(event("request failed", new Object[0], failure));
            assertOneRecord(line);
            assertThat(line).contains("IllegalArgumentException", "Caused by", "Suppressed", "LogInjectionTest");
            assertThat(line)
                    .doesNotContain("synthetic-outer-secret", "synthetic-cause-secret", "synthetic-suppressed-secret");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cookieMaskingCannotConsumeAdjacentDiagnosticFields(boolean dev) throws Exception {
        var failure = new IllegalArgumentException("ordinary failure");
        var event = event("invalid value={}", new Object[] {"Cookie: synthetic-cookie"}, failure);
        event.setMDCPropertyMap(Map.of("traceId", "4bf92f3577b34da6a3ce929d0e0e4736", "spanId", "00f067aa0ba902b7"));
        event.addKeyValuePair(new KeyValuePair("note", "Cookie: synthetic-kv-cookie"));
        event.addKeyValuePair(new KeyValuePair("ordinary", "kept-after-cookie"));
        try (var output = new ConfiguredLogOutput(dev, "console")) {
            String line = output.encode(event);
            assertOneRecord(line);
            assertThat(line)
                    .contains(
                            "4bf92f3577b34da6a3ce929d0e0e4736",
                            "00f067aa0ba902b7",
                            "IllegalArgumentException",
                            "ordinary failure",
                            "kept-after-cookie");
            assertThat(line).doesNotContain("synthetic-cookie", "synthetic-kv-cookie");
        }
    }

    private static LoggingEvent event(String message, Object[] arguments, Throwable failure) {
        var context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        return new LoggingEvent(
                LogInjectionTest.class.getName(),
                context.getLogger("request-probe"),
                Level.WARN,
                message,
                failure,
                arguments);
    }

    private static void assertOneRecord(String line) {
        assertThat(line).endsWith("\n");
        String record = line.substring(0, line.length() - (line.endsWith("\r\n") ? 2 : 1));
        assertThat(record).doesNotContain("\r", "\n", "\u000b", "\f", "\u0085", "\u2028", "\u2029");
    }

    @RestController
    static class ProbeController {
        @GetMapping("/log-probe")
        int number(@RequestParam int limit) {
            return limit;
        }
    }
}
