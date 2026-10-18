package com.smartup24.cms.instance.report.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The runs of report mailings and what each recipient got (ADR-0045). A scheduled slot has one run row per mailing (a
 * unique index), so two nodes taking the same slot create one run; a delivery is unique per run, user and channel, so a
 * retried run never records or sends twice.
 */
@Repository
public class ReportMailingRunRepository {
    private static final String SELECT = """
            select r.id, r.mailing_id, r.kind, r.slot, r.status, r.requested_by, r.recipients, r.sent, r.skipped,
                   r.failed, r.error, r.created_at, r.finished_at
            from report_mailing_runs r
            """;

    private final JdbcClient jdbc;

    public record RunRow(
            long id,
            long mailingId,
            String kind,
            Instant slot,
            String status,
            @Nullable Long requestedBy,
            int recipients,
            int sent,
            int skipped,
            int failed,
            @Nullable String error,
            Instant createdAt,
            @Nullable Instant finishedAt) {}

    /** A delivery to record: {@code status} sent, queued, skipped or failed, with its reason and outbox key. */
    public record DeliveryValues(
            long userId,
            String channel,
            String status,
            @Nullable String reason,
            @Nullable UUID outboxKey,
            @Nullable Integer rows) {}

    public record DeliveryRow(
            long userId,
            String userName,
            String channel,
            String status,
            @Nullable String reason,
            @Nullable UUID outboxKey,
            @Nullable Integer rows) {}

    public ReportMailingRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The run of a scheduled slot, unless one exists already: then empty. */
    public Optional<Long> insertScheduled(long mailingId, Instant slot) {
        return jdbc.sql("""
                        insert into report_mailing_runs (mailing_id, kind, slot)
                        values (:mailing, 'scheduled', :slot)
                        on conflict (mailing_id, slot) where kind = 'scheduled' do nothing
                        returning id
                        """)
                .param("mailing", mailingId)
                .param("slot", Timestamp.from(slot))
                .query(Long.class)
                .optional();
    }

    /** A send now ({@code manual}) or a test send ({@code test}) asked by a person. */
    public long insertRequested(long mailingId, String kind, Instant at, long requestedBy) {
        return jdbc.sql("""
                        insert into report_mailing_runs (mailing_id, kind, slot, requested_by)
                        values (:mailing, :kind, :slot, :user)
                        returning id
                        """)
                .param("mailing", mailingId)
                .param("kind", kind)
                .param("slot", Timestamp.from(at))
                .param("user", requestedBy)
                .query(Long.class)
                .single();
    }

