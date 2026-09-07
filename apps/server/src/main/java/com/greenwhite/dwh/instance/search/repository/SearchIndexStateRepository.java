package com.greenwhite.dwh.instance.search.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SearchIndexStateRepository {
    private final JdbcClient jdbc;
    public SearchIndexStateRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public IndexSnapshot snapshot() {
        return jdbc.sql("""
                select s.active_generation_id,s.version,s.initialized,g.state,g.task_collection,
                    g.project_collection,g.user_collection,g.schema_profile
                from search_index_state s left join search_generations g on g.id=s.active_generation_id where s.id=1
                """).query((rs, row) -> {
                    UUID id = rs.getObject("active_generation_id", UUID.class);
                    return new IndexSnapshot(id, rs.getLong("version"), id == null ? Map.of() : Map.of(
                            "TASK", rs.getString("task_collection"), "PROJECT", rs.getString("project_collection"),
                            "USER", rs.getString("user_collection")), rs.getString("schema_profile"),
                            rs.getBoolean("initialized"), "LEGACY".equals(rs.getString("state")));
                }).single();
    }

    /** Called once at application lifecycle start, never as timeout-based recovery. Single-process topology only. */
    @Transactional
    public void recoverOwnership(UUID owner) {
        jdbc.sql("select id from search_index_state where id=1 for update").query(Integer.class).single();
        jdbc.sql("update search_index_state set worker_owner=:owner,worker_started_at=clock_timestamp() where id=1")
                .param("owner", owner).update();
        jdbc.sql("update search_generation_delivery set owner_token=null where owner_token is not null").update();
    }

    public Optional<Generation> deliveryGeneration(UUID owner) {
        return jdbc.sql("""
                select g.*,s.version from search_index_state s join search_generations g
                on g.id=s.active_generation_id or (s.active_generation_id is null and g.state='BUILDING')
                where s.id=1 and s.worker_owner=:owner
                order by g.created_at,g.id limit 1
                """).param("owner", owner).query((rs, row) -> new Generation(rs.getObject("id", UUID.class),
                rs.getString("state"), Map.of("TASK",rs.getString("task_collection"),
                "PROJECT",rs.getString("project_collection"),"USER",rs.getString("user_collection")),
                rs.getString("schema_profile"),rs.getString("discovery_entity"),rs.getLong("discovery_after_id"),rs.getLong("version")))
                .optional();
    }

    @Transactional
    public void registerInitial(boolean legacy, UUID owner) {
        if (!lockOwner(owner, "update")) return;
        if (snapshot().generationId() != null || jdbc.sql("select exists(select 1 from search_generations where state='BUILDING')")
                .query(Boolean.class).single()) return;
        UUID id = UUID.randomUUID();
        String prefix = "search_" + id.toString().replace("-", "") + "_";
        jdbc.sql("""
                insert into search_generations(id,state,task_collection,project_collection,user_collection,
                    schema_version,schema_profile,settings_version,discovery_entity)
                values (:id,:state,:tasks,:projects,:users,:schema,'MIXED',1,'TASK')
                """).param("id", id).param("state", legacy ? "LEGACY" : "BUILDING")
                .param("tasks", legacy ? "tasks" : prefix + "tasks")
                .param("projects", legacy ? "projects" : prefix + "projects")
                .param("users", legacy ? "users" : prefix + "users")
                .param("schema", legacy ? 0 : 1).update();
        if (legacy) {
            // LEGACY remains explicitly unverified and requires a rebuild. Never reinterpret its schema.
            jdbc.sql("update search_index_state set active_generation_id=:id,initialized=true,version=version+1 where id=1")
                    .param("id", id).update();
        }
    }

    @Transactional
    public void discoverPage(Generation generation, UUID owner, int pageSize) {
        if ("DONE".equals(generation.discoveryEntity()) || !lockOwner(owner, "share")) return;
        String table = switch (generation.discoveryEntity()) {
            case "TASK" -> "ms_tasks";
            case "PROJECT" -> "ms_task_projects";
            case "USER" -> "md_users";
            default -> throw new IllegalArgumentException("Unknown discovery entity");
        };
        String next = switch (generation.discoveryEntity()) {
            case "TASK" -> "PROJECT";
            case "PROJECT" -> "USER";
            default -> "DONE";
        };
        String active = generation.discoveryEntity().equals("TASK") ? "" : " and state='A'";
        jdbc.sql("""
                with page as materialized (
                    select id from %s where id>:after %s order by id limit :limit
                ), inserted as (
                    insert into search_projection_versions(entity_type,entity_id,revision)
                    select :type,id,1 from page order by id on conflict(entity_type,entity_id) do nothing
                )
                update search_generations
                set discovery_entity=case when (select count(*) from page)<:limit then :next else :type end,
                    discovery_after_id=case when (select count(*) from page)<:limit then 0 else (select max(id) from page) end
                where id=:generation and discovery_entity=:type and discovery_after_id=:after
                """.formatted(table, active)).param("after", generation.discoveryAfterId())
                .param("type", generation.discoveryEntity()).param("next", next)
                .param("limit", Math.max(1, Math.min(100, pageSize))).param("generation", generation.id()).update();
    }

    /** Minimal initial-build CAS. Administrative verification/activation belongs to the job lifecycle. */
    @Transactional
    public boolean activateInitial(UUID generation, long expectedVersion, UUID owner) {
        if (!lockOwner(owner, "update")) return false;
        int activated = jdbc.sql("""
                update search_index_state set active_generation_id=:generation,initialized=true,version=version+1
                where id=1 and version=:version and active_generation_id is null
                  and exists(select 1 from search_generations where id=:generation and state='BUILDING' and discovery_entity='DONE')
                  and not exists(
                    select 1 from search_projection_versions v left join search_generation_delivery d
                    on d.generation_id=:generation and d.entity_type=v.entity_type and d.entity_id=v.entity_id
                    where v.revision>coalesce(d.delivered_revision,0)
                  )
                """).param("generation", generation).param("version", expectedVersion).update();
        if (activated == 1) jdbc.sql("update search_generations set state='ACTIVE' where id=:id").param("id", generation).update();
        return activated == 1;
    }

    private boolean lockOwner(UUID owner, String mode) {
        return jdbc.sql("select coalesce(worker_owner=:owner,false) from search_index_state where id=1 for " + mode)
                .param("owner", owner).query(Boolean.class).single();
    }

    public record IndexSnapshot(UUID generationId, long version, Map<String,String> collections,
                                String schemaProfile, boolean initialized, boolean legacy) {
        public IndexSnapshot { collections = Map.copyOf(collections); }
    }
    public record Generation(UUID id, String state, Map<String,String> collections, String schemaProfile,
                             String discoveryEntity, long discoveryAfterId, long version) {
        public Generation { collections = Map.copyOf(collections); }
    }
}
