package com.smartup24.cms.instance.search.repository;

import com.smartup24.cms.instance.common.jdbc.StatementTimeouts;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.search.api.SearchManagementDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SearchJobRepository {
    /**
     * The limit of each statement of a job state change, the same as their {@code @Transactional(timeout = 2)}: it
     * holds inside an outer transaction too (an Idempotency-Key request), where that timeout is ignored.
     */
    public static final Duration STATE_CHANGE_LIMIT = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final JsonColumns columns;

    public SearchJobRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.columns = new JsonColumns(mapper, "search_jobs");
    }

    /** Runs a job state change with each statement limited to {@link #STATE_CHANGE_LIMIT}. */
    public <T> T limited(Supplier<T> work) {
        return StatementTimeouts.within(jdbc, STATE_CHANGE_LIMIT, work);
    }

    public void lockState() {
        jdbc.sql("select id from search_index_state where id=1 for update")
                .query(Integer.class)
                .single();
    }

    public Optional<RequestRecord> byRequest(UUID request) {
        return jdbc.sql(
                        "select id,state,action,requested_generation_id,retry_of_job_id,request_metadata_recorded from search_jobs where request_id=:request")
                .param("request", request)
                .query((rs, row) -> new RequestRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        rs.getString("action"),
                        rs.getObject("requested_generation_id", UUID.class),
                        rs.getObject("retry_of_job_id", UUID.class),
                        rs.getBoolean("request_metadata_recorded")))
                .optional();
    }

    public JobReceipt insert(StartJobRequest request, UUID generation, Long actor) {
        return insert(request, generation, actor, null);
    }

    public JobReceipt insert(StartJobRequest request, UUID generation, Long actor, UUID retryOf) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into search_jobs(id,request_id,action,generation_id,state,actor_id,requested_generation_id,retry_of_job_id)
                values(:id,:request,:action,:generation,'QUEUED',:actor,:requested,:retryOf)
                """)
                .param("id", id)
                .param("request", request.requestId())
                .param("action", request.action())
                .param("generation", generation)
                .param("actor", actor)
                .param("requested", request.generationId())
                .param("retryOf", retryOf)
                .update();
        return new JobReceipt(id, "QUEUED");
    }

    public boolean generationExists(UUID generation) {
        return generation != null
                && jdbc.sql("select exists(select 1 from search_generations where id=:id)")
                        .param("id", generation)
                        .query(Boolean.class)
                        .single();
    }

    public boolean mutatingJobExists() {
        return jdbc.sql(
                        "select exists(select 1 from search_jobs where action in ('REBUILD','ROLLBACK') and state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING'))")
                .query(Boolean.class)
                .single();
    }

    public boolean anyJobExists() {
        return jdbc.sql("select exists(select 1 from search_jobs)")
                .query(Boolean.class)
                .single();
    }

    public Optional<UUID> initialBuilding() {
        return jdbc.sql("select id from search_generations where state='BUILDING' order by created_at,id limit 1")
                .query(UUID.class)
                .optional();
    }

    public Optional<JobStatus> find(UUID id) {
        return jdbc.sql("select * from search_jobs where id=:id")
                .param("id", id)
                .query(this::mapStatus)
                .optional();
    }

    public List<JobStatus> page(int limit, Instant time, UUID id) {
        var query = jdbc.sql("select * from search_jobs "
                        + (time == null ? "" : "where created_at<:time or (created_at=:time and id>:id) ")
                        + "order by created_at desc,id limit :limit")
                .param("limit", limit);
        if (time != null) query.param("time", java.sql.Timestamp.from(time)).param("id", id);
        return query.query(this::mapStatus).list();
    }

    public boolean cancel(UUID id) {
        return jdbc.sql(
                                "update search_jobs set state='CANCELLED',owner_token=null,updated_at=clock_timestamp(),finished_at=clock_timestamp() where id=:id and state in ('QUEUED','RUNNING','VERIFYING')")
                        .param("id", id)
                        .update()
                == 1;
    }

    @Transactional(timeout = 2)
    public Optional<JobStatus> claim(UUID owner) {
        return limited(() -> claimNow(owner));
    }

    private Optional<JobStatus> claimNow(UUID owner) {
        if (!owns(owner)) return Optional.empty();
        var id = jdbc.sql(
                        "select id from search_jobs where state in ('QUEUED','RUNNING','VERIFYING','ACTIVATING') and (owner_token is null or owner_token=:owner) order by created_at,id limit 1 for update skip locked")
                .param("owner", owner)
                .query(UUID.class)
                .optional();
        if (id.isEmpty()) return Optional.empty();
        jdbc.sql(
                        "update search_jobs set owner_token=:owner,state=case when state='QUEUED' then 'RUNNING' else state end,updated_at=clock_timestamp() where id=:id")
                .param("owner", owner)
                .param("id", id.get())
                .update();
        return find(id.get());
    }

    @Transactional(timeout = 2)
    public boolean checkpoint(
            UUID id, UUID owner, String state, long processed, long failed, VerificationSummary verification) {
        return limited(() -> checkpointNow(id, owner, state, processed, failed, verification));
    }

    private boolean checkpointNow(
            UUID id, UUID owner, String state, long processed, long failed, VerificationSummary verification) {
        if (!owns(owner)) return false;
        return jdbc.sql("""
                update search_jobs j set state=:state,processed_count=greatest(processed_count,:processed),failed_count=greatest(:failed,
                    (select count(*) from search_generation_delivery d join search_projection_versions v
                     on v.entity_type=d.entity_type and v.entity_id=d.entity_id where d.generation_id=j.generation_id
                     and d.error_code is not null and v.revision>d.delivered_revision and v.revision=d.attempted_revision)),
                    verification=cast(:verification as jsonb),updated_at=clock_timestamp()
                where id=:id and owner_token=:owner and state in ('RUNNING','VERIFYING','ACTIVATING')
                """)
                        .param("state", state)
                        .param("processed", processed)
                        .param("failed", failed)
                        .param("verification", verification == null ? null : columns.write(verification))
                        .param("id", id)
                        .param("owner", owner)
                        .update()
                == 1;
    }

    public boolean fail(UUID id, UUID owner, String error) {
        return jdbc.sql("""
                update search_jobs j set state='FAILED',error_code=:error,owner_token=null,updated_at=clock_timestamp(),finished_at=clock_timestamp(),
                    failed_count=(select count(*) from search_generation_delivery d join search_projection_versions v
                    on v.entity_type=d.entity_type and v.entity_id=d.entity_id where d.generation_id=j.generation_id
                    and d.error_code is not null and v.revision>d.delivered_revision and v.revision=d.attempted_revision)
                where id=:id and owner_token=:owner and state in ('RUNNING','VERIFYING','ACTIVATING')
                    and exists(select 1 from search_index_state where id=1 and worker_owner=:owner)
                """)
                        .param("error", error)
                        .param("id", id)
                        .param("owner", owner)
                        .update()
                == 1;
    }

    private boolean owns(UUID owner) {
        return jdbc.sql("select coalesce(worker_owner=:owner,false) from search_index_state where id=1 for share")
                .param("owner", owner)
                .query(Boolean.class)
                .single();
    }

    /** What the audit log keeps of a job operation (ADR-0026): the job's actor and the job as it stands now. */
    public record JobAuditRow(@Nullable Long actorId, Map<String, Object> newRow) {}

    public Optional<JobAuditRow> auditRow(UUID job, String operation) {
        return auditRow(jdbc, job, operation);
    }

    /** {@link #auditRow(UUID, String)} on a connection the caller holds, inside its transaction. */
    public Optional<JobAuditRow> auditRow(JdbcClient connection, UUID job, String operation) {
        return connection
                .sql("""
                select j.actor_id, j.action, j.id, j.generation_id, j.state, g.settings_version,
                    s.version as index_version, g.schema_version
                from search_jobs j join search_generations g on g.id=j.generation_id cross join search_index_state s
                where j.id=:job and s.id=1
                """)
                .param("job", job)
                .query((rs, row) -> {
                    Map<String, Object> newRow = new LinkedHashMap<>();
                    newRow.put("operation", operation);
                    newRow.put("action", rs.getString("action"));
                    newRow.put("job_id", rs.getString("id"));
                    newRow.put("generation_id", rs.getString("generation_id"));
                    newRow.put("state", rs.getString("state"));
                    newRow.put("settings_version", rs.getObject("settings_version"));
                    newRow.put("index_version", rs.getObject("index_version"));
                    newRow.put("schema_version", rs.getObject("schema_version"));
                    return new JobAuditRow(rs.getObject("actor_id", Long.class), newRow);
                })
                .optional();
    }

    private JobStatus mapStatus(ResultSet rs, int row) throws SQLException {
        String verification = rs.getString("verification");
        return new JobStatus(
                rs.getObject("id", UUID.class),
                rs.getString("action"),
                rs.getObject("generation_id", UUID.class),
                rs.getString("state"),
                rs.getLong("processed_count"),
                rs.getLong("failed_count"),
                verification == null ? null : columns.read(verification, VerificationSummary.class),
                rs.getString("error_code"),
                rs.getObject("retry_of_job_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant());
    }

    public record RequestRecord(
            UUID id, String state, String action, UUID requestedGenerationId, UUID retryOfJobId, boolean recorded) {}
}
