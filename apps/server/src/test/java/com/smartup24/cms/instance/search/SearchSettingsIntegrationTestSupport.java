package com.smartup24.cms.instance.search;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.*;
import com.smartup24.cms.instance.common.metrics.PlatformMetrics;
import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import com.smartup24.cms.instance.config.idempotency.IdempotencyService;
import com.smartup24.cms.instance.config.security.*;
import com.smartup24.cms.instance.kauth.repository.KauthSessionRepository;
import com.smartup24.cms.instance.kauth.security.KauthAuthenticationFilter;
import com.smartup24.cms.instance.kauth.service.*;
import com.smartup24.cms.instance.md.api.MdUserIdentity;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.service.*;
import com.smartup24.cms.instance.search.controller.SearchController;
import com.smartup24.cms.instance.search.service.*;
import com.smartup24.cms.instance.search.typesense.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@WebMvcTest(
        controllers = SearchController.class,
        properties = {
            "logging.level.org.springframework.boot.security.autoconfigure=ERROR",
            "dwh.typesense.enabled=false",
            "dwh.typesense.url=http://127.0.0.1:1",
            "dwh.rate-limit.expensive-per-minute=600",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unused",
            "server.port=0",
            "management.server.port=0"
        })
@Import({
    SearchSettingsIntegrationTestSupport.Fixture.class,
    SecurityConfig.class,
    ProblemDetailAuthHandlers.class,
    KauthAuthenticationFilter.class,
    RateLimitFilter.class,
    RateLimitService.class,
    IdempotencyFilter.class
})
abstract class SearchSettingsIntegrationTestSupport {
    static final ObjectMapper mapper = new ObjectMapper();
    static final List<String> requests = new CopyOnWriteArrayList<>();
    static final List<String> paths = new CopyOnWriteArrayList<>();
    static final Map<String, String> responses = new ConcurrentHashMap<>();
    static final Map<String, Integer> responseStatuses = new ConcurrentHashMap<>();
    static volatile int healthStatus = 200;
    static volatile Runnable beforeEngine = () -> {};
    static final AtomicLong actorSequence = new AtomicLong(1000);
    long actorId;
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("search_management_test")
            .withUsername("fixture")
            .withPassword("fixture-only");
    static final HttpServer engine;

