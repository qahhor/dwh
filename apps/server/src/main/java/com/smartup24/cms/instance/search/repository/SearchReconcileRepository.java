package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.search.dto.SearchManagementDtos.VerificationSummary;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of a reconciliation proof (plan 10/10, item 4.2: the service writes no SQL). A proof holds one dedicated
 * connection with two session-owned temporary tables, so every method takes that session's client instead of the
 * pool's: the temporary tables exist only there.
 */
@Repository
public class SearchReconcileRepository {

    /** Creates the temporary table of the documents found in the search index. */
    public void createIndexTable(JdbcClient session) {
        session.sql("create temporary table search_reconcile_index(entity_type text,id text,revision bigint,"
                        + "fingerprint text,content_fingerprint text,primary key(entity_type,id))")
                .update();
    }

    /** Creates the temporary table of the records the index should hold. */
    public void createSourceTable(JdbcClient session) {
        session.sql("create temporary table search_reconcile_source(entity_type text,entity_id bigint,revision bigint,"
                        + "fingerprint text,live boolean,pending boolean,primary key(entity_type,entity_id))")
                .update();
    }

    /** Records one document of the index. */
    public void insertIndexed(
            JdbcClient session, String type, String id, long revision, String fingerprint, String contentFingerprint) {
        session.sql("insert into search_reconcile_index values(:type,:id,:revision,:fingerprint,:content)")
                .param("type", type)
                .param("id", id)
                .param("revision", revision)
                .param("fingerprint", fingerprint)
                .param("content", contentFingerprint)
                .update();
    }

    /** Records one source record, pending when its revision is not yet delivered to the generation. */
    public void insertSource(
            JdbcClient session,
            String type,
            long id,
            long revision,
            String fingerprint,
            boolean live,
            UUID generation) {
        session.sql("""
                        insert into search_reconcile_source values(:type,:id,:revision,:fingerprint,:live,
                            :revision=0 or :revision>coalesce((select delivered_revision from search_generation_delivery
                                where generation_id=:generation and entity_type=:type and entity_id=:id),0))
                        """)
                .param("type", type)
                .param("id", id)
                .param("revision", revision)
                .param("fingerprint", fingerprint)
                .param("live", live)
                .param("generation", generation)
                .update();
    }

    /** Compares the two tables: missing, extra, mismatched and pending documents. */
    public VerificationSummary summary(JdbcClient session, boolean schemasMatch) {
        return session.sql("""
                        select count(*) filter(where e.live and i.id is null) as missing,
                            count(*) filter(where i.id is not null and (e.entity_id is null or not e.live)) as extra,
                            count(*) filter(where e.live and i.id is not null and
                                (i.revision=0 or i.revision is distinct from e.revision or i.fingerprint is distinct from e.fingerprint
                                    or i.content_fingerprint is distinct from e.fingerprint)) as mismatched,
                            count(*) filter(where e.pending) as pending
                        from search_reconcile_source e full join search_reconcile_index i
                        on e.entity_type=i.entity_type and e.entity_id::text=i.id
                        """)
                .query((rs, row) -> new VerificationSummary(
                        rs.getLong("missing"),
                        rs.getLong("extra"),
                        rs.getLong("mismatched"),
                        rs.getLong("pending"),
                        schemasMatch))
                .single();
    }

    /** True while no source revision moved since the proof read them. */
    public boolean revisionsUnchanged(JdbcClient session) {
        return session.sql("""
                        select not exists(select 1 from search_projection_versions v full join search_reconcile_source e
                            on v.entity_type=e.entity_type and v.entity_id=e.entity_id where v.revision is distinct from e.revision)
                        """).query(Boolean.class).single();
    }

    /** Limits the statements of the session's current transaction to two seconds. */
    public void limitTransaction(JdbcClient session) {
        session.sql("set local statement_timeout='2s'").update();
    }

    /** Drops the temporary table of the index documents. */
    public void dropIndexTable(JdbcClient session) {
        session.sql("drop table if exists pg_temp.search_reconcile_index").update();
    }

    /** Drops the temporary table of the source records. */
    public void dropSourceTable(JdbcClient session) {
        session.sql("drop table if exists pg_temp.search_reconcile_source").update();
    }
}
