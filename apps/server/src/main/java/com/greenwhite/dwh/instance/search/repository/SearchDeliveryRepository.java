package com.greenwhite.dwh.instance.search.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class SearchDeliveryRepository {
    private final JdbcClient jdbc;
    public SearchDeliveryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Previous synchronous cycle has returned. This is not recovery or takeover of another lifecycle's ownership. */
    @Transactional
    public void releaseUnfinishedCycle(UUID owner) {
        jdbc.sql("""
                update search_generation_delivery set owner_token=null
                where owner_token=:owner
                    and exists(select 1 from search_index_state where id=1 and worker_owner=:owner)
                """).param("owner", owner).update();
    }

    @Transactional
    public List<Claim> claim(UUID generation, UUID owner, Instant now, int limit) {
        boolean currentOwner = jdbc.sql("select worker_owner=:owner from search_index_state where id=1 for share")
                .param("owner", owner).query(Boolean.class).single();
        if (!currentOwner) return List.of();
        return jdbc.sql("""
                with candidates as (
                    select v.entity_type,v.entity_id,v.revision
                    from search_projection_versions v left join search_generation_delivery d
                    on d.generation_id=:generation and d.entity_type=v.entity_type and d.entity_id=v.entity_id
                    where v.revision>coalesce(d.delivered_revision,0) and d.owner_token is null
                      and (v.revision>coalesce(d.attempted_revision,0) or (d.attempts<8 and d.next_attempt_at<=:now))
                    order by v.entity_type,v.entity_id limit :limit
                )
                insert into search_generation_delivery as d(generation_id,entity_type,entity_id,attempted_revision,owner_token,next_attempt_at)
                select :generation,entity_type,entity_id,revision,:owner,:now from candidates
                on conflict(generation_id,entity_type,entity_id) do update
                set attempted_revision=excluded.attempted_revision,owner_token=excluded.owner_token,
                    attempts=case when d.attempted_revision=excluded.attempted_revision then d.attempts else 0 end,
                    next_attempt_at=case when d.attempted_revision=excluded.attempted_revision then d.next_attempt_at else excluded.next_attempt_at end,
                    error_code=case when d.attempted_revision=excluded.attempted_revision then d.error_code else null end
                where d.owner_token is null
                returning entity_type,entity_id,attempted_revision,attempts
                """).param("generation", generation).param("owner", owner).param("now", Timestamp.from(now))
                .param("limit", Math.max(1, Math.min(100, limit)))
                .query((rs, row) -> new Claim(generation, rs.getString("entity_type"), rs.getLong("entity_id"),
                        rs.getLong("attempted_revision"), rs.getInt("attempts"), owner)).list();
    }

    @Transactional
    public boolean acknowledge(Claim claim, String fingerprint) {
        return updateClaim("""
                update search_generation_delivery set delivered_revision=:revision,delivered_fingerprint=:fingerprint,
                    owner_token=null,attempts=0,error_code=null
                """, claim).param("fingerprint", fingerprint).update() == 1;
    }

    @Transactional
    public void failed(Claim claim, Instant retryAt) {
        updateClaim("""
                update search_generation_delivery set attempts=attempts+1,next_attempt_at=:retry,
                    owner_token=null,error_code='DELIVERY_FAILED'
                """, claim).param("retry", Timestamp.from(retryAt)).update();
    }

    @Transactional
    public void release(Claim claim) {
        updateClaim("update search_generation_delivery set owner_token=null", claim).update();
    }

    private JdbcClient.StatementSpec updateClaim(String update, Claim claim) {
        return jdbc.sql(update + """
                 where generation_id=:generation and entity_type=:type and entity_id=:id
                    and owner_token=:owner and attempted_revision=:revision
                    and exists(select 1 from search_index_state where id=1 and worker_owner=:owner)
                """).param("generation", claim.generationId()).param("type", claim.entityType())
                .param("id", claim.entityId()).param("owner", claim.owner()).param("revision", claim.revision());
    }

    public record Claim(UUID generationId, String entityType, long entityId, long revision, int attempts, UUID owner) {}
}
