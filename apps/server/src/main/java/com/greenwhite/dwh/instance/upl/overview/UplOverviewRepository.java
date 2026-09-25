package com.greenwhite.dwh.instance.upl.overview;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

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
        return jdbc.sql("""
                        select count(*) as uploads,
                               count(*) filter (where status = 'received') as received,
                               count(*) filter (where status = 'verified') as verified,
                               count(*) filter (where status = 'rejected') as rejected,
                               count(*) filter (where status = 'applied') as applied,
                               coalesce(sum(rows_accepted) filter (where status = 'applied'), 0) as rows_applied
                          from upl_packages
                         where uploaded_at >= :since
                        """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new Totals(rs.getLong("uploads"), rs.getLong("received"), rs.getLong("verified"),
                        rs.getLong("rejected"), rs.getLong("applied"), rs.getLong("rows_applied")))
                .single();
    }
}