    /** The sends now and test sends asked for a mailing since {@code since}. */
    public long countRequestedSince(long mailingId, Instant since) {
        Long count = jdbc.sql("""
                        select count(*) from report_mailing_runs
                        where mailing_id = :mailing and kind in ('manual', 'test') and created_at >= :since
                        """)
                .param("mailing", mailingId)
                .param("since", Timestamp.from(since))
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Takes a queued run, or one a node left running, and marks it running; empty when it is finished or gone. */
    public Optional<RunRow> claim(long runId) {
        return jdbc.sql("""
                        update report_mailing_runs
                           set status = 'running', started_at = coalesce(started_at, clock_timestamp())
                         where id = :id and status in ('queued', 'running')
                        returning id, mailing_id, kind, slot, status, requested_by, recipients, sent, skipped, failed,
                                  error, created_at, finished_at
                        """).param("id", runId).query((rs, row) -> row(rs)).optional();
    }

    public void finish(
            long runId, String status, int recipients, int sent, int skipped, int failed, @Nullable String error) {
        jdbc.sql("""
                        update report_mailing_runs
                           set status = :status, recipients = :recipients, sent = :sent, skipped = :skipped,
                               failed = :failed, error = :error, finished_at = clock_timestamp()
                         where id = :id
                        """)
                .param("id", runId)
                .param("status", status)
                .param("recipients", recipients)
                .param("sent", sent)
                .param("skipped", skipped)
                .param("failed", failed)
                .param("error", error)
                .update();
    }

    /** The users a run has recorded deliveries for. */
    public Set<Long> recordedUsers(long runId) {
        return new HashSet<>(jdbc.sql("select distinct user_id from report_mailing_deliveries where run_id = :run")
                .param("run", runId)
                .query(Long.class)
                .list());
    }

    public void insertDeliveries(long runId, List<DeliveryValues> deliveries) {
        for (DeliveryValues delivery : deliveries) {
            jdbc.sql("""
                            insert into report_mailing_deliveries
                                (run_id, user_id, channel, status, reason, outbox_key, row_count)
                            values (:run, :user, :channel, :status, :reason, :key, :rows)
                            on conflict (run_id, user_id, channel) do nothing
                            """)
                    .param("run", runId)
                    .param("user", delivery.userId())
                    .param("channel", delivery.channel())
                    .param("status", delivery.status())
                    .param("reason", delivery.reason())
                    .param("key", delivery.outboxKey())
                    .param("rows", delivery.rows())
                    .update();
        }
    }

    /** The counts of a run's deliveries by status. */
    public Map<String, Integer> statusCounts(long runId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql("select status, count(*) as n from report_mailing_deliveries where run_id = :run group by status")
                .param("run", runId)
                .query(rs -> {
                    while (rs.next()) counts.put(rs.getString("status"), rs.getInt("n"));
                    return counts;
                });
        return counts;
    }

    /** The runs of a mailing, the newest first; {@code limit + 1} rows tell whether more follow. */
    public List<RunRow> page(long mailingId, TimePage page) {
        TimePage.Position after = page.after();
        String where =
                " where r.mailing_id = :mailing" + (after == null ? "" : " and (r.created_at, r.id) < (:at, :id)");
        var statement = jdbc.sql(SELECT + where + " order by r.created_at desc, r.id desc limit :limit")
                .param("mailing", mailingId)
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> row(rs)).list();
    }

    public Optional<RunRow> find(long mailingId, long runId) {
        return jdbc.sql(SELECT + " where r.id = :id and r.mailing_id = :mailing")
                .param("id", runId)
                .param("mailing", mailingId)
                .query((rs, row) -> row(rs))
                .optional();
    }

    /** The latest run of each mailing. */
    public Map<Long, RunRow> latest(Collection<Long> mailingIds) {
        Map<Long, RunRow> latest = new HashMap<>();
        if (mailingIds.isEmpty()) return latest;
        jdbc.sql("""
                        select distinct on (r.mailing_id) r.id, r.mailing_id, r.kind, r.slot, r.status,
                               r.requested_by, r.recipients, r.sent, r.skipped, r.failed, r.error, r.created_at,
                               r.finished_at
                        from report_mailing_runs r
                        where r.mailing_id in (:ids)
                        order by r.mailing_id, r.created_at desc, r.id desc
                        """)
                .param("ids", List.copyOf(mailingIds))
                .query((rs, row) -> row(rs))
                .list()
                .forEach(run -> latest.put(run.mailingId(), run));
        return latest;
    }

    /** What each recipient got in a run, by name and channel. */
    public List<DeliveryRow> deliveries(long runId) {
        return jdbc.sql("""
                        select d.user_id, u.name, d.channel, d.status, d.reason, d.outbox_key, d.row_count
                        from report_mailing_deliveries d
                        join md_pub_users u on u.id = d.user_id
                        where d.run_id = :run
                        order by u.name, d.user_id, d.channel
                        """)
                .param("run", runId)
                .query((rs, row) -> new DeliveryRow(
                        rs.getLong("user_id"),
                        rs.getString("name"),
                        rs.getString("channel"),
                        rs.getString("status"),
                        rs.getString("reason"),
                        rs.getObject("outbox_key", UUID.class),
                        rs.getObject("row_count", Integer.class)))
                .list();
    }

    private static RunRow row(ResultSet rs) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new RunRow(
                rs.getLong("id"),
                rs.getLong("mailing_id"),
                rs.getString("kind"),
                rs.getTimestamp("slot").toInstant(),
                rs.getString("status"),
                rs.getObject("requested_by", Long.class),
                rs.getInt("recipients"),
                rs.getInt("sent"),
                rs.getInt("skipped"),
                rs.getInt("failed"),
                rs.getString("error"),
                rs.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
