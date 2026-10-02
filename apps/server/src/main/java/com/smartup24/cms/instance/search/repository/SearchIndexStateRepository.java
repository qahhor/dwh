package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.search.dto.SearchManagementDtos;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.search.service.SearchEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SearchIndexStateRepository {
    private final JdbcClient jdbc;
    private final SearchEntities entities;

    public SearchIndexStateRepository(JdbcClient jdbc, SearchEntities entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    public SearchExecutionSnapshot executionSnapshot() {
        return jdbc.sql("select s.active_generation_id,s.version,s.initialized,g.state,g.schema_profile,"
                        + SearchGenerationCollections.column("g") + ","
                        + " p.version as settings_version,p.configuration::text"
                        + " from search_index_state s cross join search_settings p"
                        + " left join search_generations g on g.id=s.active_generation_id where s.id=1 and p.id=1")
                .query((rs, row) -> {
                    IndexSnapshot index = new IndexSnapshot(
                            rs.getObject("active_generation_id", UUID.class),
                            rs.getLong("version"),
                            SearchGenerationCollections.parse(rs.getString("collections")),
                            rs.getString("schema_profile"),
                            rs.getBoolean("initialized"),
                            "LEGACY".equals(rs.getString("state")));
                    long version = rs.getLong("settings_version");
                    return new SearchExecutionSnapshot(
                            index,
                            new SettingsSnapshot(
                                    version,
                                    SearchManagementDtos.decodeStored(rs.getString("configuration"), version)));
                })
                .single();
    }

    public IndexSnapshot snapshot() {
        return jdbc.sql("select s.active_generation_id,s.version,s.initialized,g.state,g.schema_profile,"
                        + SearchGenerationCollections.column("g")
                        + " from search_index_state s left join search_generations g on g.id=s.active_generation_id"
                        + " where s.id=1")
                .query((rs, row) -> new IndexSnapshot(
                        rs.getObject("active_generation_id", UUID.class),
                        rs.getLong("version"),
                        SearchGenerationCollections.parse(rs.getString("collections")),
                        rs.getString("schema_profile"),
                        rs.getBoolean("initialized"),
                        "LEGACY".equals(rs.getString("state"))))
                .single();
    }

    public List<ObservedGeneration> observations() {
        String indexed = SearchGenerationCollections.indexed("g.id");
        return jdbc.sql("select g.*,coalesce(s.active_generation_id=g.id,false) as active,"
                        + SearchGenerationCollections.column("g") + ","
                        + " (select count(*) from search_projection_versions v left join search_generation_delivery d"
                        + " on d.generation_id=g.id and d.entity_type=v.entity_type and d.entity_id=v.entity_id"
                        + " where v.revision>coalesce(d.delivered_revision,0) and " + indexed + ") as pending,"
                        + " (select count(*) from search_generation_delivery d join search_projection_versions v"
                        + " on v.entity_type=d.entity_type and v.entity_id=d.entity_id"
                        + " where d.generation_id=g.id and d.error_code is not null and v.revision>d.delivered_revision)"
                        + " as failed,"
                        + " (select min(v.changed_at) from search_projection_versions v left join"
                        + " search_generation_delivery d"
                        + " on d.generation_id=g.id and d.entity_type=v.entity_type and d.entity_id=v.entity_id"
                        + " where v.revision>coalesce(d.delivered_revision,0) and " + indexed + ") as oldest_pending"
                        + " from search_generations g cross join search_index_state s where s.id=1"
                        + " order by g.created_at,g.id")
                .query((rs, row) -> new ObservedGeneration(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        rs.getString("schema_profile"),
                        rs.getBoolean("active"),
                        SearchGenerationCollections.parse(rs.getString("collections")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("verified_at") == null
                                ? null
                                : rs.getTimestamp("verified_at").toInstant(),
                        rs.getInt("schema_version"),
                        rs.getLong("pending"),
                        rs.getLong("failed"),
                        rs.getTimestamp("oldest_pending") == null
                                ? null
                                : rs.getTimestamp("oldest_pending").toInstant()))
                .list();
    }

    /** Called once at application lifecycle start, never as timeout-based recovery. Single-process topology only. */
    @Transactional
    public void recoverOwnership(UUID owner) {
        jdbc.sql("select id from search_index_state where id=1 for update")
                .query(Integer.class)
                .single();
        jdbc.sql("update search_index_state set worker_owner=:owner,worker_started_at=clock_timestamp() where id=1")
                .param("owner", owner)
                .update();
        jdbc.sql("update search_generation_delivery set owner_token=null where owner_token is not null")
                .update();
        // Temporary verification state belongs to the old process; restart from a durable catch-up checkpoint.
        jdbc.sql(
                        "update search_jobs set owner_token=null,state=case when state in ('VERIFYING','ACTIVATING') then 'RUNNING' else state end where state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING')")
                .update();
    }

    public Optional<Generation> deliveryGeneration(UUID owner) {
        return jdbc.sql("select g.*,s.version," + SearchGenerationCollections.column("g")
                        + " from search_index_state s join search_generations g on g.id=s.active_generation_id"
                        + " where s.id=1 and s.worker_owner=:owner order by g.created_at,g.id limit 1")
                .param("owner", owner)
                .query((rs, row) -> new Generation(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        SearchGenerationCollections.parse(rs.getString("collections")),
                        rs.getString("schema_profile"),
                        rs.getString("discovery_entity"),
                        rs.getLong("discovery_after_id"),
                        rs.getLong("version")))
                .optional();
    }

    /**
     * Finds one page of the records of the entity the generation's discovery stands at and gives each a projection
     * version, so the delivery indexes it; at the end of an entity the discovery moves to the next entity the
     * generation indexes, in the order of the codes, and after the last one it is done (ADR-0032, 10.3). An entity that
     * no longer declares the search is passed over.
     */
    @Transactional
    public void discoverPage(Generation generation, UUID owner, int pageSize) {
        String current = generation.discoveryEntity();
        if (SearchGenerationRepository.DONE.equals(current) || !lockOwner(owner, "share")) return;
        List<String> types = new ArrayList<>(generation.collections().keySet());
        int position = types.indexOf(current);
        String next = position >= 0 && position + 1 < types.size()
                ? types.get(position + 1)
                : SearchGenerationRepository.DONE;
        Optional<SearchEntity> entity = entities.find(current);
        String page = entity.map(SearchDocumentSql::ids).orElse("select null::bigint as id where false");
        jdbc.sql("""
                with page as materialized (
                    %s
                ), inserted as (
                    insert into search_projection_versions(entity_type,entity_id,revision)
                    select :type,id,1 from page order by id on conflict(entity_type,entity_id) do nothing
                )
                update search_generations
                set discovery_entity=case when (select count(*) from page)<:limit then :next else :type end,
                    discovery_after_id=case when (select count(*) from page)<:limit then 0 else (select max(id) from page) end
                where id=:generation and discovery_entity=:type and discovery_after_id=:after
                """.formatted(page))
                .param("after", generation.discoveryAfterId())
                .param("type", current)
                .param("next", next)
                .param("limit", Math.max(1, Math.min(100, pageSize)))
                .param("generation", generation.id())
                .update();
    }

    private boolean lockOwner(UUID owner, String mode) {
        return jdbc.sql("select coalesce(worker_owner=:owner,false) from search_index_state where id=1 for " + mode)
                .param("owner", owner)
                .query(Boolean.class)
                .single();
    }

    public boolean owns(UUID owner) {
        return jdbc.sql("select coalesce(worker_owner=:owner,false) from search_index_state where id=1")
                .param("owner", owner)
                .query(Boolean.class)
                .single();
    }

    /**
     * The index the queries read.
     *
     * @param collections entity code → collection of the active generation, in the order of the codes
     */
    public record IndexSnapshot(
            UUID generationId,
            long version,
            Map<String, String> collections,
            String schemaProfile,
            boolean initialized,
            boolean legacy) {
        public IndexSnapshot {
            collections = SearchGenerationCollections.ordered(collections);
        }
    }

    public record Generation(
            UUID id,
            String state,
            Map<String, String> collections,
            String schemaProfile,
            String discoveryEntity,
            long discoveryAfterId,
            long version) {
        public Generation {
            collections = SearchGenerationCollections.ordered(collections);
        }
    }

    public record ObservedGeneration(
            UUID id,
            String state,
            String schemaProfile,
            boolean active,
            Map<String, String> collections,
            Instant createdAt,
            Instant verifiedAt,
            int schemaVersion,
            long pending,
            long failed,
            Instant oldestPending) {
        public ObservedGeneration {
            collections = SearchGenerationCollections.ordered(collections);
        }
    }
}
