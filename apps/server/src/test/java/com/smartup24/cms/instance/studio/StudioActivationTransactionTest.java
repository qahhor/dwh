package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.Activated;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioActivationService;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Activation commits declaration, rights and audit once, including concurrent requests and failure (ADR-0038). */
class StudioActivationTransactionTest {
    private final ObjectMapper json = JsonMapper.shared();
    private HikariDataSource database;
    private JdbcClient jdbc;
    private StudioFormRepository repository;
    private StudioActivationService service;
    private long author;

    @BeforeEach
    void database() {
        database = (HikariDataSource) TestDatabases.migratedCopy("studio_activate");
        jdbc = JdbcClient.create(database);
        repository = new StudioFormRepository(jdbc, json);
        author = jdbc.sql(
                        "insert into md_users(name, login, email) values ('Test', 'studio_activate', 'activate@example.invalid') returning id")
                .query(Long.class)
                .single();
        service = service(audit());
        signIn();
    }

    @AfterEach
    void close() {
        SecurityContext.clear();
        database.close();
    }

    @Test
    void exactRepeatKeepsOneDeclarationOneAuditAndOnlyAdministrativeGrants() {
        byte[] body = bytes("repeat", false);
        Activated first = service.activate(body);
        assertThat(service.activate(body)).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.sql("select action from md_form_actions where form_code = 'studio.repeat'")
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder("view", "create", "update", "delete");
        assertThat(jdbc.sql(
                                "select distinct r.pcode from md_role_permissions p join md_roles r on r.id = p.role_id where p.form_code = 'studio.repeat'")
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder("admin", "chief_admin");
    }

    @Test
    void anAuditFailureRollsBackTheDeclarationPermissionsAndTheAuditInsert() {
        AuditLogService failing = spy(audit());
        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("Injected audit failure");
                })
                .when(failing)
                .logChange(anyString(), anyString(), anyString(), anyList(), any(), any());
        var failingService = service(failing);
        byte[] body = bytes("rollback", false);
        assertThatThrownBy(() -> failingService.activate(body))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Injected audit failure");
        assertThat(repository.count()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(jdbc.sql("select count(*) from md_forms where code = 'studio.rollback'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from md_form_actions where form_code = 'studio.rollback'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from md_role_permissions where form_code = 'studio.rollback'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(service.activate(body).entityCode()).isEqualTo("studio.rollback");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void concurrentExactRepeatsCommitOneDefinitionAndReturnTheSameChecksum() throws Exception {
        byte[] body = bytes("parallel", false);
        List<String> outcomes = race(body, body);
        assertThat(outcomes).containsExactly("OK", "OK");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(repository.checksum("studio.parallel"))
                .contains(pack("parallel", false).checksum());
    }

    @Test
    void concurrentDifferentPackagesForOneCodeHaveOneWinnerAndOneConflict() throws Exception {
        List<String> outcomes = race(bytes("conflict", false), bytes("conflict", true));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "CONFLICT");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(repository.checksum("studio.conflict").orElseThrow())
                .isIn(pack("conflict", false).checksum(), pack("conflict", true).checksum());
    }

    @Test
    void theLastQuotaSlotCannotBeClaimedByTwoConcurrentActivations() throws Exception {
        for (int index = 0; index < 99; index++) {
            DeliveryPackage value = pack("existing" + index, false);
            repository.insert("studio.existing" + index, value.declaration(), value.checksum(), author);
        }
        List<String> outcomes = race(bytes("lastone", false), bytes("lasttwo", false));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "VALIDATION_FAILED");
        assertThat(repository.count()).isEqualTo(100);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from md_forms where code in ('studio.lastone', 'studio.lasttwo')")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThatThrownBy(() -> service.activate(bytes("overquota", false)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        String winner = repository.checksum("studio.lastone").isPresent() ? "lastone" : "lasttwo";
        assertThat(service.activate(bytes(winner, false)).entityCode()).isEqualTo("studio." + winner);
        assertThat(repository.count()).isEqualTo(100);
        assertThat(auditCount()).isEqualTo(1);
    }

    private List<String> race(byte[] first, byte[] second) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> activateTogether(ready, first));
            var right = executor.submit(() -> activateTogether(ready, second));
            return List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
    }

    private String activateTogether(CyclicBarrier ready, byte[] body) throws Exception {
        signIn();
        try {
            ready.await(10, TimeUnit.SECONDS);
            service.activate(body);
            return "OK";
        } catch (ApiException failure) {
            return failure.getErrorCode().name();
        } finally {
            SecurityContext.clear();
        }
    }

    private AuditLogService audit() {
        return new AuditLogService(new AuditLogRepository(jdbc, json), null, new AuditDataRedactor(json));
    }

    private StudioActivationService service(AuditLogService audit) {
        var target = new StudioActivationService(
                new StudioPackages(json, new MdSimpleFormDeclarations(json)),
                repository,
                new StudioEntityCompiler(),
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                audit,
                module -> "studio".equals(module),
                true);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(database), new AnnotationTransactionAttributeSource()));
        return (StudioActivationService) proxy.getProxy();
    }

    private long auditCount() {
        return jdbc.sql("select count(*) from audit_log where table_name = 'studio_forms'")
                .query(Long.class)
                .single();
    }

    private byte[] bytes(String name, boolean changed) {
        return json.writeValueAsBytes(pack(name, changed));
    }

    private DeliveryPackage pack(String name, boolean changed) {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/studio/simple-form.json"))) {
            ObjectNode declaration = (ObjectNode) json.readTree(input.readAllBytes());
            ((ObjectNode) declaration.path("module")).put("code", "studio");
            ((ObjectNode) declaration.path("entity")).put("name", name);
            if (changed) ((ObjectNode) declaration.path("entity").path("title")).put("en", "Changed title");
            declaration.set("fields", json.createArrayNode());
            return new MdSimpleFormDeclarations(json).prepare(json.writeValueAsBytes(declaration));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                author, "studio_activate", null, null, false, Set.of("studio.forms.publish"), 1L, false, 0L, null));
    }
}
