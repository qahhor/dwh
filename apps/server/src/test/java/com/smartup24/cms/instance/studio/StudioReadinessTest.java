package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioActivationService;
import com.smartup24.cms.instance.studio.service.StudioDefinitions;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Operational limits, immutable definitions and activation without DDL privileges (ADR-0038). */
class StudioReadinessTest {
    private final ObjectMapper json = JsonMapper.shared();
    private final StudioEntityCompiler compiler = new StudioEntityCompiler();
    private HikariDataSource database;
    private JdbcClient jdbc;
    private StudioFormRepository repository;
    private long author;

    @BeforeEach
    void setup() {
        database = (HikariDataSource) TestDatabases.migratedCopy("studio_readiness");
        jdbc = JdbcClient.create(database);
        repository = new StudioFormRepository(jdbc, json);
        author = jdbc.sql(
                        "insert into md_users(name,login,email) values('Test','studio_readiness','readiness@example.invalid') returning id")
                .query(Long.class)
                .single();
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                author, "readiness", null, null, false, Set.of("studio.forms.publish"), 1L, false, 1L, null));
    }

    @AfterEach
    void close() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContext.clear();
        database.close();
    }

    @Test
    void studioModuleDoesNotAdvertiseAnEditorWithoutTheEditorsPermission() {
        assertThat(jdbc.sql("select route from md_installed_modules where code='studio'")
                        .query(String.class)
                        .single())
                .isEmpty();
    }

    @Test
    void activationNeedsNoDdlPrivilegeAndCommitsTheOrdinaryCatalogAndAudit() {
        String role = "studio_activation_dml_" + author;
        jdbc.sql("create role " + role + " nologin nosuperuser nocreatedb nocreaterole")
                .update();
        jdbc.sql("revoke create on schema public from public").update();
        jdbc.sql("grant usage on schema public to " + role).update();
        jdbc.sql("grant select, insert, update, delete on all tables in schema public to " + role)
                .update();
        jdbc.sql("grant usage, select on all sequences in schema public to " + role)
                .update();
        new TransactionTemplate(new DataSourceTransactionManager(database)).executeWithoutResult(status -> {
            jdbc.sql("set local role " + role).update();
            assertThat(jdbc.sql("select has_schema_privilege(current_user,'public','CREATE')")
                            .query(Boolean.class)
                            .single())
                    .isFalse();
            assertThat(service(true, true, new EntityRegistry(List.of()))
                            .activate(bytes("dml"))
                            .form()
                            .entityCode())
                    .isEqualTo("studio.dml");
        });
        assertThat(repository.count()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_log where table_name='studio_forms'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from md_form_actions where form_code='studio.dml'")
                        .query(Long.class)
                        .single())
                .isEqualTo(4);
    }

    @Test
    void disablingActivationOrTheModuleRejectsCreationWithoutRemovingExistingDefinitions() {
        service(true, true, new EntityRegistry(List.of())).activate(bytes("kept"));
        for (boolean activation : List.of(false, true)) {
            var closed = service(activation, !activation, new EntityRegistry(List.of()));
            assertThat(closed.availability().enabled()).isFalse();
            assertThatThrownBy(() -> closed.activate(bytes("closed"))).isInstanceOf(ApiException.class);
        }
        assertThat(repository.count()).isEqualTo(1);
        assertThat(new StudioDefinitions(repository, compiler).snapshot()).containsKey("studio.kept");
    }

    @Test
    void anOccupiedStaticCodeCannotBeActivated() {
        var fixed = compiler.compile(1, pack("occupied").declaration()).entity();
        var service = service(true, true, new EntityRegistry(List.of(fixed)));
        assertThatThrownBy(() -> service.activate(bytes("occupied")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(repository.count()).isZero();
    }

    @Test
    void updateDeleteAndTruncateCannotChangeActivatedDefinitions() {
        service(true, true, new EntityRegistry(List.of())).activate(bytes("immutable"));
        for (String sql : List.of(
                "update studio_forms set checksum=repeat('0',64)",
                "delete from studio_forms",
                "truncate studio_records,studio_forms")) {
            assertThatThrownBy(() -> jdbc.sql(sql).update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(repository.checksum("studio.immutable"))
                .contains(pack("immutable").checksum());
    }

    @Test
    void recordRevisionsStayWithinTheSafeIntegerRange() {
        service(true, true, new EntityRegistry(List.of())).activate(bytes("revision"));
        long form = jdbc.sql("select id from studio_forms where code = 'studio.revision'")
                .query(Long.class)
                .single();
        String insert = "insert into studio_records (form_id, name, code, created_by, modified_by, revision) "
                + "values (:form, 'Record', :code, :author, :author, :revision)";
        for (long revision : List.of(0L, 9007199254740992L)) {
            assertThatThrownBy(() -> jdbc.sql(insert)
                            .param("form", form)
                            .param("code", "r" + revision)
                            .param("author", author)
                            .param("revision", revision)
                            .update())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        jdbc.sql(insert)
                .param("form", form)
                .param("code", "max")
                .param("author", author)
                .param("revision", 9007199254740991L)
                .update();
    }

    @ParameterizedTest
    @ValueSource(strings = {"forms", "records", "activation"})
    void reservedCodesAreNotNewForms(String name) {
        assertThatThrownBy(
                        () -> service(true, true, new EntityRegistry(List.of())).activate(bytes(name)))
                .isInstanceOf(ApiException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void foreignNamespacesAndTamperedFieldDefinitionsAreRejected() {
        ObjectNode pack = (ObjectNode) json.valueToTree(pack("invalid"));
        ((ObjectNode) pack.path("declaration").path("module")).put("code", "catalog");
        var verified = new MdSimpleFormDeclarations(json).prepare(json.writeValueAsBytes(pack.path("declaration")));
        assertThatThrownBy(() -> verifier().verify(json.writeValueAsBytes(verified)))
                .isInstanceOf(ApiException.class);
        pack.put("formatVersion", 1.0);
        assertThatThrownBy(() -> verifier().verify(json.writeValueAsBytes(pack)))
                .isInstanceOf(ApiException.class);
        for (String body :
                List.of("{\"kind\":\"x\",\"kind\":\"y\"}", "{} {}", "null", "[]", "[".repeat(11) + "]".repeat(11))) {
            assertThatThrownBy(() -> verifier().verify(body.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void boundedMaximumCatalogHasOneImmutableSnapshotPerRequest() throws IOException {
        for (int i = 0; i < 100; i++) {
            var value = pack("bench" + i);
            repository.insert("studio.bench" + i, value.declaration(), value.checksum(), author);
        }
        StudioDefinitions definitions = new StudioDefinitions(repository, compiler);
        List<Long> samples = new ArrayList<>();
        for (int iteration = 0; iteration < 25; iteration++) {
            var request = new ServletRequestAttributes(new MockHttpServletRequest());
            RequestContextHolder.setRequestAttributes(request);
            long started = System.nanoTime();
            var snapshot = definitions.snapshot();
            assertThat(snapshot).hasSize(100);
            for (int repeat = 0; repeat < 20; repeat++)
                assertThat(definitions.snapshot()).isSameAs(snapshot);
            if (iteration >= 5) samples.add((System.nanoTime() - started) / 1000000);
            request.requestCompleted();
            RequestContextHolder.resetRequestAttributes();
        }
        samples.sort(Long::compareTo);
        Files.writeString(
                Path.of("target/studio-catalog-workload.txt"),
                "Local PostgreSQL; 100 forms x 10 extra fields; 5 warmups, 20 request samples; "
                        + "one DB snapshot/request, 20 reuse checks; p50_ms=" + samples.get(9) + ", p95_ms="
                        + samples.get(18) + ", max_ms=" + samples.get(19) + "\n");
    }

    private StudioActivationService service(boolean enabled, boolean active, EntityRegistry entities) {
        return new StudioActivationService(
                verifier(),
                repository,
                compiler,
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                new AuditLogService(new AuditLogRepository(jdbc, json), null, new AuditDataRedactor(json)),
                module -> active,
                enabled,
                entities);
    }

    private StudioPackages verifier() {
        return new StudioPackages(json, new MdSimpleFormDeclarations(json));
    }

    private byte[] bytes(String name) {
        return json.writeValueAsBytes(pack(name));
    }

    private DeliveryPackage pack(String name) {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/studio/simple-form.json"))) {
            ObjectNode declaration = (ObjectNode) json.readTree(input.readAllBytes());
            ((ObjectNode) declaration.path("module")).put("code", "studio");
            ((ObjectNode) declaration.path("entity")).put("name", name);
            return new MdSimpleFormDeclarations(json).prepare(json.writeValueAsBytes(declaration));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
