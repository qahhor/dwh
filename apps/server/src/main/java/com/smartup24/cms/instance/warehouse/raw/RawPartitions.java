package com.smartup24.cms.instance.warehouse.raw;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The layout of {@code raw.rows} in pg-dwh (plan 10/10, item 7.8; ADR-0030, section 7): a table partitioned by list on
 * {@code load_id}, one partition {@code raw.rows_<load_id>} per load.
 *
 * <p>A load's partition is born outside the partitioned table: the writer creates a plain table, fills it with
 * {@code COPY}, builds its key and index and only then attaches it, all in one transaction. Until the attach the write
 * holds no lock on {@code raw.rows}, so a concurrent cleanup ({@code DETACH ... CONCURRENTLY}) of another load does
 * not wait for a copy of minutes; the attach itself takes {@code SHARE UPDATE EXCLUSIVE}, which readers do not wait
 * for, and finds the check constraint, so it scans nothing. The columns are spelled out rather than taken with
 * {@code LIKE raw.rows}: {@code LIKE} would lock the parent for the whole copy.
 */
public final class RawPartitions {

    /** The schema of the raw layer. */
    public static final String SCHEMA = "raw";

    private static final String PREFIX = "rows_";
    private static final Pattern NAME = Pattern.compile("^" + PREFIX + "([1-9][0-9]{0,18})$");

    private RawPartitions() {}

    /** The bare name of a load's partition, {@code rows_<load_id>}. */
    public static String name(long loadId) {
        if (loadId <= 0) {
            throw new IllegalArgumentException("A load id is positive: " + loadId);
        }
        return PREFIX + loadId;
    }

    /** The qualified name of a load's partition, {@code raw.rows_<load_id>}. */
    public static String table(long loadId) {
        return SCHEMA + "." + name(loadId);
    }

    /** The load a table of the raw schema holds, when its name is that of a load's partition. */
    public static OptionalLong loadIdOf(String tableName) {
        Matcher matcher = NAME.matcher(tableName);
        if (!matcher.matches()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException tooLarge) {
            return OptionalLong.empty();
        }
    }

    /** Whether the load's partition is attached to {@code raw.rows}: read from the catalog, no table lock. */
    static boolean attached(Connection connection, long loadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select 1
                  from pg_inherits i
                  join pg_class c on c.oid = i.inhrelid
                  join pg_namespace n on n.oid = c.relnamespace
                 where i.inhparent = 'raw.rows'::regclass and n.nspname = ? and c.relname = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, name(loadId));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Creates the load's partition as a plain table, not yet attached: the copy fills it without index upkeep. */
    static void create(Connection connection, long loadId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table " + table(loadId) + " ("
                    + " load_id bigint not null,"
                    + " source_file_id uuid,"
                    + " row_no bigint not null,"
                    + " sheet text,"
                    + " source_row_no integer,"
                    + " fields jsonb not null,"
                    + " loaded_at timestamptz not null default now())");
        }
    }

    /**
     * Builds the key and the index of a filled partition and attaches it. The check constraint proves the partition
     * bound, so the attach does not scan the rows; the indexes match those of {@code raw.rows} and are adopted.
     */
    static void attach(Connection connection, long loadId) throws SQLException {
        String table = table(loadId);
        String name = name(loadId);
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "alter table " + table + " add constraint " + name + "_ck_load check (load_id = " + loadId + ")");
            statement.execute("alter table " + table + " add constraint " + name + "_pk primary key (load_id, row_no)");
            statement.execute("create index " + name + "_source_file_idx on " + table
                    + " (source_file_id) where source_file_id is not null");
            statement.execute("alter table raw.rows attach partition " + table + " for values in (" + loadId + ")");
        }
    }
}
