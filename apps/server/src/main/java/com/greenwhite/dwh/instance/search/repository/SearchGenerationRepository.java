package com.greenwhite.dwh.instance.search.repository;

import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.SettingsSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.UUID;
import java.util.*;
import java.time.Instant;

@Repository
public class SearchGenerationRepository {
    private final JdbcClient jdbc;
    public SearchGenerationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public long registeredCount() { return jdbc.sql("select count(*) from search_generations").query(Long.class).single(); }
    public UUID allocate(SettingsSnapshot snapshot) {
        UUID id=UUID.randomUUID();
        String prefix="cms_"+id.toString().replace("-","")+"_";
        jdbc.sql("""
                insert into search_generations(id,state,task_collection,project_collection,user_collection,schema_version,schema_profile,settings_version,discovery_entity)
                values(:id,'BUILDING',:tasks,:projects,:users,1,:profile,:settings,'TASK')
                """).param("id",id).param("tasks",prefix+"tasks").param("projects",prefix+"projects").param("users",prefix+"users")
                .param("profile",snapshot.policy().schemaProfile()).param("settings",snapshot.version()).update();
        return id;
    }
    public void failBuild(UUID id) {
        jdbc.sql("update search_generations set state='FAILED' where id=:id and state='BUILDING'").param("id",id).update();
    }
    public boolean retryBuild(UUID id) {
        boolean changed=jdbc.sql("update search_generations set state='BUILDING',verified_at=null where id=:id and state='FAILED' and schema_version=1")
                .param("id",id).update()==1;
        if (changed) resetRetries(id);
        return changed;
    }
    public void resetRetries(UUID id) {
        jdbc.sql("update search_generation_delivery set attempts=0,error_code=null,owner_token=null,next_attempt_at='epoch' where generation_id=:id")
                .param("id",id).update();
    }
    public Optional<FrozenGeneration> find(UUID id) {
        return jdbc.sql("select * from search_generations where id=:id").param("id",id).query((rs,row) -> new FrozenGeneration(
                rs.getObject("id",UUID.class),rs.getString("state"),Map.of("TASK",rs.getString("task_collection"),
                "PROJECT",rs.getString("project_collection"),"USER",rs.getString("user_collection")),rs.getInt("schema_version"),
                rs.getString("schema_profile"),rs.getLong("settings_version"),rs.getString("discovery_entity"),rs.getLong("discovery_after_id"),
                rs.getTimestamp("verified_at")==null ? null : rs.getTimestamp("verified_at").toInstant())).optional();
    }
    public long pending(UUID id) {
        return jdbc.sql("select count(*) from search_projection_versions v left join search_generation_delivery d on d.generation_id=:id and d.entity_type=v.entity_type and d.entity_id=v.entity_id where v.revision>coalesce(d.delivered_revision,0)")
                .param("id",id).query(Long.class).single();
    }
    public long processed(UUID id) {
        return jdbc.sql("select count(*) from search_generation_delivery where generation_id=:id and delivered_revision>0")
                .param("id",id).query(Long.class).single();
    }
    public long exhausted(UUID id) {
        return jdbc.sql("select count(*) from search_generation_delivery d join search_projection_versions v on v.entity_type=d.entity_type and v.entity_id=d.entity_id where d.generation_id=:id and d.attempts>=8 and v.revision>d.delivered_revision and v.revision=d.attempted_revision")
                .param("id",id).query(Long.class).single();
    }
    public Optional<BarrierState> lockBarrier(JdbcClient proof,UUID owner,UUID job,String jobState,FrozenGeneration generation) {
        var barrier=proof.sql("select active_generation_id,version,worker_owner from search_index_state where id=1 for update")
                .query((rs,row) -> new BarrierState(rs.getObject("active_generation_id",UUID.class),rs.getLong("version"),rs.getObject("worker_owner",UUID.class))).single();
        if (!owner.equals(barrier.owner())) return Optional.empty();
        boolean eligible=proof.sql("""
                select exists(select 1 from search_jobs j join search_generations g on g.id=j.generation_id
                    where j.id=:job and j.owner_token=:owner and j.state=:state and g.id=:generation
                    and g.schema_version=:schema and g.schema_profile=:profile and g.settings_version=:settings
                    and g.task_collection=:tasks and g.project_collection=:projects and g.user_collection=:users)
                """).param("job",job).param("owner",owner).param("state",jobState).param("generation",generation.id())
                .param("schema",generation.schemaVersion()).param("profile",generation.schemaProfile()).param("settings",generation.settingsVersion())
                .param("tasks",generation.collections().get("TASK")).param("projects",generation.collections().get("PROJECT"))
                .param("users",generation.collections().get("USER")).query(Boolean.class).single();
        return eligible ? Optional.of(barrier) : Optional.empty();
    }
    public boolean readyToActivate(JdbcClient proof,UUID generation) {
        return proof.sql("""
                select exists(select 1 from search_generations where id=:generation and state in ('BUILDING','RETAINED')
                    and schema_version=1 and discovery_entity='DONE') and not exists(
                    select 1 from search_projection_versions v left join search_generation_delivery d
                    on d.generation_id=:generation and d.entity_type=v.entity_type and d.entity_id=v.entity_id
                    where v.revision>coalesce(d.delivered_revision,0))
                """).param("generation",generation).query(Boolean.class).single();
    }
    public boolean activate(JdbcClient proof,UUID generation,UUID job,UUID owner,long expectedVersion,UUID oldGeneration) {
        int switched=proof.sql("update search_index_state set active_generation_id=:generation,version=version+1,initialized=true where id=1 and version=:expected")
                .param("generation",generation).param("expected",expectedVersion).update();
        if (switched!=1) return false;
        if (oldGeneration!=null) proof.sql("update search_generations set state='RETAINED' where id=:id").param("id",oldGeneration).update();
        proof.sql("update search_generations set state='ACTIVE',verified_at=clock_timestamp() where id=:id").param("id",generation).update();
        complete(proof,job,owner,"ACTIVATING");
        Long actor=proof.sql("select actor_id from search_jobs where id=:job").param("job",job).query((rs,row) -> rs.getObject("actor_id",Long.class)).optional().orElse(null);
        SearchJobRepository.audit(proof,job,actor,"SWITCH");
        return true;
    }
    public void completeCheck(JdbcClient proof,UUID generation,UUID job,UUID owner,boolean verifiedActive) {
        if (verifiedActive) proof.sql("update search_generations set verified_at=clock_timestamp() where id=:id").param("id",generation).update();
        complete(proof,job,owner,"VERIFYING");
    }
    private void complete(JdbcClient proof,UUID job,UUID owner,String expectedState) {
        if (proof.sql("update search_jobs set state='SUCCEEDED',owner_token=null,finished_at=clock_timestamp(),updated_at=clock_timestamp() where id=:job and owner_token=:owner and state=:state")
                .param("job",job).param("owner",owner).param("state",expectedState).update()!=1) throw new IllegalStateException("JOB_OWNERSHIP_LOST");
    }
    public record FrozenGeneration(UUID id,String state,Map<String,String> collections,int schemaVersion,String schemaProfile,
                                   long settingsVersion,String discoveryEntity,long discoveryAfterId,Instant verifiedAt) {
        public SearchIndexStateRepository.Generation delivery(long version) {
            return new SearchIndexStateRepository.Generation(id,state,collections,schemaProfile,discoveryEntity,discoveryAfterId,version);
        }
    }
    public record BarrierState(UUID activeGeneration,long version,UUID owner) {}
}
