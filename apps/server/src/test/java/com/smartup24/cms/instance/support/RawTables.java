package com.smartup24.cms.instance.support;

import com.smartup24.cms.instance.warehouse.raw.RawPartitions;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test helpers for {@code raw.rows} in pg-dwh, partitioned by load (plan 10/10, item 7.8). Raw rows cannot be deleted
 * (a row trigger forbids it), so a test empties raw by dropping the load partitions, and a test that plants rows by
 * hand first creates the partition of their load.
 */
public final class RawTables {

    private RawTables() {}

    /** Drops every load partition of raw, attached or not: raw is empty afterwards. */
    public static void clear(JdbcClient dwh) {
        dwh.sql("""
                        do $$
                        declare
                            t record;
                        begin
                            for t in select c.oid::regclass as rel
                                       from pg_class c
                                       join pg_namespace n on n.oid = c.relnamespace
                                      where n.nspname = 'raw' and c.relkind = 'r' and c.relname ~ '^rows_[0-9]+$'
                            loop
                                execute format('drop table %s', t.rel);
                            end loop;
                        end
                        $$
                        """).update();
    }

    /** Creates the attached partition of a load, for rows a test inserts with plain SQL. */
    public static void partition(JdbcClient dwh, long loadId) {
        dwh.sql("create table if not exists " + RawPartitions.table(loadId) + " partition of raw.rows for values in ("
                        + loadId + ")")
                .update();
    }
}
