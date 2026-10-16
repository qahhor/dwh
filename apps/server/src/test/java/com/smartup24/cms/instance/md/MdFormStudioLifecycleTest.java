package com.smartup24.cms.instance.md;

import static com.smartup24.cms.instance.md.api.MdFormStudioDtos.MAX_INTEGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.FormLayoutDeclarations;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormSectionMeta;
import com.smartup24.cms.instance.common.entity.FormStudioService;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.example.service.ExampleProductsEntity;
import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionPackage;
import com.smartup24.cms.instance.md.repository.MdFormStudioRepository;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.md.service.MdFormStudioPackages;
import com.smartup24.cms.instance.md.service.MdFormStudioService;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MdFormStudioLifecycleTest {
    private static final String CODE = "example.products";
    private final JsonMapper json = JsonMapper.shared();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private MdFormStudioRepository repo;
    private MdFormStudioService service;
    private FormStudioService metadata;
    private FormLayoutDeclarations declarations;
    private MdFormStudioPackages packages;
    private AuditLogService audit;
    private FormMeta meta;
    private byte[] initial;
    private long actor;

    @BeforeEach
    void setup() {
        pool = (HikariDataSource) TestDatabases.migratedCopy("studio_lifecycle");
        jdbc = JdbcClient.create(pool);
        MdAuditActors.ensureSystemUser(jdbc);
        actor = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        signIn();
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        declarations = new FormLayoutDeclarations(json);
        var real = new FormStudioService(
                EntityGate.of(new EntityRegistry(
                        List.of(ExampleProductsEntity.DEFINITION, ExampleRequestsEntity.DEFINITION))),
                mock(EntityEnums.class),
                declarations);
        meta = real.metadata(CODE);
        initial = json.writeValueAsBytes(declarations.initial(meta));
        metadata = mock(FormStudioService.class);
        when(metadata.metadata(CODE)).thenReturn(meta);
        repo = new MdFormStudioRepository(jdbc);
        packages = new MdFormStudioPackages(json, declarations);
        audit = new AuditLogService(new AuditLogRepository(jdbc, json), null, new AuditDataRedactor());
        service = service(audit);
        inTransaction(() -> service.create(CODE, initial));
    }

    @AfterEach
    void close() {
        SecurityContext.clear();
        pool.close();
    }

    @Test
    void concurrentAuthorsHaveExactlyOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> saveAfter(start));
            var second = threads.submit(() -> saveAfter(start));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(repo.findDraft(CODE, false).orElseThrow().revision()).isEqualTo(2);
        assertThat(count("audit_log", "table_name = 'md_form_layout_drafts'")).isEqualTo(2);
    }

    @Test
    void simultaneousPrepareAndRetryCreateOneVersionAndOneAudit() throws Exception {
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> prepareAfter(start));
            var second = threads.submit(() -> prepareAfter(start));
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(count("md_form_layout_versions", "true")).isEqualTo(1);
        assertThat(count("audit_log", "table_name = 'md_form_layout_versions'")).isEqualTo(1);
        assertThat(inTransaction(() -> service.prepare(CODE, 1)).version()).isEqualTo(1);
        assertThat(count("audit_log", "table_name = 'md_form_layout_versions'")).isEqualTo(1);
    }

    @Test
    void anAuditFailureRollsBackTheWholeVersionAndRetryRecovers() {
        var broken = mock(AuditLogService.class);
        doThrow(new IllegalStateException("test fault"))
                .when(broken)
                .logChange(anyString(), anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> inTransaction(() -> service(broken).prepare(CODE, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("test fault");
        assertThat(count("md_form_layout_versions", "true")).isZero();
        assertThat(repo.findDraft(CODE, false).orElseThrow().revision()).isEqualTo(1);
        assertThat(inTransaction(() -> service.prepare(CODE, 1)).version()).isEqualTo(1);
    }

    @Test
    void preparedRowsCannotBeUpdatedDeletedOrTruncated() {
        inTransaction(() -> service.prepare(CODE, 1));
        for (String sql : List.of(
                "update md_form_layout_versions set checksum = repeat('0',64)",
                "delete from md_form_layout_versions",
                "truncate md_form_layout_versions")) {
            assertThatThrownBy(() -> jdbc.sql(sql).update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(count("md_form_layout_versions", "true")).isEqualTo(1);
    }

    @Test
    void databaseLimitsUseExactArithmeticAndRejectTheFirstUnsafeInteger() {
        assertThat(jdbc.sql("select pg_typeof(power(2::numeric, 53) - 1)::text")
                        .query(String.class)
                        .single())
                .isEqualTo("numeric");
        assertThat(jdbc.sql("select power(2::numeric, 53) - 1")
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo(Long.toString(MAX_INTEGER));
        jdbc.sql("update md_form_layout_drafts set revision=:revision where entity_code=:code")
                .param("revision", MAX_INTEGER)
                .param("code", CODE)
                .update();
        assertThat(repo.findDraft(CODE, false).orElseThrow().revision()).isEqualTo(MAX_INTEGER);
        assertThatThrownBy(() -> jdbc.sql("update md_form_layout_drafts set revision=:revision where entity_code=:code")
                        .param("revision", MAX_INTEGER + 1)
                        .param("code", CODE)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("md_form_layout_drafts_ck_revision");
        var maximum = repo.insertVersion(CODE, MAX_INTEGER, MAX_INTEGER, "0".repeat(64), "{}", actor);
        assertThat(maximum.version()).isEqualTo(MAX_INTEGER);
        assertThat(maximum.sourceRevision()).isEqualTo(MAX_INTEGER);
        assertThatThrownBy(() -> repo.insertVersion(CODE, MAX_INTEGER + 1, 1, "0".repeat(64), "{}", actor))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("md_form_layout_versions_ck_version");
        assertThatThrownBy(() -> repo.insertVersion(CODE, 1, MAX_INTEGER + 1, "0".repeat(64), "{}", actor))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("md_form_layout_versions_ck_revision");
    }

    @Test
    void aRecomputedAttackerChecksumDoesNotReplaceTheTrustedPackage() {
        FormStudioVersionPackage original = inTransaction(() -> service.prepare(CODE, 1));
        ObjectNode forged = (ObjectNode) json.valueToTree(original);
        ObjectNode artifact = (ObjectNode) forged.path("package");
        ObjectNode field = (ObjectNode) artifact.path("declaration")
                .path("sections")
                .get(0)
                .path("fields")
                .get(0);
        field.set(
                "labels",
                json.createObjectNode()
                        .put("ru", "Changed")
                        .put("uz", "Changed")
                        .put("en", "Changed"));
        forged.put("checksum", packages.digest(artifact));
        byte[] body = json.writeValueAsBytes(forged);
        assertThat(packages.validate(body, meta)).isNotNull();
        assertThatThrownBy(() -> service.verify(CODE, 1, body))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getMessageKey()).isEqualTo("error.md.form_studio_package_invalid"));
        assertThat(service.version(CODE, 1)).isEqualTo(original);
    }

    @Test
    void dependencyDriftAndHiddenFieldsNeverReturnStaleValidatedContent() {
        var original = inTransaction(() -> service.prepare(CODE, 1));
        FormMeta changed = new FormMeta(
                meta.code(),
                meta.titleKey(),
                meta.listCode(),
                meta.fields(),
                meta.layout(),
                List.of(),
                meta.capabilities(),
                meta.collections(),
                meta.workflow(),
                meta.tabs());
        when(metadata.metadata(CODE)).thenReturn(changed);
        assertThatThrownBy(() -> service.verify(CODE, 1, json.writeValueAsBytes(original)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getMessageKey())
                                .isEqualTo("error.md.form_studio_dependencies_changed"));
        assertThatThrownBy(() -> inTransaction(() -> service.prepare(CODE, 1))).isInstanceOf(ApiException.class);
        FormMeta hidden = new FormMeta(
                meta.code(),
                meta.titleKey(),
                meta.listCode(),
                meta.fields().stream()
                        .filter(field -> !field.key().equals("price"))
                        .toList(),
                List.of(new FormSectionMeta("main", "main", List.of("code", "name", "unit"))),
                meta.actions(),
                meta.capabilities(),
                meta.collections(),
                meta.workflow(),
                meta.tabs());
        when(metadata.metadata(CODE)).thenReturn(hidden);
        assertThatThrownBy(() -> service.get(CODE)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.version(CODE, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.verify(CODE, 1, json.writeValueAsBytes(original)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTransaction(
                        () -> service.update(CODE, 1, json.writeValueAsBytes(declarations.initial(hidden)))))
                .isInstanceOf(ApiException.class);
        assertThat(repo.findDraft(CODE, false).orElseThrow().revision()).isEqualTo(1);
        assertThat(service.versions(CODE, 20, null).items()).hasSize(1);
    }

    private int saveAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        signIn();
        try {
            inTransaction(() -> service.update(CODE, 1, initial));
            return 200;
        } catch (ApiException conflict) {
            assertThat(conflict.getMessageKey()).isEqualTo("error.common.revision_conflict");
            return 409;
        } finally {
            SecurityContext.clear();
        }
    }

    private FormStudioVersionPackage prepareAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        signIn();
        try {
            return inTransaction(() -> service.prepare(CODE, 1));
        } finally {
            SecurityContext.clear();
        }
    }

    private MdFormStudioService service(AuditLogService logging) {
        return new MdFormStudioService(repo, metadata, declarations, packages, logging, json);
    }

    private long count(String table, String condition) {
        return jdbc.sql("select count(*) from " + table + " where " + condition)
                .query(Long.class)
                .single();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                actor, "studio", "studio@example.test", null, false, Set.of("*.*"), 1, false, 1, null));
    }
}
