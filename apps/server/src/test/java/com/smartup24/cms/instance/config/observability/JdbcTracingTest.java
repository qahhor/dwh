package com.smartup24.cms.instance.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/** Plan 10/10, item 7.2: JDBC spans and the traceparent SQL comment, on a plain JDBC driver. */
class JdbcTracingTest {

    private final List<SpanData> exported = new CopyOnWriteArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
            .addSpanProcessor(SimpleSpanProcessor.create(new Recorder(exported)))
            .build();
    private final Tracer tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});

    @AfterEach
    void close() {
        provider.close();
    }

    @Test
    @DisplayName("7.2: операторы внутри трассы получают комментарий traceparent и span JDBC до закрытия")
    void statementsInsideATraceAreCommentedAndTraced() throws Exception {
        List<String> sql = new CopyOnWriteArrayList<>();
        DataSource dataSource = traced(recording(h2(), sql));
        Span parent = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent);
                Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.execute("select 1")).isTrue();
                statement.addBatch("select 2");
                assertThat(exported).isEmpty();
            }
            try (var prepared = connection.prepareStatement("select ?")) {
                prepared.setInt(1, 3);
                assertThat(prepared.execute()).isTrue();
            }
            Object same = connection;
            assertThat(connection.equals(same)).isTrue();
            assertThat(connection.hashCode()).isEqualTo(System.identityHashCode(connection));
            assertThat(connection).isNotEqualTo(dataSource);
        } finally {
            parent.end();
        }

        assertThat(exported).hasSize(3);
        List<SpanData> statements = exported.stream()
                .filter(span -> JdbcTracing.SPAN_NAME.equals(span.getName()))
                .toList();
        assertThat(statements).hasSize(2).allSatisfy(span -> {
            assertThat(span.getParentSpanId()).isEqualTo(parent.context().spanId());
            assertThat(span.getAttributes().asMap().toString()).contains("postgresql", "dataSource");
        });
        String trace = "\n/*traceparent='00-" + parent.context().traceId() + "-";
        String plain = trace + statements.get(0).getSpanId() + "-01'*/";
        String prepared = trace + statements.get(1).getSpanId() + "-01'*/";
        assertThat(sql).containsExactly("select 1" + plain, "select 2" + plain, "select ?" + prepared);
    }

    @Test
    @DisplayName("7.2: ошибка подготовки или выполнения отмечается на span")
    void aFailureIsRecordedOnTheSpan() throws Exception {
        DataSource dataSource = traced(h2());
        Span parent = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent);
                Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> connection.prepareStatement("select no_such_column"))
                    .isInstanceOf(SQLException.class);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("select no_such_column"))
                        .isInstanceOf(SQLException.class);
            }
        } finally {
            parent.end();
        }

        assertThat(exported.stream().filter(span -> JdbcTracing.SPAN_NAME.equals(span.getName())))
                .hasSize(2)
                .allSatisfy(span -> assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
    }

    @Test
    @DisplayName("7.2: забытый оператор закрывает свой span вместе с соединением")
    void aForgottenStatementEndsWithItsConnection() throws Exception {
        DataSource dataSource = traced(h2());
        Span parent = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            Connection connection = dataSource.getConnection();
            connection.prepareStatement("select 1");
            assertThat(exported).isEmpty();
            connection.close();
        } finally {
            parent.end();
        }
        assertThat(exported.stream().filter(span -> JdbcTracing.SPAN_NAME.equals(span.getName())))
                .hasSize(1);
    }

    @Test
    @DisplayName("7.2: вне трассы и в несэмплированной трассе соединение не меняет SQL и не пишет span")
    void nothingChangesOutsideASampledTrace() throws Exception {
        DataSource dataSource = traced(h2());
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("select 1");
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
        }
        assertThat(exported).isEmpty();
    }

    @Test
    @DisplayName("7.2: при вероятности 0 источники данных не оборачиваются; при записи — оборачиваются один раз")
    void dataSourcesAreWrappedOnlyWhileTracingRecords() throws Exception {
        DataSource plain = h2();
        BeanPostProcessor off = processor(new MockEnvironment());
        assertThat(off.postProcessAfterInitialization(plain, "dataSource")).isSameAs(plain);

        BeanPostProcessor on =
                processor(new MockEnvironment().withProperty(ObservabilityConfiguration.SAMPLING_PROBABILITY, "0.1"));
        Object wrapped = on.postProcessAfterInitialization(plain, "dataSource");
        assertThat(wrapped).isInstanceOf(TracingDataSource.class);
        assertThat(on.postProcessAfterInitialization(wrapped, "dataSource")).isSameAs(wrapped);
        assertThat(on.postProcessAfterInitialization("not a data source", "other"))
                .isEqualTo("not a data source");

        BeanPostProcessor disabled = processor(new MockEnvironment()
                .withProperty(ObservabilityConfiguration.SAMPLING_PROBABILITY, "1.0")
                .withProperty(ObservabilityConfiguration.TRACING_ENABLED, "false"));
        assertThat(disabled.postProcessAfterInitialization(plain, "dataSource")).isSameAs(plain);
        ((TracingDataSource) wrapped).close();
    }

    @Test
    @DisplayName("7.1: заголовок ответа traceparent называет текущий span и пуст вне трассы")
    void theResponseHeaderNamesTheCurrentSpan() {
        assertThat(TraceResponseHeaderFilter.traceparent(null)).isNull();
        assertThat(TraceResponseHeaderFilter.traceparent(tracer)).isNull();
        assertThat(TraceResponseHeaderFilter.traceparent(Tracer.NOOP)).isNull();
        Span span = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(TraceResponseHeaderFilter.traceparent(tracer))
                    .isEqualTo("00-" + span.context().traceId() + "-"
                            + span.context().spanId() + "-01");
        } finally {
            span.end();
        }
    }

    private BeanPostProcessor processor(MockEnvironment environment) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        return ObservabilityConfiguration.tracingDataSourcePostProcessor(
                environment, beans.getBeanProvider(Tracer.class));
    }

    private DataSource traced(DataSource target) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        return new TracingDataSource(target, new JdbcTracing(beans.getBeanProvider(Tracer.class), "dataSource"));
    }

    private static DataSource h2() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:jdbc-tracing;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /** The data source with every SQL text its connections and statements receive written down. */
    private static DataSource recording(DataSource target, List<String> sql) {
        return new org.springframework.jdbc.datasource.DelegatingDataSource(target) {
            @Override
            public Connection getConnection() throws SQLException {
                return recordingProxy(Connection.class, super.getConnection(), sql);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T recordingProxy(Class<T> type, Object target, List<String> sql) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                JdbcTracingTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof String text) {
                        sql.add(text);
                    }
                    Object result;
                    try {
                        result = method.invoke(target, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (result instanceof java.sql.PreparedStatement) {
                        return recordingProxy(java.sql.PreparedStatement.class, result, sql);
                    }
                    if (result instanceof Statement) {
                        return recordingProxy(Statement.class, result, sql);
                    }
                    return result;
                });
    }

    private record Recorder(List<SpanData> spans) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
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
