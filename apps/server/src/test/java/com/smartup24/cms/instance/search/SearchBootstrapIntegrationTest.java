package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.security.RoleMembershipAuthorizer;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.bootstrap.InstanceBootstrap;
import com.smartup24.cms.instance.config.bootstrap.InstanceBootstrapProperties;
import com.smartup24.cms.instance.kauth.service.KauthPasswordHasher;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.repository.SearchFallbackRepository;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchAccessPolicy;
import com.smartup24.cms.instance.search.service.SearchDeliveryWorker;
import com.smartup24.cms.instance.search.service.SearchExecutionSnapshotReader;
import com.smartup24.cms.instance.search.service.SearchJobWorker;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchResultBudget;
import com.smartup24.cms.instance.search.service.SearchService;
import com.smartup24.cms.instance.search.service.SearchWorkerCoordinator;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class SearchBootstrapIntegrationTest extends SearchDeliveryTestSupport {
    @Test
    void activationWaitsForThePublishingTransactionAndRejectsItsUndeliveredRevision() throws Exception {
        long id = user("Before activation");
        collections.addAll(List.of("tasks", "projects", "users"));
        var generation = UUID.randomUUID();
        jdbc.sql("""
                insert into search_generations(id,state,task_collection,project_collection,user_collection,
                    schema_version,schema_profile,settings_version,discovery_entity)
                values (:id,'BUILDING','tasks','projects','users',1,'MIXED',1,'DONE')
                """).param("id", generation).update();
        var claim = delivery.claim(generation, owner, clock.instant(), 100).getFirst();
        var projection = reader.read("USER", id).orElseThrow();
        client.documents().upsertDocument("users", projection.document());
        delivery.acknowledge(claim, projection.fingerprint());
        var receipt = jobRepository.insert(
                new SearchManagementDtos.StartJobRequest(UUID.randomUUID(), "REBUILD", null), generation, null);
        jobRepository.claim(owner);
        jobRepository.checkpoint(receipt.id(), owner, "ACTIVATING", 1, 0, null);
        var frozen = generationRepository.find(generation).orElseThrow();
        try (var proof = reconciliation.begin(frozen.delivery(1))) {
            while (!proof.advance()) {
                /* bounded fixture units */
            }
            var changed = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var activating = new CountDownLatch(1);
            int pid = proof.transaction(connection -> connection
                    .sql("select pg_backend_pid()")
                    .query(Integer.class)
                    .single());
            try (var executor = Executors.newFixedThreadPool(2)) {
                var publishing = executor.submit(() -> tx.executeWithoutResult(status -> {
                    users.updateUser(id, "After activation", null, null, null, null, null, null, null, null, id, 1L);
                    changed.countDown();
                    SearchRevisionIntegrationTest.await(commit);
                }));
                Future<Boolean> activation = null;
                try {
                    assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                    activation = executor.submit(() -> {
                        activating.countDown();
                        return generationService.finish(
                                proof, frozen, jobRepository.find(receipt.id()).orElseThrow(), owner, 1);
                    });
                    assertThat(activating.await(10, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    boolean blocked = false;
                    while (!activation.isDone() && System.nanoTime() < deadline) {
                        blocked = jdbc.sql("select cardinality(pg_blocking_pids(:pid))>0")
                                .param("pid", pid)
                                .query(Boolean.class)
                                .single();
                        if (blocked) break;
                    }
                    assertThat(blocked).isTrue();
                    assertThat(state.snapshot().initialized()).isFalse();
                } finally {
                    commit.countDown();
                }
                publishing.get(10, TimeUnit.SECONDS);
                assertThat(activation.get(10, TimeUnit.SECONDS)).isFalse();
            }
        }
        jobRepository.checkpoint(receipt.id(), owner, "RUNNING", 1, 0, null);
        for (int i = 0; i < 40 && !state.snapshot().initialized(); i++) runCycle();
        assertThat(state.snapshot().initialized()).isTrue();
        assertThat(documents.get("users/" + id)).containsEntry("name", "After activation");
    }

    @Test
    void queriesFallBackUntilActivationAndResolveCollectionsFreshForEveryQuery() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                99L, "admin", "admin@example.invalid", 1L, false, Set.of("*.*"), 1L, false, 0, null));
        try {
            var service = new SearchService(
                    client.search(),
                    new SearchFallbackRepository(jdbc),
                    new SearchAccessPolicy(mock(RoleMembershipAuthorizer.class)),
                    new SearchResultBudget(),
                    new SearchPolicyProvider(
                            new SearchOwnerRateLimits() {
                                @Override
                                public int userPerMinute() {
                                    return 600;
                                }

                                @Override
                                public int tokenPerMinute() {
                                    return 300;
                                }
                            },
                            new SearchSettingsRepository(jdbc)),
                    new SearchExecutionSnapshotReader(state));
            var fallback = service.search("nothing", "ALL", 10);
            assertThat(fallback.source()).isEqualTo("POSTGRES");
            assertThat(fallback.degraded()).isTrue();
            var id = activeGeneration();
            jdbc.sql(
                            "update search_generations set task_collection='v1_tasks',project_collection='v1_projects',user_collection='v1_users' where id=:id")
                    .param("id", id)
                    .update();
            beforeRequest = exchange -> jdbc.sql(
                            "update search_generations set task_collection='v2_tasks',project_collection='v2_projects',user_collection='v2_users' where id=:id")
                    .param("id", id)
                    .update();
            assertThat(service.search("nothing", "ALL", 10).source()).isEqualTo("TYPESENSE");
            assertThat(searchCollections.getFirst()).containsExactly("v1_tasks", "v1_projects", "v1_users");
            beforeRequest = exchange -> {};
            service.search("nothing", "ALL", 10);
            assertThat(searchCollections.getLast()).containsExactly("v2_tasks", "v2_projects", "v2_users");
        } finally {
            SecurityContext.clear();
        }
    }

    @Test
    void startupOutageRecoversAndMissingCollectionsUseOneGeneratedInitialGeneration() {
        long id = user("Recovery");
        failures.set(1);
        try {
            runCycle();
        } catch (TypesenseException expected) {
            /* startup retries next cycle */
        }
        assertThat(state.snapshot().initialized()).isFalse();
        for (int i = 0; i < 40 && !state.snapshot().initialized(); i++) runCycle();
        assertThat(state.snapshot().initialized()).isTrue();
        assertThat(state.snapshot().legacy()).isFalse();
        assertThat(state.snapshot().collections().values()).allMatch(name -> name.startsWith("cms_"));
        assertThat(jdbc.sql("select count(*) from search_jobs where action='REBUILD' and state='SUCCEEDED'")
                        .query(Long.class)
                        .single())
                .isOne();
        assertThat(schemas).hasSize(3);
        assertThat(documents.get(state.snapshot().collections().get("USER") + "/" + id))
                .containsEntry("name", "Recovery");
    }

    @Test
    void allExistingCollectionsAreRegisteredAsLegacyWithoutBeingRecreatedOrVerified() {
        collections.addAll(List.of("tasks", "projects", "users"));
        runCycle();
        assertThat(state.snapshot().initialized()).isTrue();
        assertThat(state.snapshot().legacy()).isTrue();
        assertThat(state.snapshot().collections())
                .isEqualTo(Map.of("TASK", "tasks", "PROJECT", "projects", "USER", "users"));
        assertThat(schemas).isEmpty();
        assertThat(jdbc.sql("select count(*) from search_generations where state='LEGACY' and verified_at is null")
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void partialLegacySetIsPreservedWhileTheNewGenerationBuilds() {
        collections.add("tasks");
        runCycle();
        assertThat(state.snapshot().initialized()).isFalse();
        for (int i = 0; i < 40 && !state.snapshot().initialized(); i++) runCycle();
        assertThat(collections).contains("tasks").hasSize(4);
        assertThat(state.snapshot().collections().values()).doesNotContain("tasks");
    }

    @Test
    void keysetDiscoveryIsBoundedAndResumesAfterRestartWithoutIncrementingExistingRevisions() {
        jdbc.sql("""
                insert into md_users(name,login,email,password_hash,state,language,timezone)
                select 'Discovered '||n,'discovery-'||n,'discovery-'||n||'@example.invalid','x','A','ru','UTC'
                from generate_series(1,205) n
                """).update();
        long first = jdbc.sql("select min(id) from md_users").query(Long.class).single();
        tx.executeWithoutResult(status -> {
            publisher.changed("USER", first);
            publisher.changed("USER", first);
        });
        for (int i = 0; i < 3; i++) runCycle();
        assertThat(jdbc.sql("select count(*) from search_projection_versions where entity_type='USER'")
                        .query(Long.class)
                        .single())
                .isEqualTo(100);
        assertThat(jdbc.sql(
                                "select revision from search_projection_versions where entity_type='USER' and entity_id=:id")
                        .param("id", first)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        var generation =
                jdbc.sql("select id from search_generations").query(UUID.class).single();
        long cursor = jdbc.sql("select discovery_after_id from search_generations")
                .query(Long.class)
                .single();
        assertThat(cursor).isGreaterThan(first);
        recreateWorker();
        for (int i = 0; i < 40 && !state.snapshot().initialized(); i++) runCycle();
        assertThat(state.snapshot().generationId()).isEqualTo(generation);
        assertThat(documents).hasSize(205);
        assertThat(schemas).hasSize(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedSchemasPreserveDefaultTokenizerAndIncludeDeliveryMetadata() {
        runCycle();
        assertThat(schemas).hasSize(3);
        for (var schema : schemas) {
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            assertThat(fields)
                    .anySatisfy(field -> assertThat(field)
                            .containsEntry("name", "_projection_revision")
                            .containsEntry("type", "int64"));
            assertThat(fields)
                    .anySatisfy(field -> assertThat(field)
                            .containsEntry("name", "_projection_fingerprint")
                            .containsEntry("type", "string"));
            assertThat(fields).allSatisfy(field -> {
                assertThat(field).doesNotContainKeys("locale", "enable_phonetic");
                assertThat(field.get("stem")).isIn(null, false);
            });
        }
    }

    private static SearchDeliveryWorker lifecycleWorker;
    private static SearchBootstrapIntegrationTest lifecycleFixture;

    @Test
    void backgroundNetworkStartsOnlyAfterCommittedBootstrapAndDoesNotBlockApplicationRunner() throws Exception {
        lifecycleFixture = this;
        lifecycleWorker = new SearchDeliveryWorker(client.documents(), reader, delivery, state, clock, () -> 0.5);
        var httpEntered = new CountDownLatch(1);
        var releaseHttp = new CountDownLatch(1);
        beforeRequest = exchange -> {
            assertThat(jdbc.sql("select count(*) from md_users where login='bootstrap-admin'")
                            .query(Long.class)
                            .single())
                    .isOne();
            httpEntered.countDown();
            SearchRevisionIntegrationTest.await(releaseHttp);
        };
        var app = new SpringApplication(BootstrapConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        // The development secrets of application.yml are refused outside the dev and test profiles (ADR-0027).
        app.setAdditionalProfiles("test");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var startup = executor.submit(() -> app.run());
            ConfigurableApplicationContext context = null;
            try {
                context = startup.get(10, TimeUnit.SECONDS);
                assertThat(httpEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(startup.isDone()).isTrue();
            } finally {
                releaseHttp.countDown();
                if (context != null) context.close();
            }
        }
    }

    @Test
    void migrateProfileDoesNotCreateTheCoordinator() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("migrate");
            context.registerBean(SearchDeliveryWorker.class, () -> worker);
            context.register(SearchWorkerCoordinator.class);
            context.refresh();
            assertThat(context.getBeansOfType(SearchWorkerCoordinator.class)).isEmpty();
            assertThat(requests.get()).isZero();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(SearchWorkerCoordinator.class)
    static class BootstrapConfiguration {
        @Bean
        DataSourceTransactionManager transactionManager() {
            return manager;
        }

        @Bean
        SearchDeliveryWorker searchWorker() {
            return lifecycleWorker;
        }

        @Bean
        SearchJobWorker searchJobWorker() {
            var f = lifecycleFixture;
            return new SearchJobWorker(
                    f.client.collections(),
                    lifecycleWorker,
                    f.state,
                    f.jobRepository,
                    f.generationRepository,
                    f.generationService,
                    f.jobService,
                    f.reconciliation,
                    f.storage);
        }

        @Bean
        InstanceBootstrap instanceBootstrap() {
            return new InstanceBootstrap(
                    jdbc,
                    new KauthPasswordHasher(),
                    new MdPermissionService(new MdPermissionRepository(jdbc)),
                    new InstanceBootstrapProperties(
                            "search-test",
                            "Search test",
                            "S",
                            "bootstrap-admin",
                            "bootstrap@example.invalid",
                            "synthetic-fixture-password"));
        }

        @Bean
        @Order(15)
        ApplicationRunner beforeReady() {
            return args -> {
                assertThat(lifecycleFixture.requests.get()).isZero();
                assertThat(jdbc.sql("select count(*) from md_users where login='bootstrap-admin'")
                                .query(Long.class)
                                .single())
                        .isOne();
            };
        }
    }
}
