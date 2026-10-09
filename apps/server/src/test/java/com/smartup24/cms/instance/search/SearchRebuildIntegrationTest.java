package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.api.SearchManagementDtos;
import com.smartup24.cms.instance.search.api.TypesenseProperties;
import com.smartup24.cms.instance.search.repository.SearchDocumentSql;
import com.smartup24.cms.instance.search.service.SearchEntity;
import com.smartup24.cms.instance.search.service.SearchFieldPolicies;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.typesense.*;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch.CollectionQuery;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * A rebuild on a real Typesense 27.1 (ADR-0032, 10.3): one collection per entity with the SEARCH capability, built from
 * its declaration, catches the changes made while it runs and activates; the schemas of both profiles round-trip.
 */
@Testcontainers
class SearchRebuildIntegrationTest extends SearchDeliveryTestSupport {
    private static final String FIXTURE_KEY = UUID.randomUUID().toString();

    @Container
    static final GenericContainer<?> engine = new GenericContainer<>("typesense/typesense:27.1")
            .withTmpFs(Map.of("/data", "rw"))
            .withExposedPorts(8108)
            .withCommand("--data-dir=/data", "--api-key=" + FIXTURE_KEY)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    private TypesenseFixture client() {
        return TypesenseFixture.of(
                new TypesenseProperties(
                        "http://" + engine.getHost() + ":" + engine.getMappedPort(8108), FIXTURE_KEY, true, false),
                new ObjectMapper());
    }

    @BeforeEach
    void ownRealEngine() {
        client = client();
        recreateWorker();
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContext.clear();
    }

