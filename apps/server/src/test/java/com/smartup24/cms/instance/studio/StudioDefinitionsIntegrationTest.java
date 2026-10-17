package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioDefinitions;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Independent readers converge without notifications; an in-flight request retains one snapshot (ADR-0038). */
class StudioDefinitionsIntegrationTest {
    private final ObjectMapper json = JsonMapper.shared();
    private HikariDataSource database;
    private StudioFormRepository repository;
    private long author;

    @BeforeEach
    void database() {
        database = (HikariDataSource) TestDatabases.migratedCopy("studio_snapshot");
        JdbcClient jdbc = JdbcClient.create(database);
        repository = new StudioFormRepository(jdbc, json);
        author = jdbc.sql(
                        "insert into md_users(name, login, email) values ('Test', 'studio_snapshot', 'snapshot@example.invalid') returning id")
                .query(Long.class)
                .single();
    }

    @AfterEach
    void close() {
        RequestContextHolder.resetRequestAttributes();
        database.close();
    }

    @Test
    void independentProvidersAndRestartReadCommittedDefinitionsWithoutNotifications() throws Exception {
        StudioDefinitions first = node();
        StudioDefinitions second = node();
        assertThat(first.snapshot()).isEmpty();
        assertThat(second.snapshot()).isEmpty();
        insert("alpha");
        assertThat(first.snapshot()).containsOnlyKeys("studio.alpha");
        assertThat(second.snapshot()).containsOnlyKeys("studio.alpha");
        insert("beta");
        assertThat(first.snapshot()).containsOnlyKeys("studio.alpha", "studio.beta");
        assertThat(second.snapshot()).containsOnlyKeys("studio.alpha", "studio.beta");
        assertThat(node().snapshot()).containsOnlyKeys("studio.alpha", "studio.beta");
    }

    @Test
    void oneRequestKeepsItsDefinitionAndMessagesWhileAnotherSeesTheNewCommit() throws Exception {
        insert("alpha");
        StudioDefinitions first = node();
        StudioDefinitions second = node();
        withRequest(() -> {
            Map<String, EntityDefinitionSource.Declaration> pinned = first.snapshot();
            DeliveryPackage beta = pack("beta");
            repository.insert("studio.beta", beta.declaration(), beta.checksum(), author);
            assertThat(first.snapshot()).isSameAs(pinned).containsOnlyKeys("studio.alpha");
            assertThat(withRequest(second::snapshot)).containsOnlyKeys("studio.alpha", "studio.beta");
            assertThat(first.snapshot()).isSameAs(pinned);
            assertThat(pinned.get("studio.alpha").messages().get("en"))
                    .containsEntry("studio.live.alpha.title", "Locations");
            return true;
        });
        assertThat(withRequest(first::snapshot)).containsOnlyKeys("studio.alpha", "studio.beta");
    }

    @Test
    void aRolledBackInsertionIsInvisibleToIndependentReaders() throws Exception {
        insert("alpha");
        StudioDefinitions first = node();
        StudioDefinitions second = node();
        DeliveryPackage beta = pack("beta");
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        transactions.executeWithoutResult(status -> {
            repository.insert("studio.beta", beta.declaration(), beta.checksum(), author);
            status.setRollbackOnly();
        });
        assertThat(first.snapshot()).containsOnlyKeys("studio.alpha");
        assertThat(second.snapshot()).containsOnlyKeys("studio.alpha");
    }

    @Test
    void snapshotsAndNestedTranslationsCannotBeChangedByConsumers() throws Exception {
        insert("alpha");
        var snapshot = node().snapshot();
        var declaration = snapshot.get("studio.alpha");
        assertThatThrownBy(() -> snapshot.put("studio.beta", declaration))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> declaration.messages().put("extra", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> declaration.messages().get("en").put("studio.live.alpha.title", "Changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(node().snapshot().get("studio.alpha").messages().get("en"))
                .containsEntry("studio.live.alpha.title", "Locations");
    }

    @Test
    void mismatchedStoredCodeIsLeftOutInsteadOfExposingAnotherDefinitionOrFailingTheOthers() throws Exception {
        DeliveryPackage alpha = pack("alpha");
        repository.insert("studio.other", alpha.declaration(), alpha.checksum(), author);
        insert("beta");
        StudioDefinitions node = node();
        assertThat(node.snapshot()).containsOnlyKeys("studio.beta");
        assertThat(node.current().broken())
                .containsExactly(new StudioDefinitions.Broken("studio.other", "code_mismatch"));
    }

    private StudioDefinitions node() {
        return new StudioDefinitions(
                new StudioFormRepository(JdbcClient.create(database), json), new StudioEntityCompiler());
    }

    private void insert(String name) {
        DeliveryPackage value = pack(name);
        repository.insert("studio." + name, value.declaration(), value.checksum(), author);
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

    private static <T> T withRequest(Supplier<T> action) {
        var previous = RequestContextHolder.getRequestAttributes();
        var request = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(request);
        try {
            return action.get();
        } finally {
            request.requestCompleted();
            RequestContextHolder.setRequestAttributes(previous);
        }
    }
}