    static {
        try {
            engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            engine.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                paths.add(exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath());
                beforeEngine.run();
                String output;
                if (exchange.getRequestURI().getPath().equals("/multi_search")) {
                    var searches = mapper.readTree(body).path("searches");
                    var results = new ArrayList<Map<String, Object>>();
                    for (var search : searches) {
                        var hits = new ArrayList<Map<String, Object>>();
                        for (int i = 1; i <= search.path("per_page").asInt(); i++)
                            hits.add(Map.of("document", Map.of("id", "" + i, "title", "Delivery " + i, "task_id", i)));
                        results.add(Map.of("hits", hits, "found", 30, "search_time_ms", 1));
                    }
                    output = mapper.writeValueAsString(Map.of("results", results));
                } else output = responses.getOrDefault(exchange.getRequestURI().getPath(), "{\"ok\":true}");
                byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(
                        responseStatuses.getOrDefault(
                                exchange.getRequestURI().getPath(),
                                exchange.getRequestURI().getPath().equals("/health") ? healthStatus : 200),
                        bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            engine.start();
            postgres.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SearchPolicyProvider provider;

    @Autowired
    DataSource contextSource;

    @MockitoBean
    KauthSessionService sessions;

    @MockitoBean
    KauthApiTokenService apiTokens;

    @MockitoBean
    MdUserService users;

    @MockitoBean
    MdPermissionService permissions;

    @MockitoBean
    RoleMembershipAuthorizer roles;

    @MockitoBean
    PlatformMetrics metrics;

    @MockitoBean
    IdempotencyService idempotency;

    @BeforeEach
    void resetData() {
        actorId = actorSequence.incrementAndGet();
        requests.clear();
        paths.clear();
        responses.clear();
        responseStatuses.clear();
        healthStatus = 200;
        beforeEngine = () -> {};
        responses.put("/debug", "{\"version\":\"27.1\",\"state\":1}");
        responses.put(
                "/metrics.json", "{\"system_disk_used_bytes\":\"123456\",\"system_disk_total_bytes\":\"999999\"}");
        for (var type : List.of("TASK", "PROJECT", "USER")) {
            String collection = "fixture_"
                    + switch (type) {
                        case "TASK" -> "tasks";
                        case "PROJECT" -> "projects";
                        default -> "users";
                    };
            var schema = new LinkedHashMap<>(SearchCollectionSchema.mixed(collection, type));
            schema.put("num_documents", 23);
            responses.put("/collections/" + collection, mapper.writeValueAsString(schema));
        }
        jdbc.sql("update search_settings set configuration=cast(:policy as jsonb),version=version+1 where id=1")
                .param("policy", mapper.writeValueAsString(SearchQueryPolicy.defaults()))
                .update();
        jdbc.sql("update search_index_state set active_generation_id=null, initialized=false where id=1")
                .update();
        jdbc.sql("delete from search_jobs").update();
        jdbc.sql("delete from search_generation_delivery").update();
        jdbc.sql("delete from search_projection_versions").update();
        jdbc.sql("delete from search_generations").update();
        var id = UUID.randomUUID();
        jdbc.sql(
                        "insert into search_generations(id,state,task_collection,project_collection,user_collection,schema_version,schema_profile,settings_version) values(:id,'ACTIVE','fixture_tasks','fixture_projects','fixture_users',1,'MIXED',1)")
                .param("id", id)
                .update();
        jdbc.sql("update search_index_state set active_generation_id=:id,initialized=true where id=1")
                .param("id", id)
                .update();
        provider.refresh();
    }

    void authenticate(Set<String> allowed, boolean admin) {
        when(sessions.getActiveSession(anyString()))
                .thenReturn(Optional.of(new KauthSessionRepository.SessionRecord(
                        actorId,
                        actorId,
                        "fixture",
                        "127.0.0.1",
                        "fixture",
                        null,
                        Instant.now(),
                        Instant.now(),
                        null,
                        0)));
        var fixtureUser = new MdUserRepository.UserRecord(
                actorId,
                "Fixture",
                "fixture",
                "fixture@example.invalid",
                null,
                "x",
                "A",
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                null,
                Instant.now(),
                Instant.now(),
                null,
                null,
                0,
                1L);
        when(users.getUserIdentity(actorId))
                .thenReturn(new MdUserIdentity(actorId, "fixture", "fixture@example.invalid", "A", false, 0));
        when(users.findAuthUserById(actorId)).thenReturn(Optional.of(MdUserService.AuthUser.from(fixtureUser)));
        when(permissions.getEffectivePermissions(actorId)).thenReturn(allowed);
        when(permissions.getPermissionVersion(actorId)).thenReturn(1L);
        when(roles.hasActiveRole(anyLong(), anyString())).thenReturn(admin);
    }

    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("DWH_SESSION", "fixture-session"), new Cookie("XSRF-TOKEN", "fixture-csrf"))
                .header("X-XSRF-TOKEN", "fixture-csrf")
                .contentType("application/json");
    }

    JsonNode readSettings() throws Exception {
        return mapper.readTree(mvc.perform(auth(get("/api/v1/search/settings")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    static String saveJson(long version, Object policy) {
        return mapper.writeValueAsString(Map.of("version", version, "policy", policy));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ComponentScan(
            basePackages = "com.smartup24.cms.instance.search",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern =
                                    ".*(SearchGenerationService|SearchGenerationRepository|SearchJobAudit|SearchStoragePreflight|SearchProjectionReader|SearchJobService|SearchJobRepository|SearchController|SearchManagementController|SearchSettingsService|SearchStatusService|SearchExecutionSnapshotReader|SearchSettingsRepository|SearchPolicyProvider|SearchService|SearchAccessPolicy|SearchResultBudget|SearchIndexStateRepository|SearchFallbackRepository|TypesenseClient|TypesenseCollections|TypesenseHealth|TypesenseSearch)$"))
    @Import({AuditLogService.class, AuditLogRepository.class, AuditDataRedactor.class, LegacyController.class})
    static class Fixture {
        @Bean
        DataSource dataSource() {
            var dataSource =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            FlywayUtcConfiguration.configure(org.flywaydb.core.Flyway.configure())
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            return new ObservingDataSource(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(DataSource source) {
            return JdbcClient.create(source);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TypesenseProperties typesenseProperties() {
            return new TypesenseProperties(
                    "http://127.0.0.1:" + engine.getAddress().getPort(), "fixture-key", true, false);
        }

        @Bean(destroyMethod = "close")
        AutoCloseable ownedResources() {
            return () -> {
                engine.stop(0);
                postgres.stop();
            };
        }
    }

    @RestController
    static class LegacyController {
        @PostMapping("/api/v1/search-test/legacy")
        public LegacyOptional legacy(@RequestBody LegacyOptional request) {
            return request;
        }
    }

    record LegacyOptional(int optional) {}

    static final class ObservingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final Map<Thread, AtomicInteger> open = new ConcurrentHashMap<>();
        private final AtomicReference<Runnable> afterRead = new AtomicReference<>();
        final AtomicInteger snapshotReads = new AtomicInteger();
        private volatile Thread observedThread;

        ObservingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void observe(Thread thread, Runnable callback) {
            snapshotReads.set(0);
            afterRead.set(callback);
            observedThread = thread;
        }

        void stopObserving() {
            observedThread = null;
            afterRead.set(null);
        }

        int openConnections(Thread thread) {
            return open.getOrDefault(thread, new AtomicInteger()).get();
        }

        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return track(delegate.getConnection());
        }

        public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
            return track(delegate.getConnection(user, password));
        }

        private java.sql.Connection track(java.sql.Connection connection) {
            Thread owner = Thread.currentThread();
            var count = open.computeIfAbsent(owner, ignored -> new AtomicInteger());
            count.incrementAndGet();
            var closed = new AtomicBoolean();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection.class.getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close") && closed.compareAndSet(false, true))
                            count.decrementAndGet();
                        Object result = invoke(connection, method, args);
                        if (method.getName().equals("prepareStatement")
                                && result instanceof java.sql.PreparedStatement statement
                                && args[0] instanceof String sql
                                && (sql.contains("search_index_state") || sql.contains("search_settings")))
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    java.sql.PreparedStatement.class.getClassLoader(),
                                    new Class<?>[] {java.sql.PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        Object statementResult = invoke(statement, statementMethod, statementArgs);
                                        if (Thread.currentThread() == observedThread
                                                && statementMethod.getName().equals("executeQuery")) {
                                            snapshotReads.incrementAndGet();
                                            Runnable callback = afterRead.getAndSet(null);
                                            if (callback != null) callback.run();
                                        }
                                        return statementResult;
                                    });
                        return result;
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
