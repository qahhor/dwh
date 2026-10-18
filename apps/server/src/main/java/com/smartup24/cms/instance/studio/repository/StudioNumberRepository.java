package com.smartup24.cms.instance.studio.repository;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The counters of the documents' numbers of live forms (ADR-0043, V228): one row per form and period, raised in the
 * transaction that gives a number. Its row lock holds until the commit, so two saves of one form never take one number,
 * and a rollback takes the raise back with the record — a number is given to one committed record only and never again.
 */
@Repository
public class StudioNumberRepository {
    private final JdbcClient jdbc;

    public StudioNumberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The next number of the form in the period ({@code ""}, {@code 2026} or {@code 2026-10}), 1 for the first. */
    public long next(long formId, String period) {
        Long number = jdbc.sql("""
                        insert into studio_form_number_counters as c (form_id, period, last_number)
                        values (:form, :period, 1)
                        on conflict (form_id, period) do update set last_number = c.last_number + 1
                        returning c.last_number""")
                .param("form", formId)
                .param("period", period)
                .query(Long.class)
                .single();
        return Objects.requireNonNull(number, "studio_form_number_counters");
    }
}
