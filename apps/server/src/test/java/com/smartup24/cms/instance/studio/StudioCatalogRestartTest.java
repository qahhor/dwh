package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioDefinitions;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Rebuilds the startup catalog chain on a new database pool, without reusing runtime state (ADR-0038). */
class StudioCatalogRestartTest {
    @Test
    void reconstructedCatalogKeepsPersistedDefinitionsAndTheirGrants() throws Exception {
        String url;
        Set<String> staticPairs;
        try (var first = (HikariDataSource) TestDatabases.migratedCopy("studio_restart")) {
            url = first.getJdbcUrl();
            var jdbc = JdbcClient.create(first);
            staticPairs = new MdPermissionRepository(jdbc).getGrantablePairs();
            long author = jdbc.sql(
                            "insert into md_users(name,login,email) values('Test','studio_restart','restart@example.invalid') returning id")
                    .query(Long.class)
                    .single();
            var json = JsonMapper.shared();
            ObjectNode declaration = (ObjectNode)
                    json.readTree(Files.readAllBytes(Path.of("src/test/resources/studio/simple-form.json")));
            ((ObjectNode) declaration.path("module")).put("code", "studio");
            ((ObjectNode) declaration.path("entity")).put("name", "persisted");
            var pack = new MdSimpleFormDeclarations(json).prepare(json.writeValueAsBytes(declaration));
            long id = new StudioFormRepository(jdbc, json)
                    .insert("studio.persisted", pack.declaration(), pack.checksum(), author);
            var compiled = new StudioEntityCompiler().compile(id, pack.declaration());
            new TransactionTemplate(new DataSourceTransactionManager(first))
                    .executeWithoutResult(status -> new MdPermissionService(new MdPermissionRepository(jdbc))
                            .registerEntityDefinition(
                                    compiled.entity(), compiled.messages().get("ru")));
        }
        for (int restart = 0; restart < 2; restart++) {
            try (var fresh = TestDatabases.pooled(url, TestDatabases.USER, null, 2, "studio_restart_" + restart)) {
                verifyFreshCatalog(fresh, staticPairs);
            }
        }
    }

    private void verifyFreshCatalog(HikariDataSource database, Set<String> staticPairs) {
        var jdbc = JdbcClient.create(database);
        var json = JsonMapper.shared();
        var definitions = new StudioDefinitions(new StudioFormRepository(jdbc, json), new StudioEntityCompiler());
        var live = new LiveEntities(() -> List.of(definitions));
        var beans = new StaticListableBeanFactory(Map.of("live", live));
        var registry = new EntityRegistry(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                beans.getBeanProvider(EntityRecordStore.class),
                beans.getBeanProvider(LiveEntities.class));
        var permissions =
                new MdPermissionService(new MdPermissionRepository(jdbc), registry, new MdI18nCatalog(json), live);
        new TransactionTemplate(new DataSourceTransactionManager(database))
                .executeWithoutResult(status -> permissions.syncFormCatalog(staticPairs));
        assertThat(registry.find("studio.persisted")).isPresent();
        assertThat(permissions.getGrantablePairs())
                .contains(
                        "studio.persisted.view",
                        "studio.persisted.create",
                        "studio.persisted.update",
                        "studio.persisted.delete");
        assertThat(jdbc.sql("select name from md_forms where code='studio.persisted'")
                        .query(String.class)
                        .single())
                .isEqualTo(definitions
                        .snapshot()
                        .get("studio.persisted")
                        .messages()
                        .get("ru")
                        .get("studio.live.persisted.title"));
        assertThat(jdbc.sql(
                                "select count(*) from md_role_permissions p join md_roles r on r.id=p.role_id where p.form_code='studio.persisted' and r.pcode in ('admin','chief_admin')")
                        .query(Long.class)
                        .single())
                .isEqualTo(8L);
        assertThat(jdbc.sql("select count(*) from md_form_actions where form_code='studio.persisted' and is_deprecated")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
