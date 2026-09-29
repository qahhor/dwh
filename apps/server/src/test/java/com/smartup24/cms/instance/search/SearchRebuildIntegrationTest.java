package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.service.SearchQueryPolicy;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.typesense.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

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
        jdbc.sql(
                        "update search_generations set task_collection=:tasks,project_collection=:projects,user_collection=:users where id=:id")
                .param("tasks", prefix + "tasks")
                .param("projects", prefix + "projects")
                .param("users", prefix + "users")
                .param("id", old)
                .update();
        for (String type : List.of("TASK", "PROJECT", "USER"))
            client.collections().ensureCollection(state.snapshot().collections().get(type), type);
        long reporter = user("Reporter"),
                missing = task(reporter, "Before"),
                deleted = task(reporter, "Delete during catchup");
        worker.runOnce();
        client.documents().deleteDocument(prefix + "tasks", Long.toString(missing));
        client.documents()
                .importDocuments(
                        prefix + "tasks",
                        List.of(Map.of(
                                "id",
                                "999999",
                                "task_id",
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
            if (!updated && !discovered.equals("TASK")) {
                tasks.updateTask(missing, "Changed", null, null, null, null, null, null, reporter);
                updated = true;
            }
            long delivered = jdbc.sql(
                            "select coalesce(max(delivered_revision),0) from search_generation_delivery where generation_id=:generation and entity_type='TASK' and entity_id=:id")
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
                    publisher.changed("TASK", deleted);
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
                .forEachDocumentMetadata(state.snapshot().collections().get("TASK"), exported::add);
        assertThat(exported)
                .extracting(TypesenseDocumentStream.DocumentMetadata::id)
                .containsExactly(Long.toString(missing));
        var authoritative = reader.read("TASK", missing).orElseThrow();
        assertThat(exported.getFirst().revision()).isEqualTo(authoritative.revision());
        assertThat(exported.getFirst().fingerprint()).isEqualTo(authoritative.fingerprint());
        assertThat(exported.getFirst().contentFingerprint()).isEqualTo(authoritative.fingerprint());
        assertThat(client.search()
                        .multiSearch(
                                "Changed", "TASK", 10, state.snapshot().collections(), SearchQueryPolicy.defaults())
                        .getFirst()
                        .hits())
                .extracting(SearchService.SearchHit::id)
                .containsExactly(Long.toString(missing));
        for (String type : List.of("tasks", "projects", "users"))
            assertThat(client.collections().collectionExists(prefix + type)).isTrue();
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
    void ruGenerationSchemaRoundtripsThroughRealTypesense271() {
        var client = client();
        assertThat(client.health().observeDependency().version()).isEqualTo("27.1");
        for (String type : List.of("TASK", "PROJECT", "USER")) {
            String collection = "ru_" + UUID.randomUUID().toString().replace("-", "");
            client.collections().ensureCollection(collection, type, "RU");
            assertThat(client.collections()
                            .observeCollection(collection, type, "RU")
                            .schemaMatches())
                    .isTrue();
            assertThat(client.collections()
                            .observeCollection(collection, type, "MIXED")
                            .schemaMatches())
                    .isFalse();
            Map<String, Object> document = switch (type) {
                case "TASK" ->
                    Map.of(
                            "id",
                            "7",
                            "task_id",
                            7,
                            "title",
                            "Поставка",
                            "_projection_revision",
                            1,
                            "_projection_fingerprint",
                            "fixture");
                case "PROJECT" ->
                    Map.of(
                            "id",
                            "7",
                            "project_id",
                            7,
                            "name",
                            "Поставка",
                            "state",
                            "A",
                            "_projection_revision",
                            1,
                            "_projection_fingerprint",
                            "fixture");
                default ->
                    Map.of(
                            "id",
                            "7",
                            "user_id",
                            7,
                            "name",
                            "Поставка",
                            "login",
                            "identifier",
                            "email",
                            "fixture@example.invalid",
                            "state",
                            "A",
                            "_projection_revision",
                            1,
                            "_projection_fingerprint",
                            "fixture");
            };
            assertThat(client.documents().importDocuments(collection, List.of(document)))
                    .singleElement()
                    .satisfies(ack -> assertThat(ack.success()).isTrue());
            var exported = new ArrayList<TypesenseDocumentStream.DocumentMetadata>();
            client.documents().forEachDocumentMetadata(collection, exported::add);
            assertThat(exported).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo("7");
                assertThat(row.revision()).isOne();
            });
        }
    }
}
