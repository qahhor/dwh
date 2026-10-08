package com.smartup24.cms.instance.config.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * JDBC spans and the {@code traceparent} SQL comment (plan 10/10, item 7.2; ADR-0009, section 4).
 *
 * <p>Inside a sampled trace every statement gets a client span, a child of the current span, which lives from the
 * moment the statement is prepared until it is closed, and its SQL ends with {@code /*traceparent='00-...-01'*}{@code /}
 * naming that span, so a slow query in the PostgreSQL log leads to its trace. Outside a sampled trace nothing is
 * changed: the SQL text stays stable and the server-side prepared statement cache keeps working. The span carries no
 * SQL text and no parameters (ADR-0009, section 5).
 */
final class JdbcTracing {

    static final String SPAN_NAME = "jdbc statement";

    private static final Set<String> PREPARE = Set.of("prepareStatement", "prepareCall");
    private static final Set<String> EXECUTE_WITH_SQL =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private final ObjectProvider<Tracer> tracer;
    private final String dataSourceName;

    JdbcTracing(ObjectProvider<Tracer> tracer, String dataSourceName) {
        this.tracer = tracer;
        this.dataSourceName = dataSourceName;
    }

    /** The SQL with the trace comment of the span appended. */
    static String comment(String sql, TraceContext context) {
        return sql + "\n/*traceparent='00-" + context.traceId() + "-" + context.spanId() + "-01'*/";
    }

    Connection connection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                JdbcTracing.class.getClassLoader(), new Class<?>[] {Connection.class}, new ConnectionHandler(target));
    }

    /** A client span under the current span, or null when the current trace is not sampled. */
    private @Nullable Span startSpan() {
        Tracer current = tracer.getIfAvailable();
        Span parent = current == null ? null : current.currentSpan();
        if (current == null
                || parent == null
                || !Boolean.TRUE.equals(parent.context().sampled())) {
            return null;
        }
        return current.spanBuilder()
                .setParent(parent.context())
                .name(SPAN_NAME)
                .kind(Span.Kind.CLIENT)
                .tag("db.system", "postgresql")
                .tag("db.datasource", dataSourceName)
                .remoteServiceName("postgresql")
                .start();
    }

    private static Object call(Object target, Method method, Object @Nullable [] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    private static @Nullable Object identity(Object proxy, Method method, Object @Nullable [] args) {
        return switch (method.getName()) {
            case "equals" -> args != null && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> null;
        };
    }

    /** The connection: starts a span for each statement it creates, ends the forgotten ones when it closes. */
    private final class ConnectionHandler implements InvocationHandler {

        private final Connection target;
        private final List<Span> open = new ArrayList<>();

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
            Object identity = identity(proxy, method, args);
            if (identity != null) {
                return identity;
            }
            String name = method.getName();
            boolean prepare = PREPARE.contains(name) && args != null && args[0] instanceof String;
            if (!prepare && !"createStatement".equals(name)) {
                if ("close".equals(name)) {
                    open.forEach(Span::end);
                    open.clear();
                }
                return call(target, method, args);
            }
            Span span = startSpan();
            if (span == null) {
                return call(target, method, args);
            }
            Object[] callArgs = args;
            if (prepare) {
                callArgs = args.clone();
                callArgs[0] = comment((String) args[0], span.context());
            }
            try {
                Statement statement = (Statement) call(target, method, callArgs);
                open.add(span);
                return statement(statement, span, prepare);
            } catch (Throwable e) {
                span.error(e);
                span.end();
                throw e;
            }
        }

        private Object statement(Statement statement, Span span, boolean prepared) {
            Class<?> type = statement instanceof CallableStatement
                    ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(
                    JdbcTracing.class.getClassLoader(),
                    new Class<?>[] {type},
                    new StatementHandler(statement, span, prepared, this));
        }

        void closed(Span span) {
            if (open.remove(span)) {
                span.end();
            }
        }
    }

    /** The statement: comments the SQL it is given, records a failure on its span, ends the span when closed. */
    private static final class StatementHandler implements InvocationHandler {

        private final Statement target;
        private final Span span;
        private final boolean prepared;
        private final ConnectionHandler connection;

        StatementHandler(Statement target, Span span, boolean prepared, ConnectionHandler connection) {
            this.target = target;
            this.span = span;
            this.prepared = prepared;
            this.connection = connection;
        }

        @Override
        public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
            Object identity = identity(proxy, method, args);
            if (identity != null) {
                return identity;
            }
            String name = method.getName();
            if ("close".equals(name)) {
                try {
                    return call(target, method, args);
                } finally {
                    connection.closed(span);
                }
            }
            Object[] callArgs = args;
            if (!prepared && EXECUTE_WITH_SQL.contains(name) && args != null && args[0] instanceof String sql) {
                callArgs = args.clone();
                callArgs[0] = comment(sql, span.context());
            }
            try {
                return call(target, method, callArgs);
            } catch (Throwable e) {
                if (name.startsWith("execute")) {
                    span.error(e);
                }
                throw e;
            }
        }
    }
}
