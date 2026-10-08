package com.smartup24.cms.instance.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, items 7.1 and 7.2 on the running server: every log line of the console and of the file is one JSON
 * object, the lines of a request carry its trace_id and span_id, secrets are masked, and a recorded trace shows the
 * chain HTTP request -> JDBC statements, with the trace in the SQL comment.
 */
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
@Import(ObservabilityIntegrationTest.RecordedSpansConfiguration.class)
@TestPropertySource(
        properties = {
            "management.tracing.sampling.probability=1.0",
            "management.tracing.export.enabled=true",
            "management.tracing.export.otlp.enabled=false",
            "logging.file.name=${java.io.tmpdir}/smc-observability-test/server.log",
            "logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG"
        })
class ObservabilityIntegrationTest extends EmbeddedPostgresTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    // Fresh per run: the log file outlives a run, and a line must be found exactly once.
    private static final String TRACE_ID = randomTraceId();
    private static final String PARENT_ID = "00f067aa0ba902b7";
    private static final String LANGUAGES = "/api/v1/i18n/languages";

    @LocalServerPort
    int port;

    @Autowired
    Tracer tracer;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    RecordedSpans spans;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void forgetEarlierSpans() {
        flush();
        spans.clear();
    }

    @Test
    @DisplayName(
            "7.1: каждая строка консоли и файла — JSON; строки запроса несут его trace_id и span_id; пароль и токен скрыты")
    void everyLogLineIsJsonWithTheTraceOfItsRequest(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get(LANGUAGES, "00-" + TRACE_ID + "-" + PARENT_ID + "-01");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("traceparent"))
                .hasValueSatisfying(value -> assertThat(value)
                        .matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01")
                        .doesNotContain(PARENT_ID));

        Span job = tracer.nextSpan().name("observability-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(job)) {
            log.info("job finished user_id=42 password={} token={}", "Hunter2!", "tok-0123456789");
        } finally {
            job.end();
        }

        for (List<JsonNode> lines : List.of(jsonLines(output.getOut()), jsonLines(logFile()))) {
            JsonNode request = only(
                    lines,
                    line -> TRACE_ID.equals(line.path("trace_id").asString())
                            && line.path("message").asString().contains("GET \"" + LANGUAGES));
            assertThat(request.path("trace_id").asString()).isEqualTo(TRACE_ID);
            assertThat(request.path("span_id").asString()).matches("[0-9a-f]{16}");

            JsonNode masked = only(
                    lines,
                    line -> job.context().traceId().equals(line.path("trace_id").asString()));
            assertThat(masked.path("trace_id").asString())
                    .isEqualTo(job.context().traceId());
            assertThat(masked.path("span_id").asString())
                    .isEqualTo(job.context().spanId());
            assertThat(masked.path("message").asString()).isEqualTo("job finished user_id=42 password=*** token=***");
            assertThat(masked.path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(masked.path("service").path("name").asString()).isEqualTo("smartupcms-server");
        }
        assertThat(output.getOut()).doesNotContain("Hunter2!", "tok-0123456789");
        assertThat(Files.readString(logFile(), StandardCharsets.UTF_8)).doesNotContain("Hunter2!", "tok-0123456789");
    }

    @Test
    @DisplayName("7.2: трасса запроса показывает цепочку HTTP -> JDBC")
    void aRecordedTraceChainsTheRequestToItsStatements() throws Exception {
        String traceId = randomTraceId();
        String traceparent = "00-" + traceId + "-" + PARENT_ID + "-01";

        assertThat(get(LANGUAGES, traceparent).statusCode()).isEqualTo(200);
        flush();

        Map<String, SpanData> trace = spans.ofTrace(traceId);
        SpanData server = trace.values().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no server span in " + trace.values()));
        assertThat(server.getParentSpanId()).isEqualTo(PARENT_ID);
        List<SpanData> statements = trace.values().stream()
                .filter(span -> JdbcTracing.SPAN_NAME.equals(span.getName()))
                .toList();
        assertThat(statements).isNotEmpty().allSatisfy(statement -> {
            assertThat(statement.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(ancestors(statement, trace)).contains(server.getSpanId());
        });
    }

    @Test
    @DisplayName("7.2: SQL записанной трассы несёт комментарий traceparent со span JDBC")
    void theSqlOfARecordedTraceCarriesItsTraceparent() {
        Span parent = tracer.nextSpan().name("observability-test").start();
        String query;
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            query = jdbc.sql("select current_query()").query(String.class).single();
        } finally {
            parent.end();
        }
        flush();

        SpanData statement = spans.ofTrace(parent.context().traceId()).values().stream()
                .filter(span -> JdbcTracing.SPAN_NAME.equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(statement.getParentSpanId()).isEqualTo(parent.context().spanId());
        assertThat(query)
                .endsWith("/*traceparent='00-" + parent.context().traceId() + "-" + statement.getSpanId() + "-01'*/");
    }

    @Test
    @DisplayName("7.2: вне записанной трассы SQL не меняется")
    void theSqlOutsideATraceIsUnchanged() {
        assertThat(jdbc.sql("select current_query()").query(String.class).single())
                .isEqualTo("select current_query()");
    }

    private static String randomTraceId() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        bytes[0] |= 1;
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private HttpResponse<String> get(String path, String traceparent) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("traceparent", traceparent)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void flush() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    }

    private static Path logFile() {
        return Path.of(System.getProperty("java.io.tmpdir"), "smc-observability-test", "server.log");
    }

    private static List<JsonNode> jsonLines(Path file) throws Exception {
        return jsonLines(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Every non-empty line parsed as one JSON object; a line that is not one fails the test. */
    private static List<JsonNode> jsonLines(String text) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (RuntimeException e) {
                throw new AssertionError("not a JSON line: " + line, e);
            }
            assertThat(node.isObject()).as("a JSON object: %s", line).isTrue();
            assertThat(node.path("@timestamp").isString())
                    .as("@timestamp in %s", line)
                    .isTrue();
            lines.add(node);
        }
        assertThat(lines).isNotEmpty();
        return lines;
    }

    private static JsonNode only(List<JsonNode> lines, java.util.function.Predicate<JsonNode> filter) {
        List<JsonNode> found = lines.stream().filter(filter).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static List<String> ancestors(SpanData span, Map<String, SpanData> trace) {
        List<String> chain = new ArrayList<>();
        Optional<SpanData> current = Optional.ofNullable(trace.get(span.getParentSpanId()));
        while (current.isPresent()) {
            chain.add(current.get().getSpanId());
            current = Optional.ofNullable(trace.get(current.get().getParentSpanId()));
        }
        return chain;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordedSpansConfiguration {

        @Bean
        RecordedSpans recordedSpans() {
            return new RecordedSpans();
        }
    }

    /** The spans the server exported, kept in memory. */
    static class RecordedSpans implements SpanExporter {

        private final List<SpanData> exported = new CopyOnWriteArrayList<>();

        Map<String, SpanData> ofTrace(String traceId) {
            return exported.stream()
                    .filter(span -> span.getTraceId().equals(traceId))
                    .collect(Collectors.toMap(SpanData::getSpanId, Function.identity(), (a, b) -> a));
        }

        void clear() {
            exported.clear();
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
