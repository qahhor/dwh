package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.search.api.SearchManagementDtos.SettingsSnapshot;
import com.smartup24.cms.instance.search.service.SearchEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchGenerationRepository {
    /** The discovery state of a generation that has found the records of every entity it indexes. */
    public static final String DONE = "DONE";

    private final JdbcClient jdbc;

    public SearchGenerationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long registeredCount() {
        return jdbc.sql("select count(*) from search_generations")
                .query(Long.class)
                .single();
    }

    /**
     * Registers a new generation that builds one collection per entity the search indexes (ADR-0032, 10.3); its
     * discovery starts with the first entity, or is done when there is none.
     */
    public UUID allocate(SettingsSnapshot snapshot, List<SearchEntity> entities) {
        UUID id = UUID.randomUUID();
        String prefix = "cms_" + id.toString().replace("-", "") + "_";
        jdbc.sql("""
                insert into search_generations(id,state,schema_version,schema_profile,settings_version,discovery_entity)
                values(:id,'BUILDING',1,:profile,:settings,:discovery)
                """)
                .param("id", id)
                .param("profile", snapshot.policy().schemaProfile())
                .param("settings", snapshot.version())
                .param(
                        "discovery",
                        entities.isEmpty() ? DONE : entities.getFirst().code())
                .update();
        for (SearchEntity entity : entities) {
            jdbc.sql("insert into search_generation_collections(generation_id,entity_type,collection)"
                            + " values(:id,:type,:collection)")
                    .param("id", id)
                    .param("type", entity.code())
                    .param("collection", entity.collection(prefix))
                    .update();
        }
        return id;
    }

    public void failBuild(UUID id) {
        jdbc.sql("update search_generations set state='FAILED' where id=:id and state='BUILDING'")
                .param("id", id)
                .update();
    }

    public boolean retryBuild(UUID id) {
        boolean changed = jdbc.sql(
                                "update search_generations set state='BUILDING',verified_at=null where id=:id and state='FAILED' and schema_version=1")
                        .param("id", id)
                        .update()
                == 1;
        if (changed) resetRetries(id);
        return changed;
    }

    public void resetRetries(UUID id) {
        jdbc.sql(
                        "update search_generation_delivery set attempts=0,error_code=null,owner_token=null,next_attempt_at='epoch' where generation_id=:id")
                .param("id", id)
                .update();
    }

    public Optional<FrozenGeneration> find(UUID id) {
        return jdbc.sql("select g.*," + SearchGenerationCollections.column("g")
                        + " from search_generations g where g.id=:id")
                .param("id", id)
                .query((rs, row) -> new FrozenGeneration(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        SearchGenerationCollections.parse(rs.getString("collections")),
                        rs.getInt("schema_version"),
                        rs.getString("schema_profile"),
                        rs.getLong("settings_version"),
                        rs.getString("discovery_entity"),
                        rs.getLong("discovery_after_id"),
                        rs.getTimestamp("verified_at") == null
                                ? null
                                : rs.getTimestamp("verified_at").toInstant()))
                .optional();
    }

    /** The projection versions of the generation's types not yet delivered to it. */
    public long pending(UUID id) {
        return jdbc.sql("select count(*) from search_projection_versions v left join search_generation_delivery d"
                        + " on d.generation_id=:id and d.entity_type=v.entity_type and d.entity_id=v.entity_id"
                        + " where v.revision>coalesce(d.delivered_revision,0) and "
                        + SearchGenerationCollections.indexed(":id"))
                .param("id", id)
                .query(Long.class)
                .single();
    }

    public long processed(UUID id) {
        return jdbc.sql(
                        "select count(*) from search_generation_delivery where generation_id=:id and delivered_revision>0")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    public long exhausted(UUID id) {
        return jdbc.sql(
                        "select count(*) from search_generation_delivery d join search_projection_versions v on v.entity_type=d.entity_type and v.entity_id=d.entity_id where d.generation_id=:id and d.attempts>=8 and v.revision>d.delivered_revision and v.revision=d.attempted_revision")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    public Optional<BarrierState> lockBarrier(
            JdbcClient proof, UUID owner, UUID job, String jobState, FrozenGeneration generation) {
        var barrier = proof.sql(
                        "select active_generation_id,version,worker_owner from search_index_state where id=1 for update")
                .query((rs, row) -> new BarrierState(
                        rs.getObject("active_generation_id", UUID.class),
                        rs.getLong("version"),
                        rs.getObject("worker_owner", UUID.class)))
                .single();
        if (!owner.equals(barrier.owner())) return Optional.empty();
        boolean eligible = proof.sql("""
                select exists(select 1 from search_jobs j join search_generations g on g.id=j.generation_id
                    where j.id=:job and j.owner_token=:owner and j.state=:state and g.id=:generation
                    and g.schema_version=:schema and g.schema_profile=:profile and g.settings_version=:settings)
                """)
                .param("job", job)
                .param("owner", owner)
                .param("state", jobState)
                .param("generation", generation.id())
                .param("schema", generation.schemaVersion())
                .param("profile", generation.schemaProfile())
                .param("settings", generation.settingsVersion())
                .query(Boolean.class)
                .single();
        // The collections the proof verified are still the generation's own.
        String collections = proof.sql("select " + SearchGenerationCollections.column("g")
                        + " from search_generations g where g.id=:generation")
                .param("generation", generation.id())
                .query(String.class)
                .optional()
                .orElse(null);
        eligible &= SearchGenerationCollections.parse(collections).equals(generation.collections());
        return eligible ? Optional.of(barrier) : Optional.empty();
    }

    /**
     * Whether the generation has found every record and holds the last revision of each of its types; a projection
     * version of a type it has no collection for never holds it back (ADR-0032, 10.3).
     */
    public boolean readyToActivate(JdbcClient proof, UUID generation) {
        return proof.sql("""
                select exists(select 1 from search_generations where id=:generation and state in ('BUILDING','RETAINED')
                    and schema_version=1 and discovery_entity='DONE') and not exists(
                    select 1 from search_projection_versions v left join search_generation_delivery d
                    on d.generation_id=:generation and d.entity_type=v.entity_type and d.entity_id=v.entity_id
                    where v.revision>coalesce(d.delivered_revision,0) and %s)
                """.formatted(SearchGenerationCollections.indexed(":generation")))
                .param("generation", generation)
                .query(Boolean.class)
                .single();
    }

    public boolean activate(
            JdbcClient proof, UUID generation, UUID job, UUID owner, long expectedVersion, UUID oldGeneration) {
        int switched = proof.sql(
                        "update search_index_state set active_generation_id=:generation,version=version+1,initialized=true where id=1 and version=:expected")
                .param("generation", generation)
                .param("expected", expectedVersion)
                .update();
        if (switched != 1) return false;
        if (oldGeneration != null)
            proof.sql("update search_generations set state='RETAINED' where id=:id")
                    .param("id", oldGeneration)
                    .update();
        proof.sql("update search_generations set state='ACTIVE',verified_at=clock_timestamp() where id=:id")
                .param("id", generation)
                .update();
        complete(proof, job, owner, "ACTIVATING");
        return true;
    }

    public void completeCheck(JdbcClient proof, UUID generation, UUID job, UUID owner, boolean verifiedActive) {
        if (verifiedActive)
            proof.sql("update search_generations set verified_at=clock_timestamp() where id=:id")
                    .param("id", generation)
                    .update();
        complete(proof, job, owner, "VERIFYING");
    }

    private void complete(JdbcClient proof, UUID job, UUID owner, String expectedState) {
        if (proof.sql(
                                "update search_jobs set state='SUCCEEDED',owner_token=null,finished_at=clock_timestamp(),updated_at=clock_timestamp() where id=:job and owner_token=:owner and state=:state")
                        .param("job", job)
                        .param("owner", owner)
                        .param("state", expectedState)
                        .update()
                != 1) throw new IllegalStateException("JOB_OWNERSHIP_LOST");
    }

    /**
     * A generation as a job reads it.
     *
     * @param collections entity code → collection, one per entity the generation indexes (ADR-0032, 10.3)
     */
    public record FrozenGeneration(
            UUID id,
            String state,
            Map<String, String> collections,
            int schemaVersion,
            String schemaProfile,
            long settingsVersion,
            String discoveryEntity,
            long discoveryAfterId,
            Instant verifiedAt) {
        public FrozenGeneration {
            collections = SearchGenerationCollections.ordered(collections);
        }

        public SearchIndexStateRepository.Generation delivery(long version) {
            return new SearchIndexStateRepository.Generation(
                    id, state, collections, schemaProfile, discoveryEntity, discoveryAfterId, version);
        }
    }

    public record BarrierState(UUID activeGeneration, long version, UUID owner) {}
}
