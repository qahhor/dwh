package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.service.SearchReconciliationService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class SearchReconciliationTest extends SearchDeliveryTestSupport {
    @Test void failedProofConstructionCannotDropTemporaryObjectsItDidNotCreate() throws Exception {
        activeGeneration();
        try (var connection=database.getConnection()) {
            var existing=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var session=org.springframework.jdbc.core.simple.JdbcClient.create(existing);
            session.sql("create temporary table search_reconcile_index(id integer)").update();
            session.sql("insert into search_reconcile_index values(7)").update();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SearchReconciliationService(existing,reader,client)
                    .begin(state.deliveryGeneration(owner).orElseThrow())).isInstanceOf(RuntimeException.class);
            assertThat(session.sql("select to_regclass('pg_temp.search_reconcile_index') is not null").query(Boolean.class).single()).isTrue();
            assertThat(session.sql("select id from search_reconcile_index").query(Integer.class).single()).isEqualTo(7);
        }
    }
    @Test void unversionedSourceIsReportedPendingWithoutCreatingARevision() {
        activeGeneration();
        jdbc.sql("insert into md_users(name,login,email) values('Unversioned','unversioned','unversioned@example.invalid')").update();
        try (var proof=new SearchReconciliationService(database,reader,client).begin(state.deliveryGeneration(owner).orElseThrow())) {
            finish(proof);assertThat(proof.summary().missing()).isOne();assertThat(proof.summary().pending()).isOne();
        }
        assertThat(jdbc.sql("select count(*) from search_projection_versions").query(Long.class).single()).isZero();
    }
    @Test void streamedComparisonFindsMissingExtraAndTamperedBodiesWithoutWritingTheIndex() {
        activeGeneration();
        long missing=user("Missing"), tampered=user("Original");
        worker.runOnce();
        documents.remove("users/"+missing);
        var changed=new HashMap<String,Object>(documents.get("users/"+tampered)); changed.put("name","Tampered");
        documents.put("users/"+tampered,changed);
        documents.put("users/999999",Map.of("id","999999","user_id",999999,"name","Extra"));
        var before=List.copyOf(writes);
        var reconciliation=new SearchReconciliationService(database,reader,client);
        try (var proof=reconciliation.begin(state.deliveryGeneration(owner).orElseThrow())) {
            finish(proof);
            assertThat(proof.summary().missing()).isOne();
            assertThat(proof.summary().extra()).isOne();
            assertThat(proof.summary().mismatched()).isOne();
            assertThat(proof.summary().pending()).isZero();
            assertThat(proof.summary().successful()).isFalse();
        }
        assertThat(writes).isEqualTo(before);
    }

    @Test void deliveredInterveningRevisionInvalidatesAnOtherwiseSuccessfulProof() {
        activeGeneration();
        long id=user("Before"); worker.runOnce();
        var reconciliation=new SearchReconciliationService(database,reader,client);
        try (var proof=reconciliation.begin(state.deliveryGeneration(owner).orElseThrow())) {
            finish(proof); assertThat(proof.summary().successful()).isTrue();
            assertThat(proof.revisionsUnchanged()).isTrue();
            users.updateUser(id,"After",null,null,null,null,null,null,null,null,id); worker.runOnce();
            assertThat(delivered("USER",id)).isEqualTo(2);
            assertThat(proof.revisionsUnchanged()).isFalse();
        }
    }

    @Test void sourceNewerThanDeliveryIsPendingAndMissingMetadataNeverVerifies() {
        activeGeneration(); long id=user("Before");worker.runOnce();
        users.updateUser(id,"After",null,null,null,null,null,null,null,null,id);
        var changed=new HashMap<String,Object>(documents.get("users/"+id));
        changed.remove("_projection_revision");changed.remove("_projection_fingerprint");documents.put("users/"+id,changed);
        try (var proof=new SearchReconciliationService(database,reader,client).begin(state.deliveryGeneration(owner).orElseThrow())) {
            finish(proof);assertThat(proof.summary().pending()).isOne();assertThat(proof.summary().successful()).isFalse();
        }
    }

    private void finish(SearchReconciliationService.Proof proof) {
        int cycles=0;while (!proof.advance() && cycles++<100) {}
        assertThat(cycles).isLessThan(100);
    }
}
