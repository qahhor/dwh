package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioDefinitions;
import com.smartup24.cms.instance.studio.service.StudioDefinitions.Broken;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One stored live form that fails to load is left out; everything else keeps serving (ADR-0038). */
class StudioCatalogResilienceTest {
    private final ObjectMapper json = JsonMapper.shared();
    private HikariDataSource database;
    private JdbcClient jdbc;
    private StudioFormRepository repository;
    private long author;

    @BeforeEach
    void database() {
        database = (HikariDataSource) TestDatabases.migratedCopy("studio_resilience");
        jdbc = JdbcClient.create(database);
        repository = new StudioFormRepository(jdbc, json);
        author = jdbc.sql("insert into md_users(name, login, email) "
                        + "values ('Test', 'studio_resilience', 'resilience@example.invalid') returning id")
                .query(Long.class)
                .single();
    }

    @AfterEach
    void close() {
        RequestContextHolder.resetRequestAttributes();
        database.close();
    }

    @Test
    void aDeclarationThatNoLongerCompilesIsLeftOutAndTheOthersAndDeclaredEntitiesServe() {
        insert("alpha");
        insertBroken("beta", field -> field.put("type", "money"));
        insert("gamma");
        StudioDefinitions definitions = new StudioDefinitions(repository, new StudioEntityCompiler());
        assertThat(definitions.snapshot()).containsOnlyKeys("studio.alpha", "studio.gamma");
        assertThat(definitions.current().broken()).containsExactly(new Broken("studio.beta", "declaration"));

        EntityDefinition fixed = fixed("ms.fixed");
        EntityRegistry registry = registry(List.of(fixed), definitions);
        assertThat(registry.find("ms.fixed")).isPresent();
        assertThat(registry.find("studio.alpha")).isPresent();
        assertThat(registry.find("studio.beta")).isEmpty();
        assertThat(registry.all())
                .extracting(EntityDefinition::code)
                .containsExactly("ms.fixed", "studio.alpha", "studio.gamma");
    }

    @Test
    void aStoredFormUnderADeclaredCodeLosesToTheDeclaredEntityAndIsReported() {
        insert("alpha");
        insert("beta");
        StudioDefinitions definitions = definitions(Set.of("studio.alpha"), Set.of(), null);
        assertThat(definitions.snapshot()).containsOnlyKeys("studio.beta");
        assertThat(definitions.current().broken()).containsExactly(new Broken("studio.alpha", "declared_code"));
        EntityDefinition fixed = fixed("studio.alpha");
        EntityRegistry registry = registry(List.of(fixed), definitions);
        assertThat(registry.find("studio.alpha")).containsSame(fixed);
        assertThat(registry.all()).extracting(EntityDefinition::code).containsExactly("studio.alpha", "studio.beta");
    }

    @Test
    void labelsShadowingBundledTranslationKeysAreReportedInsteadOfBreakingTheDictionary() {
        insert("alpha");
        insert("beta");
        StudioDefinitions definitions = definitions(Set.of(), Set.of("studio.live.alpha.title"), null);
        assertThat(definitions.snapshot()).containsOnlyKeys("studio.beta");
        assertThat(definitions.current().broken()).containsExactly(new Broken("studio.alpha", "translation_key"));
    }

    @Test
    void aCatalogBeyondTheLimitServesTheFirstHundredAndReportsTheRest() {
        for (int i = 0; i < 101; i++) insert("bulk" + i);
        StudioDefinitions definitions = new StudioDefinitions(repository, new StudioEntityCompiler());
        assertThat(definitions.snapshot()).hasSize(100).doesNotContainKey("studio.bulk100");
        assertThat(definitions.current().broken()).containsExactly(new Broken("studio.bulk100", "form_limit"));
    }

    @Test
    void quarantineWithdrawsAFormOnEveryNodeWithoutNoticesAndKeepsItsRights() {
        long alpha = insert("alpha");
        long beta = insertBroken("beta", field -> field.put("type", "money"));
        StudioDefinitions first = new StudioDefinitions(repository, new StudioEntityCompiler());
        StudioDefinitions second = new StudioDefinitions(repository, new StudioEntityCompiler());
        assertThat(first.snapshot()).containsOnlyKeys("studio.alpha");
        assertThat(second.current().broken()).hasSize(1);

        assertThat(repository.quarantine(alpha, "Wrong form", author)).isPresent();
        assertThat(repository.quarantine(alpha, "Again", author)).isEmpty();
        assertThat(repository.quarantine(beta, "Does not compile", author)).isPresent();
        assertThat(first.snapshot()).isEmpty();
        assertThat(second.snapshot()).isEmpty();
        assertThat(second.current().broken()).isEmpty();
        assertThat(second.current().quarantined()).isEqualTo(1);
        assertThat(second.registered()).containsOnlyKeys("studio.alpha");

        assertThat(repository.release(alpha)).isTrue();
        assertThat(first.snapshot()).containsOnlyKeys("studio.alpha");
        assertThat(second.snapshot()).containsOnlyKeys("studio.alpha");
        assertThat(repository.release(alpha)).isFalse();
    }

