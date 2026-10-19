package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The runs of automations (ADR-0046): queued with the change that triggered them — unique per automation and event, so
 * a repeated event queues nothing — taken one record at a time in the order they were queued, and closed with what their
 * actions did.
 */
@Repository
public class StudioAutomationRunRepository {
    private static final String SELECT = """
            select id, automation_id, event_key, kind, trigger_kind, form_code, record_id, depth, payload::text as payload,
                   status, reason, error::text as error, results::text as results, attempts, not_before, created_at,
                   finished_at, not_before <= clock_timestamp() as due
            from studio_automation_runs
            """;

    private final JdbcClient jdbc;

    /**
     * A run as stored: its payload, error and results as JSON text; {@code due} — its attempt may start now, by the
     * database's clock, the one that set {@code not_before}.
     */
    public record RunRow(
            long id,
            long automationId,
            String eventKey,
            String kind,
            String triggerKind,
            String form,
            long recordId,
            int depth,
            String payload,
            String status,
            @Nullable String reason,
            @Nullable String error,
            String results,
            int attempts,
            Instant notBefore,
            Instant createdAt,
            @Nullable Instant finishedAt,
            boolean due) {}

    /** A run to queue. */
    public record NewRun(
            long automationId,
            String eventKey,
            String kind,
            String triggerKind,
            String form,
            long recordId,
            int depth,
            String payload) {}

    /** A record with runs to take, by its form. */
    public record RecordKey(String form, long recordId) {}

    public StudioAutomationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues the run unless the automation already has one for the event; answers whether it was queued. */
    public boolean insert(NewRun run) {
        return jdbc.sql("""
                        insert into studio_automation_runs
                            (automation_id, event_key, kind, trigger_kind, form_code, record_id, depth, payload)
                        values (:automation, :key, :kind, :trigger, :form, :record, :depth, cast(:payload as jsonb))
                        on conflict (automation_id, event_key) do nothing
                        """)
                        .param("automation", run.automationId())
                        .param("key", run.eventKey())
                        .param("kind", run.kind())
                        .param("trigger", run.triggerKind())
                        .param("form", run.form())
                        .param("record", run.recordId())
                        .param("depth", run.depth())
                        .param("payload", run.payload())
                        .update()
                == 1;
    }

    /** How many runs the automation queued for the record since {@code since}. */
    public int countForRecord(long automationId, long recordId, Instant since) {
        Integer count = jdbc.sql("""
                        select count(*) from studio_automation_runs
                        where automation_id = :automation and record_id = :record and created_at >= :since
                        """)
                .param("automation", automationId)
                .param("record", recordId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** How many runs every automation queued since {@code since}. */
    public int countSince(Instant since) {
        Integer count = jdbc.sql("select count(*) from studio_automation_runs where created_at >= :since")
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Holds the runs of one record for this transaction: a second node waits, so they run in order. */
    public void lockRecord(String form, long recordId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('studio.automation:' || :form), cast(:record as integer))")
                .param("form", form)
                .param("record", (int) (recordId ^ (recordId >>> 32)))
                .query(Object.class)
                .single();
    }

    /** The first queued run of the record, locked; empty when none is queued. */
    public Optional<RunRow> nextQueued(String form, long recordId) {
        return jdbc.sql(SELECT
                        + " where form_code = :form and record_id = :record and status = 'queued'"
                        + " order by id limit 1 for update")
                .param("form", form)
                .param("record", recordId)
                .query((rs, row) -> row(rs))
                .optional();
    }

    /**
     * The records with a queued run due by {@code now} and queued before {@code stale} — a run queued later is taken by
     * the job queued with it — at most {@code limit}.
     */
    public List<RecordKey> dueRecords(Instant now, Instant stale, int limit) {
        return jdbc.sql("""
                        select form_code, record_id from studio_automation_runs
                        where status = 'queued' and not_before <= :now and created_at <= :stale
                        group by form_code, record_id
                        order by min(id) limit :limit
                        """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("stale", OffsetDateTime.ofInstant(stale, ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, row) -> new RecordKey(rs.getString("form_code"), rs.getLong("record_id")))
                .list();
    }

    /** When the oldest queued run was queued, if any is queued. */
    public Optional<Instant> oldestQueued() {
        List<Instant> oldest =
                jdbc.sql("""
                        select created_at from studio_automation_runs where status = 'queued'
                        order by created_at limit 1
                        """).query((rs, row) -> rs.getTimestamp(1).toInstant()).list();
        return oldest.stream().findFirst();
    }

    /** Closes the run with its outcome and what its actions did. */
    public void finish(long id, String status, @Nullable String reason, @Nullable String error, String results) {
        jdbc.sql("""
                        update studio_automation_runs
                        set status = :status, reason = :reason, error = cast(:error as jsonb),
                            results = cast(:results as jsonb), attempts = attempts + 1,
                            finished_at = clock_timestamp()
                        where id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("reason", reason)
                .param("error", error)
                .param("results", results)
                .update();
    }

    /** Leaves the run queued for another attempt at {@code notBefore}, with the problem of this one. */
    public void retryAt(long id, Instant notBefore, @Nullable String reason, @Nullable String error) {
        jdbc.sql("""
                        update studio_automation_runs
                        set attempts = attempts + 1, not_before = :at, reason = :reason, error = cast(:error as jsonb)
                        where id = :id and status = 'queued'
                        """)
                .param("id", id)
                .param("at", OffsetDateTime.ofInstant(notBefore, ZoneOffset.UTC))
                .param("reason", reason)
                .param("error", error)
                .update();
    }

    /** The runs of an automation, the newest first; {@code limit + 1} rows tell whether more follow. */
    public List<RunRow> page(long automationId, TimePage page) {
        TimePage.Position after = page.after();
        String where =
                " where automation_id = :automation" + (after == null ? "" : " and (created_at, id) < (:at, :id)");
        var statement = jdbc.sql(SELECT + where + " order by created_at desc, id desc limit :limit")
                .param("automation", automationId)
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> row(rs)).list();
    }

    /** The last run of each automation named. */
    public Map<Long, RunRow> latest(Collection<Long> automationIds) {
        Map<Long, RunRow> latest = new LinkedHashMap<>();
        if (automationIds.isEmpty()) return latest;
        jdbc.sql(SELECT
                        + " where id in (select max(id) from studio_automation_runs where automation_id in (:ids)"
                        + " group by automation_id)")
                .param("ids", List.copyOf(automationIds))
                .query((rs, row) -> row(rs))
                .list()
                .forEach(run -> latest.put(run.automationId(), run));
        return latest;
    }

    /** How many runs a person asked for of the automation since {@code since}. */
    public int countManualSince(long automationId, Instant since) {
        Integer count = jdbc.sql("""
                        select count(distinct split_part(event_key, ':', 2)) from studio_automation_runs
                        where automation_id = :automation and kind = 'manual' and created_at >= :since
                        """)
                .param("automation", automationId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private static RunRow row(ResultSet rs) throws SQLException {
        return new RunRow(
                rs.getLong("id"),
                rs.getLong("automation_id"),
                rs.getString("event_key"),
                rs.getString("kind"),
                rs.getString("trigger_kind"),
                rs.getString("form_code"),
                rs.getLong("record_id"),
                rs.getInt("depth"),
                rs.getString("payload"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getString("error"),
                rs.getString("results"),
                rs.getInt("attempts"),
                rs.getTimestamp("not_before").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                StudioAutomationRepository.instant(rs.getTimestamp("finished_at")),
                rs.getBoolean("due"));
    }
}