    @Test
    void rebuildCatchesConcurrentSourceChangesAndReplacesStaleHitsWithoutDeletingOldCollections() {
        UUID old = activeGeneration();
        String prefix = "seed_" + old.toString().replace("-", "") + "_";
        renameCollections(old, prefix);
        for (SearchEntity entity : entities.all())
            client.collections().ensureCollection(state.snapshot().collections().get(entity.code()), entity);
        String oldTasks = state.snapshot().collections().get(SearchTestEntities.TASKS);
        long reporter = user("Reporter"),
                missing = task(reporter, "Before"),
                deleted = task(reporter, "Delete during catchup");
        worker.runOnce();
        client.documents().deleteDocument(oldTasks, Long.toString(missing));
        client.documents()
                .importDocuments(
                        oldTasks,
                        List.of(Map.of(
                                "id",
                                "999999",
                                SearchDocumentSql.RECORD_ID,
                                999999,
                                "title",
                                "Stale extra",
                                "_projection_revision",
                                1,
                                "_projection_fingerprint",
                                "stale")));
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                reporter, "fixture", "fixture@example.invalid", 1L, false, Set.of("*.*"), 1L, false, 0, null));
        UUID job = jobService
                .start(new SearchManagementDtos.StartJobRequest(UUID.randomUUID(), "REBUILD", null))
                .id();
        UUID candidate = jobRepository.find(job).orElseThrow().generationId();
        boolean updated = false, removed = false;
        for (int cycle = 0;
                cycle < 80
                        && !Set.of("SUCCEEDED", "FAILED")
                                .contains(jobRepository.find(job).orElseThrow().state());
                cycle++) {
            runCycle();
            String discovered = jdbc.sql("select discovery_entity from search_generations where id=:id")
                    .param("id", candidate)
                    .query(String.class)
                    .single();
            if (!updated && !discovered.equals(SearchTestEntities.TASKS)) {
                tasks.rename(missing, "Changed", reporter);
                updated = true;
            }
            long delivered = jdbc.sql(
                            "select coalesce(max(delivered_revision),0) from search_generation_delivery where generation_id=:generation and entity_type='ms.tasks' and entity_id=:id")
                    .param("generation", candidate)
                    .param("id", deleted)
                    .query(Long.class)
                    .single();
            if (!removed && delivered > 0) {
                tx.executeWithoutResult(transaction -> {
                    jdbc.sql("delete from ms_task_members where task_id=:id")
                            .param("id", deleted)
                            .update();
                    jdbc.sql("delete from ms_tasks where id=:id")
                            .param("id", deleted)
                            .update();
                    publisher.changed(SearchTestEntities.TASKS, deleted);
                });
                removed = true;
            }
        }
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(updated).isTrue();
        assertThat(removed).isTrue();
        assertThat(state.snapshot().generationId()).isEqualTo(candidate);
        var exported = new ArrayList<TypesenseDocumentStream.DocumentMetadata>();
        client.documents()
                .forEachDocumentMetadata(state.snapshot().collections().get(SearchTestEntities.TASKS), exported::add);
        assertThat(exported)
                .extracting(TypesenseDocumentStream.DocumentMetadata::id)
                .containsExactly(Long.toString(missing));
        var authoritative = reader.read(SearchTestEntities.TASKS, missing).orElseThrow();
        assertThat(exported.getFirst().revision()).isEqualTo(authoritative.revision());
        assertThat(exported.getFirst().fingerprint()).isEqualTo(authoritative.fingerprint());
        assertThat(exported.getFirst().contentFingerprint()).isEqualTo(authoritative.fingerprint());
        SearchEntity tasksEntity = entities.find(SearchTestEntities.TASKS).orElseThrow();
        assertThat(client.search()
                        .multiSearch(
                                "Changed",
                                List.of(new CollectionQuery(
                                        tasksEntity,
                                        state.snapshot().collections().get(SearchTestEntities.TASKS),
                                        SearchFieldPolicies.defaults(tasksEntity),
                                        null,
                                        10)))
                        .getFirst()
                        .hits())
                .extracting(SearchService.SearchHit::id)
                .containsExactly(Long.toString(missing));
        for (SearchEntity entity : entities.all())
            assertThat(client.collections().collectionExists(entity.collection(prefix)))
                    .isTrue();
        assertThat(jdbc.sql("select state from search_generations where id=:id")
                        .param("id", old)
                        .query(String.class)
                        .single())
                .isEqualTo("RETAINED");
        assertThat(jdbc.sql(
                                "select count(*) from audit_log where table_name='search_index_state' and new_row->>'job_id'=:job and changed_by=:actor")
                        .param("job", job.toString())
                        .param("actor", reporter)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void schemasOfBothProfilesRoundtripThroughRealTypesense271() {
        var client = client();
        assertThat(client.health().observeDependency().version()).isEqualTo("27.1");
        for (SearchEntity entity : entities.all()) {
            for (String profile : List.of("RU", "MIXED")) {
                String collection = "p_" + UUID.randomUUID().toString().replace("-", "");
                client.collections().ensureCollection(collection, entity, profile);
                assertThat(client.collections()
                                .observeCollection(collection, entity, profile)
                                .schemaMatches())
                        .as("%s %s", entity.code(), profile)
                        .isTrue();
                assertThat(client.collections()
                                .observeCollection(collection, entity, profile.equals("RU") ? "MIXED" : "RU")
                                .schemaMatches())
                        .isFalse();
                Map<String, Object> document = Map.of(
                        "id",
                        "7",
                        SearchDocumentSql.RECORD_ID,
                        7,
                        entity.titleField().key(),
                        "Поставка",
                        SearchDocumentSql.SCOPE_USERS,
                        List.of(3, 4),
                        SearchDocumentSql.SCOPE_UNITS,
                        List.of(),
                        "_projection_revision",
                        1,
                        "_projection_fingerprint",
                        "fixture");
                assertThat(client.documents().importDocuments(collection, List.of(document)))
                        .singleElement()
                        .satisfies(ack -> assertThat(ack.success()).isTrue());
                var exported = new ArrayList<TypesenseDocumentStream.DocumentMetadata>();
                client.documents().forEachDocumentMetadata(collection, exported::add);
                assertThat(exported).singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo("7");
                    assertThat(row.revision()).isOne();
                });
                // The scope keys filter the documents (ADR-0032, 10.3): a viewer of another user finds nothing.
                var query = new CollectionQuery(
                        entity, collection, SearchFieldPolicies.defaults(entity), "scope_users:=3", 10);
                assertThat(client.search()
                                .multiSearch("Поставка", List.of(query))
                                .getFirst()
                                .hits())
                        .extracting(SearchService.SearchHit::id)
                        .containsExactly("7");
                var other = new CollectionQuery(
                        entity, collection, SearchFieldPolicies.defaults(entity), "scope_users:=5", 10);
                assertThat(client.search()
                                .multiSearch("Поставка", List.of(other))
                                .getFirst()
                                .hits())
                        .isEmpty();
            }
        }
    }
}
