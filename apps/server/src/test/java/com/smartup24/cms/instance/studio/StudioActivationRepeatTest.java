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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The activation answers "activated" only for a form that is served, and refuses a form that would not load before it
 * stores it (ADR-0038, decisions 9 and 12).
 */
class StudioActivationRepeatTest {
    private final ObjectMapper json = JsonMapper.shared();
    private final StudioEntityCompiler compiler = new StudioEntityCompiler();
    private final Set<String> declaredCodes = new HashSet<>();
    private final Set<String> bundledKeys = new HashSet<>();
    private HikariDataSource database;
    private JdbcClient jdbc;
    private StudioFormRepository repository;
    private StudioDefinitions definitions;
    private long author;

    @BeforeEach
    void setup() {
        database = (HikariDataSource) TestDatabases.migratedCopy("studio_activation_repeat");
        jdbc = JdbcClient.create(database);
        repository = new StudioFormRepository(jdbc, json);
        author = jdbc.sql("insert into md_users(name, login, email) "
                        + "values ('Test', 'studio_repeat', 'repeat@example.invalid') returning id")
                .query(Long.class)
                .single();
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                author, "repeat", null, null, false, Set.of("studio.forms.publish"), 1L, false, 1L, null));
        // The bundled keys are read once per definitions object, so each test builds the keys first.
        definitions = new StudioDefinitions(
                repository, compiler, module -> true, () -> Set.copyOf(declaredCodes), () -> bundledKeys, null);
    }

    @AfterEach
    void close() {
        SecurityContext.clear();
        database.close();
    }

    @Test
    void anExactRepeatOfAServedFormAnswersActivatedWithoutCreatingAnything() {
        assertThat(service().activate(bytes("served")).created()).isTrue();
        var repeat = service().activate(bytes("served"));
        assertThat(repeat.created()).isFalse();
        assertThat(repeat.form().entityCode()).isEqualTo("studio.served");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void anExactRepeatOfAQuarantinedFormIsAConflict() {
        service().activate(bytes("held"));
        repository.quarantine(repository.id("studio.held").orElseThrow(), "Hold", author);
        assertThatThrownBy(() -> service().activate(bytes("held")))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(error.getMessageKey()).isEqualTo("error.studio.quarantined");
                });
    }

    @Test
    void anExactRepeatOfAStoredFormThatDoesNotLoadIsAConflictWithItsReason() {
        service().activate(bytes("shadow"));
        bundledKeys.add("studio.live.shadow.title");
        var later = new StudioDefinitions(repository, compiler, module -> true, Set::of, () -> bundledKeys, null);
        assertThatThrownBy(() -> service(later).activate(bytes("shadow")))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(error.getMessageKey()).isEqualTo("error.studio.broken");
                    assertThat(error.getParams()).isEqualTo(Map.of("reason", "translation_key"));
                });
    }

    @Test
    void aNewFormWhoseLabelsShadowBundledKeysIsRefusedBeforeItIsStored() {
        bundledKeys.add("studio.live.clash.field.address");
        assertThatThrownBy(() -> service().activate(bytes("clash")))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(error.getMessageKey()).isEqualTo("error.studio.translation_key");
                });
        assertThat(repository.count()).isZero();
    }

    @Test
    void aNewFormUnderADeclaredCodeIsRefusedLikeAnOccupiedOne() {
        declaredCodes.add("studio.taken");
        assertThatThrownBy(() -> service().activate(bytes("taken")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getMessageKey()).isEqualTo("error.studio.exists"));
        assertThat(repository.count()).isZero();
    }

    private StudioActivationService service() {
        return service(definitions);
    }

    private StudioActivationService service(StudioDefinitions source) {
        return new StudioActivationService(
                new StudioPackages(json, new MdSimpleFormDeclarations(json)),
                repository,
                compiler,
                new MdPermissionService(new MdPermissionRepository(jdbc)),
                new AuditLogService(new AuditLogRepository(jdbc, json), null, new AuditDataRedactor(json)),
                module -> true,
                true,
                new EntityRegistry(List.of()),
                source);
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
