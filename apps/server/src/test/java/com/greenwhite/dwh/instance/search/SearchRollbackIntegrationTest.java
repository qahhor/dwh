package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.typesense.*;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.StartJobRequest;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class SearchRollbackIntegrationTest extends SearchDeliveryTestSupport {
    private static final String KEY=UUID.randomUUID().toString();
    @Container static final GenericContainer<?> engine=new GenericContainer<>("typesense/typesense:27.1")
            .withTmpFs(Map.of("/data","rw")).withExposedPorts(8108).withCommand("--data-dir=/data","--api-key="+KEY)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));
    @BeforeEach void realEngine() {
        client=new TypesenseClient(new TypesenseProperties("http://"+engine.getHost()+":"+engine.getMappedPort(8108),KEY,true,false),mapper);
        recreateWorker();
    }
    @AfterEach void clearContext() { SecurityContext.clear(); }
    @Test void rollbackCatchesUpRetainedGenerationAndNeverResurrectsDeletedOrExcludedRows() {
        long reporter=user("Reporter"),deleted=task(reporter,"Must disappear"),excluded=user("To exclude");
        for (int i=0;i<60 && !state.snapshot().initialized();i++) runCycle();
        UUID original=state.snapshot().generationId();
        assertThat(original).isNotNull();
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(reporter,"fixture","fixture@example.invalid",1L,false,Set.of("*.*"),1L,false,0,null));
        UUID rebuild=jobService.start(new StartJobRequest(UUID.randomUUID(),"REBUILD",null)).id();
        complete(rebuild);
        assertThat(generationRepository.find(original).orElseThrow().state()).isEqualTo("RETAINED");
        tx.executeWithoutResult(transaction -> {
            jdbc.sql("delete from ms_task_members where task_id=:id").param("id",deleted).update();
            jdbc.sql("delete from ms_tasks where id=:id").param("id",deleted).update();publisher.changed("TASK",deleted);
            jdbc.sql("update md_users set state='P' where id=:id").param("id",excluded).update();publisher.changed("USER",excluded);
        });
        worker.runOnce();
        UUID rollback=jobService.start(new StartJobRequest(UUID.randomUUID(),"ROLLBACK",original)).id();
        complete(rollback);
        assertThat(state.snapshot().generationId()).isEqualTo(original);
        var rows=new ArrayList<TypesenseDocumentStream.DocumentMetadata>();
        client.forEachDocumentMetadata(state.snapshot().collections().get("TASK"),rows::add);
        assertThat(rows).isEmpty();
        client.forEachDocumentMetadata(state.snapshot().collections().get("USER"),rows::add);
        assertThat(rows).extracting(TypesenseDocumentStream.DocumentMetadata::id).containsExactly(Long.toString(reporter));
        assertThat(rows.getFirst().contentFingerprint()).isEqualTo(reader.read("USER",reporter).orElseThrow().fingerprint());
    }
    private void complete(UUID job) {
        for (int i=0;i<60 && !Set.of("SUCCEEDED","FAILED").contains(jobRepository.find(job).orElseThrow().state());i++) runCycle();
        assertThat(jobRepository.find(job).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
}
