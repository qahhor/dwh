package com.smartup24.cms.instance.audit.repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Maintenance of {@code audit_log} partitions (FR-AUD-2): monthly ones for past and current months, daily ones
 * since V127.
 *
 * This is routine maintenance, not schema evolution: the Flyway version does not
 * change, table structure is untouched, and the schema gate (FR-INST-2) notices
 * nothing. The NFR-10 rule "the application does not migrate the schema" does not
 * apply here; otherwise partitions would have to be added by hand with migrations.
 *
 * DDL runs in SECURITY DEFINER functions: the application has no DDL rights and does not own audit_log.
 */
@Repository
public class AuditPartitionRepository {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final DateTimeFormatter DAY_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM_dd");
    /** A log partition name: monthly or daily, attached or detached by retention. */
    private static final Pattern NAME = Pattern.compile("^audit_log_(archived_)?(\\d{4})_(\\d{2})(?:_(\\d{2}))?$");

    private final JdbcClient jdbc;

    public AuditPartitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A log partition covering the period {@code [from, to)}.
     *
     * @param attached attached to {@code audit_log}; a partition detached by retention is named
     *                 {@code audit_log_archived_*}
     */
    public record AuditPartition(String name, LocalDate from, LocalDate to, boolean attached) {

        public boolean daily() {
            return to.equals(from.plusDays(1));
        }

        /** Closed: no row will ever land in it again, so it can be archived. */
        public boolean closedBy(LocalDate today) {
            return !to.isAfter(today);
        }
    }

    /** Parses a partition name; names not derived from a date never reach this method. */
    public static Optional<AuditPartition> parse(String name, boolean attached) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(m.group(2));
        int month = Integer.parseInt(m.group(3));
        if (m.group(4) == null) {
            LocalDate from = LocalDate.of(year, month, 1);
            return Optional.of(new AuditPartition(name, from, from.plusMonths(1), attached));
        }
        LocalDate day = LocalDate.of(year, month, Integer.parseInt(m.group(4)));
        return Optional.of(new AuditPartition(name, day, day.plusDays(1), attached));
    }

    /** The partition name for a month. Derived from a date; user input never reaches it. */
    public static String partitionName(YearMonth month) {
        return "audit_log_" + month.format(SUFFIX);
    }

    public static String dayPartitionName(LocalDate day) {
        return "audit_log_" + day.format(DAY_SUFFIX);
    }

    public boolean exists(YearMonth month) {
        return tableExists(partitionName(month));
    }

    /** The day is already covered by a partition: a monthly one if the month is still monthly, else a daily one. */
    public boolean covers(LocalDate day) {
        return tableExists(partitionName(YearMonth.from(day))) || tableExists(dayPartitionName(day));
    }

    /** In the schema the application works in, not in any schema of the database. */
    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbc.sql("select to_regclass(:name) is not null")
                .param("name", name)
                .query(Boolean.class)
                .single());
    }

    /** A monthly partition (V033): for months that are still monthly, and for maintenance tests. */
    public void create(YearMonth month) {
        jdbc.sql("select audit_log_create_partition(:year, :month)")
                .param("year", month.getYear())
                .param("month", month.getMonthValue())
                .query(String.class)
                .single();
    }

    /**
     * A daily partition (V127). If the month is still monthly, the function creates nothing and returns the
     * monthly partition's name.
     *
     * @return the name of the partition that covers the day
     */
    public String createDay(LocalDate day) {
        return jdbc.sql("select audit_log_create_day_partition(:day)")
                .param("day", day)
                .query(String.class)
                .single();
    }

    /**
     * All log partitions: attached ones (except the fallback {@code audit_log_default}, which has no bounds) and
     * those detached by retention, ordered by period start.
     */
    public List<AuditPartition> partitions() {
        List<AuditPartition> result = new ArrayList<>();
        jdbc.sql("""
                        select c.relname,
                               exists (select 1
                                       from pg_inherits i
                                       join pg_class p on p.oid = i.inhparent
                                       where i.inhrelid = c.oid and p.oid = to_regclass('audit_log')) as attached
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = current_schema() and c.relkind = 'r'
                          and c.relname ~ '^audit_log_(archived_)?[0-9]{4}_[0-9]{2}(_[0-9]{2})?$'
                        """)
                .query((rs, rowNum) -> parse(rs.getString("relname"), rs.getBoolean("attached")))
                .list()
                .forEach(partition -> partition.ifPresent(result::add));
        result.sort((a, b) -> a.from().compareTo(b.from()));
        return result;
    }

    /**
     * Monthly partitions attached to {@code audit_log} for months strictly before {@code before}.
     */
    public List<YearMonth> attachedPartitionsBefore(YearMonth before) {
        List<YearMonth> result = new ArrayList<>();
        for (AuditPartition partition : partitions()) {
            if (partition.attached()
                    && !partition.daily()
                    && YearMonth.from(partition.from()).isBefore(before)) {
                result.add(YearMonth.from(partition.from()));
            }
        }
        return result;
    }

    /**
     * Detaches a monthly partition and renames it to {@code audit_log_archived_YYYY_MM} (V033, V125).
     *
     * Data is NOT deleted: retention ended for the operational log, not for the records themselves.
     * Only the archive export deletes data ({@link #dropArchived}), and only when that is enabled.
     */
    public String detachAndArchive(YearMonth month) {
        return jdbc.sql("select audit_log_detach_partition(:year, :month)")
                .param("year", month.getYear())
                .param("month", month.getMonthValue())
                .query(String.class)
                .single();
    }

    /** Retention for a daily partition (V127): detach it and rename it to {@code audit_log_archived_*}. */
    public String detachDay(LocalDate day) {
        return jdbc.sql("select audit_log_detach_day_partition(:day)")
                .param("day", day)
                .query(String.class)
                .single();
    }

    /** The disk space the partition takes, including indexes. */
    public long sizeBytes(AuditPartition partition) {
        Long size = jdbc.sql("select pg_total_relation_size(cast(:name as regclass))")
                .param("name", partition.name())
                .query(Long.class)
                .single();
        return size != null ? size : 0L;
    }

    /**
     * Drops a closed partition held by a verified archive (V127). The function itself checks that the archive
     * exists and is reconciled, and marks the partition as dropped.
     */
    public void dropArchived(AuditPartition partition) {
        jdbc.sql("select audit_log_drop_archived_partition(:name)")
                .param("name", partition.name())
                .query()
                .singleValue();
    }

    /** Rows in the fallback partition: while they exist, a partition for the same period cannot be created. */
    public long countDefaultRows() {
        Long count = jdbc.sql("select count(*) from audit_log_default")
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }
}