    @Test
    void aSwitchedOffModuleServesNothingAndNeverReadsTheCatalog() {
        insertBroken("beta", field -> field.put("type", "money"));
        var unreadable = new StudioFormRepository(jdbc, json) {
            @Override
            public Catalog catalog() {
                throw new AssertionError("The catalog of a switched-off module is read");
            }

            @Override
            public Version version() {
                throw new AssertionError("The catalog of a switched-off module is read");
            }
        };
        var definitions =
                new StudioDefinitions(unreadable, new StudioEntityCompiler(), module -> false, Set::of, Set::of, null);
        assertThat(definitions.snapshot()).isEmpty();
        assertThat(registry(List.of(), definitions).all()).isEmpty();
        assertThat(registry(List.of(), definitions).find("studio.beta")).isEmpty();
    }

    @Test
    void theCompiledCatalogIsReusedWhileItsVersionStaysAndDroppedByAChangeOrANotice() {
        insert("alpha");
        AtomicInteger compiled = new AtomicInteger();
        StudioEntityCompiler counting = new StudioEntityCompiler() {
            @Override
            public EntityDefinitionSource.Declaration compile(long id, Declaration source) {
                compiled.incrementAndGet();
                return super.compile(id, source);
            }
        };
        List<Runnable> handlers = new ArrayList<>();
        List<String> published = new ArrayList<>();
        ClusterNotices notices = new ClusterNotices() {
            @Override
            public void publish(String name) {
                published.add(name);
            }

            @Override
            public void onNotice(String name, Runnable handler) {
                if (StudioDefinitions.NOTICE.equals(name)) handlers.add(handler);
            }

            @Override
            public void send(String topic, String message) {}

            @Override
            public void onMessage(String topic, Consumer<String> handler) {}
        };
        var definitions = new StudioDefinitions(repository, counting, module -> true, Set::of, Set::of, notices);
        for (int i = 0; i < 5; i++) assertThat(definitions.snapshot()).containsOnlyKeys("studio.alpha");
        assertThat(compiled).hasValue(1);
        withRequest(() -> {
            var pinned = definitions.snapshot();
            insert("beta");
            assertThat(definitions.snapshot()).isSameAs(pinned);
        });
        assertThat(definitions.snapshot()).containsOnlyKeys("studio.alpha", "studio.beta");
        assertThat(compiled).hasValue(3);
        handlers.forEach(Runnable::run);
        definitions.snapshot();
        assertThat(compiled).hasValue(5);
        definitions.changed();
        assertThat(published).containsExactly(StudioDefinitions.NOTICE);
        definitions.snapshot();
        assertThat(compiled).hasValue(7);
    }

    @Test
    void theStartupCatalogSyncSurvivesABrokenFormAndKeepsTheRightsOfAQuarantinedOne() {
        long alpha = insert("alpha");
        insertBroken("beta", field -> field.put("type", "money"));
        repository.quarantine(alpha, "Hold", author);
        var definitions = new StudioDefinitions(repository, new StudioEntityCompiler());
        var live = new LiveEntities(() -> List.of(definitions));
        var registry = registry(List.of(), definitions);
        var permissions =
                new MdPermissionService(new MdPermissionRepository(jdbc), registry, new MdI18nCatalog(json), live);
        Set<String> declared = new MdPermissionRepository(jdbc).getGrantablePairs();
        new TransactionTemplate(new DataSourceTransactionManager(database))
                .executeWithoutResult(status -> permissions.syncFormCatalog(declared));
        assertThat(registry.find("studio.alpha")).isEmpty();
        assertThat(permissions.getGrantablePairs()).contains("studio.alpha.view", "studio.alpha.delete");
    }

    private static EntityDefinition fixed(String code) {
        return Entity.define(code, code)
                .table("ms_fixed", "t")
                .scope(EntityScope.all())
                .field(text("name", "fixed.name").column("name").required())
                .section("main", "entity.section.main", "name")
                .defaultSort("name", Entity.Sort.ASC)
                .build();
    }

    private StudioDefinitions definitions(Set<String> codes, Set<String> keys, ClusterNotices notices) {
        return new StudioDefinitions(
                repository, new StudioEntityCompiler(), module -> true, () -> codes, () -> keys, notices);
    }

    private static EntityRegistry registry(List<EntityDefinition> declared, StudioDefinitions definitions) {
        var live = new LiveEntities(() -> List.of(definitions));
        var beans = new StaticListableBeanFactory(Map.of("live", live));
        return new EntityRegistry(
                declared,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                beans.getBeanProvider(EntityRecordStore.class),
                beans.getBeanProvider(LiveEntities.class));
    }

    private long insert(String name) {
        DeliveryPackage value = pack(name);
        return repository.insert("studio." + name, value.declaration(), value.checksum(), author);
    }

    /** A row the activation would refuse today, as a later compiler change could leave a stored one. */
    private long insertBroken(String name, Consumer<ObjectNode> firstField) {
        ObjectNode declaration = json.valueToTree(pack(name).declaration());
        firstField.accept((ObjectNode) declaration.path("fields").get(0));
        return jdbc.sql("insert into studio_forms (code, declaration, checksum, created_by) "
                        + "values (:code, cast(:declaration as jsonb), repeat('a', 64), :author) returning id")
                .param("code", "studio." + name)
                .param("declaration", json.writeValueAsString(declaration))
                .param("author", author)
                .query(Long.class)
                .single();
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

    private static void withRequest(Runnable action) {
        var request = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(request);
        try {
            action.run();
        } finally {
            request.requestCompleted();
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
