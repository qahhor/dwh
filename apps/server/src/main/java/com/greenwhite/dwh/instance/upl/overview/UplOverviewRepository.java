package com.greenwhite.dwh.instance.upl.overview;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Figures of the data overview (roadmap wave 5) read straight from the UPL tables. */
@Repository
public class UplOverviewRepository {

    private final JdbcClient jdbc;

    public UplOverviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uploads since a moment, by status, and the rows that reached the data warehouse. */
    public record Totals(long uploads, long received, long verified, long rejected, long applied, long rowsApplied) {
    }

    public Totals totals(Instant since) {
        return totals(since, null);
    }

    /** Uploads in [from, to); no {@code to} — up to now. */
    public Totals totals(Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) as uploads,
                               count(*) filter (where status = 'received') as received,
                               count(*) filter (where status = 'verified') as verified,
                               count(*) filter (where status = 'rejected') as rejected,
                               count(*) filter (where status = 'applied') as applied,
                               coalesce(sum(rows_accepted) filter (where status = 'applied'), 0) as rows_applied
                          from upl_packages
                         where uploaded_at >= :since and (cast(:until as timestamptz) is null or uploaded_at < :until)
                        """)
                .param("since", Timestamp.from(from))
                .param("until", to == null ? null : Timestamp.from(to))
                .query((rs, n) -> new Totals(rs.getLong("uploads"), rs.getLong("received"), rs.getLong("verified"),
                        rs.getLong("rejected"), rs.getLong("applied"), rs.getLong("rows_applied")))
                .single();
    }
    /**
     * A source with the last period that reached the warehouse and when it did (the moment the upload turned
     * applied; the load journal itself belongs to the foundation and is not read here).
     */
    public record SourceFreshnessRow(long id, String code, String name, String periodicity, int slaDays,
                                     LocalDate lastPeriodTo, Instant lastAppliedAt) {
    }

    public List<SourceFreshnessRow> sourceFreshness() {
        return jdbc.sql("""
                        select s.id, s.code, s.name, s.periodicity, s.sla_days,
                               (select max(p.period_to) from upl_packages p
                                 where p.source_id = s.id and p.status = 'applied') as last_period_to,
                               (select max(p.modified_at) from upl_packages p
                                 where p.source_id = s.id and p.status = 'applied') as last_applied_at
                          from upl_sources s
                         order by s.name, s.id
                        """)
                .query((rs, n) -> new SourceFreshnessRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("periodicity"), rs.getInt("sla_days"), rs.getObject("last_period_to", LocalDate.class),
                        rs.getTimestamp("last_applied_at") == null ? null : rs.getTimestamp("last_applied_at").toInstant()))
                .list();
    }

    /** An upload somebody has to act on. */
    public record PackageAttentionRow(UUID publicId, long sourceId, String sourceCode, String sourceName, String fileName,
                                      LocalDate periodFrom, LocalDate periodTo, Instant uploadedAt) {
    }

    /** Checked uploads waiting to be applied, oldest first. */
    public List<PackageAttentionRow> waitingToApply(int limit) {
        return packages("p.status = 'verified'", null, limit);
    }

    /** Rejected uploads of the period that no later checked or applied upload of the same source and period replaced. */
    public List<PackageAttentionRow> rejectedNotReplaced(Instant since, int limit) {
        return packages("""
                p.status = 'rejected' and p.uploaded_at >= :since
                and not exists (select 1 from upl_packages q
                                 where q.source_id = p.source_id and q.period_from = p.period_from
                                   and q.uploaded_at > p.uploaded_at and q.status in ('verified', 'applied'))""", since, limit);
    }

    private List<PackageAttentionRow> packages(String where, Instant since, int limit) {
        // The condition comes from this class only; values travel as parameters.
        var query = jdbc.sql("select p.public_id, s.id as source_id, s.code, s.name, p.file_name, p.period_from, p.period_to, p.uploaded_at"
                        + " from upl_packages p join upl_sources s on s.id = p.source_id"
                        + " where " + where
                        + " order by p.uploaded_at, p.id limit :limit")
                .param("limit", limit);
        if (since != null) query.param("since", Timestamp.from(since));
        return query.query((rs, n) -> new PackageAttentionRow(rs.getObject("public_id", UUID.class), rs.getLong("source_id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("file_name"), rs.getObject("period_from", LocalDate.class),
                        rs.getObject("period_to", LocalDate.class), rs.getTimestamp("uploaded_at").toInstant()))
                .list();
    }
    /** Uploads of one UTC day by outcome: applied, rejected and the rest (received or checked). */
    public record DayRow(LocalDate day, long applied, long rejected, long other) {
    }

    public List<DayRow> daily(Instant since) {
        return jdbc.sql("""
                        select (uploaded_at at time zone 'UTC')::date as day,
                               count(*) filter (where status = 'applied') as applied,
                               count(*) filter (where status = 'rejected') as rejected,
                               count(*) filter (where status in ('received', 'verified')) as other
                          from upl_packages
                         where uploaded_at >= :since
                         group by 1
                         order by 1
                        """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getLong("applied"),
                        rs.getLong("rejected"), rs.getLong("other")))
                .list();
    }
}
