package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.VerificationSummary;
import com.greenwhite.dwh.instance.search.repository.SearchIndexStateRepository.Generation;
import com.greenwhite.dwh.instance.search.repository.SearchProjectionReader;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseDocumentStream;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

@Service
public class SearchReconciliationService {
    private final DataSource source;
    private final SearchProjectionReader reader;
    private final TypesenseClient client;
    public SearchReconciliationService(DataSource source,SearchProjectionReader reader,TypesenseClient client) {
        this.source=source;this.reader=reader;this.client=client;
    }
    public Proof begin(Generation generation) { return new Proof(source,reader,client,generation); }

    /** Session-owned TEMP objects are dropped before returning this dedicated connection to its pool. */
    public static final class Proof implements AutoCloseable {
        private static final List<String> TYPES=List.of("TASK","PROJECT","USER");
        private final Connection connection;
        private final JdbcClient jdbc;
        private final SearchProjectionReader reader;
        private final TypesenseClient client;
        private final Generation generation;
        private final Set<String> absent=new HashSet<>();
        private TypesenseDocumentStream stream;
        private int schemaStep,exportStep,sourceStep;
        private long after,processed;
        private boolean schemasMatch=true,closed,indexCreated,sourceCreated;
        private VerificationSummary summary;

        private Proof(DataSource source,SearchProjectionReader reader,TypesenseClient client,Generation generation) {
            this.reader=reader;this.client=client;this.generation=generation;
            Connection acquired=null;
            try { acquired=source.getConnection(); acquired.setAutoCommit(true); }
            catch (SQLException failure) {
                if (acquired!=null) try { acquired.close(); } catch (SQLException ignored) { /* acquisition failed */ }
                throw new IllegalStateException("RECONCILIATION_STORAGE_UNAVAILABLE");
            }
            connection=acquired;
            jdbc=JdbcClient.create(new SingleConnectionDataSource(connection,true));
            try {
                jdbc.sql("create temporary table search_reconcile_index(entity_type text,id text,revision bigint,fingerprint text,content_fingerprint text,primary key(entity_type,id))").update();
                indexCreated=true;
                jdbc.sql("create temporary table search_reconcile_source(entity_type text,entity_id bigint,revision bigint,fingerprint text,live boolean,pending boolean,primary key(entity_type,entity_id))").update();
                sourceCreated=true;
            } catch (RuntimeException failure) { close(); throw failure; }
        }

        /** Exactly one schema request, export page, or source keyset page per coordinator cycle. */
        public boolean advance() {
            if (closed) throw new IllegalStateException("RECONCILIATION_CLOSED");
            if (summary!=null) return true;
            if (schemaStep<TYPES.size()) {
                String type=TYPES.get(schemaStep++);
                var observed=client.observeCollection(generation.collections().get(type),type,generation.schemaProfile());
                if ("COLLECTION_MISSING".equals(observed.errorCode())) absent.add(type);
                else if (observed.schemaMatches()==null) throw TypesenseException.unavailable();
                schemasMatch &= Boolean.TRUE.equals(observed.schemaMatches());
                return false;
            }
            if (exportStep<TYPES.size()) {
                String type=TYPES.get(exportStep);
                if (absent.contains(type)) { exportStep++; return false; }
                if (stream==null) stream=client.openDocumentMetadata(generation.collections().get(type));
                var page=stream.readPage(100,1_048_576);
                for (var document:page) {
                    jdbc.sql("insert into search_reconcile_index values(:type,:id,:revision,:fingerprint,:content)")
                            .param("type",type).param("id",document.id()).param("revision",document.revision())
                            .param("fingerprint",document.fingerprint()).param("content",document.contentFingerprint()).update();
                }
                processed+=page.size();
                if (stream.exhausted()) { stream.close();stream=null;exportStep++; }
                return false;
            }
            if (sourceStep<TYPES.size()) {
                String type=TYPES.get(sourceStep);
                var ids=reader.reconciliationIds(type,after,100);
                for (long id:ids) {
                    var value=reader.readForReconciliation(type,id).orElseThrow();
                    jdbc.sql("""
                            insert into search_reconcile_source values(:type,:id,:revision,:fingerprint,:live,
                                :revision=0 or :revision>coalesce((select delivered_revision from search_generation_delivery
                                    where generation_id=:generation and entity_type=:type and entity_id=:id),0))
                            """).param("type",type).param("id",id).param("revision",value.revision()).param("fingerprint",value.fingerprint())
                            .param("live",value.document()!=null).param("generation",generation.id()).update();
                    after=id;
                }
                processed+=ids.size();
                if (ids.size()<100) { sourceStep++;after=0; }
                return false;
            }
            summary=jdbc.sql("""
                    select count(*) filter(where e.live and i.id is null) as missing,
                        count(*) filter(where i.id is not null and (e.entity_id is null or not e.live)) as extra,
                        count(*) filter(where e.live and i.id is not null and
                            (i.revision=0 or i.revision is distinct from e.revision or i.fingerprint is distinct from e.fingerprint
                                or i.content_fingerprint is distinct from e.fingerprint)) as mismatched,
                        count(*) filter(where e.pending) as pending
                    from search_reconcile_source e full join search_reconcile_index i
                    on e.entity_type=i.entity_type and e.entity_id::text=i.id
                    """).query((rs,row) -> new VerificationSummary(rs.getLong("missing"),rs.getLong("extra"),rs.getLong("mismatched"),
                            rs.getLong("pending"),schemasMatch)).single();
            return true;
        }

        public long processed() { return processed; }
        public VerificationSummary summary() {
            if (summary==null) throw new IllegalStateException("RECONCILIATION_INCOMPLETE");
            return summary;
        }
        public boolean revisionsUnchanged() {
            if (summary==null || closed) return false;
            return jdbc.sql("""
                    select not exists(select 1 from search_projection_versions v full join search_reconcile_source e
                        on v.entity_type=e.entity_type and v.entity_id=e.entity_id where v.revision is distinct from e.revision)
                    """).query(Boolean.class).single();
        }

        /** Caller performs only PostgreSQL barrier/CAS/audit work here; never transport or source discovery. */
        public <T> T transaction(Function<JdbcClient,T> action) {
            if (closed || summary==null) throw new IllegalStateException("RECONCILIATION_INCOMPLETE");
            try {
                connection.setAutoCommit(false);
                try {
                    jdbc.sql("set local statement_timeout='2s'").update();
                    T result=action.apply(jdbc);connection.commit();return result;
                } catch (RuntimeException | Error | SQLException failure) {
                    try { connection.rollback(); }
                    catch (SQLException rollbackFailed) {
                        try { connection.abort(Runnable::run); } catch (SQLException ignored) { /* do not commit a failed transaction */ }
                    }
                    throw failure;
                }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException failure) { throw new IllegalStateException("RECONCILIATION_CHECKPOINT_FAILED"); }
        }

        @Override public void close() {
            if (closed) return;
            closed=true;
            try {
                if (stream!=null) {
                    try { stream.close(); } catch (RuntimeException ignored) { /* still release task-owned database resources */ }
                    stream=null;
                }
                if (indexCreated) jdbc.sql("drop table if exists pg_temp.search_reconcile_index").update();
                if (sourceCreated) jdbc.sql("drop table if exists pg_temp.search_reconcile_source").update();
            } catch (RuntimeException failure) {
                try { connection.abort(Runnable::run); } catch (SQLException ignored) { /* pool must discard this connection */ }
            } finally { try { connection.close(); } catch (SQLException ignored) { /* task-owned connection */ } }
        }
    }
}
