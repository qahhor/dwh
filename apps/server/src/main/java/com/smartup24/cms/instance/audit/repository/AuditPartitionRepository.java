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
 * Обслуживание партиций {@code audit_log} (FR-AUD-2): месячных — прошлых и текущей, дневных — с V127.
 *
 * Это регламентное обслуживание, а не эволюция схемы: версия Flyway не
 * меняется, структура таблиц не трогается, schema-gate (FR-INST-2) ничего не
 * замечает. Запрет NFR-10 «приложение не мигрирует схему» сюда не относится —
 * иначе партиции пришлось бы досоздавать миграцией вручную.
 *
 * DDL выполняют SECURITY DEFINER функции (I-02): у приложения нет прав DDL и владения audit_log.
 */
@Repository
public class AuditPartitionRepository {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final DateTimeFormatter DAY_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM_dd");
    /** Имя партиции журнала: месячной или дневной, прикреплённой или отцеплённой сроком хранения. */
    private static final Pattern NAME = Pattern.compile("^audit_log_(archived_)?(\\d{4})_(\\d{2})(?:_(\\d{2}))?$");

    private final JdbcClient jdbc;

    public AuditPartitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Партиция журнала за период {@code [from, to)}.
     *
     * @param attached прикреплена к {@code audit_log}; отцеплённая сроком хранения называется
     *                 {@code audit_log_archived_*}
     */
    public record AuditPartition(String name, LocalDate from, LocalDate to, boolean attached) {

        public boolean daily() {
            return to.equals(from.plusDays(1));
        }

        /** Закрыта: в неё больше не попадёт ни одна строка, её можно выгружать. */
        public boolean closedBy(LocalDate today) {
            return !to.isAfter(today);
        }
    }

    /** Разбирает имя партиции; имена, выведенные не из даты, сюда не попадают. */
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

    /** Имя партиции за месяц. Выведено из даты, пользовательский ввод сюда не попадает. */
    public static String partitionName(YearMonth month) {
        return "audit_log_" + month.format(SUFFIX);
    }

    public static String dayPartitionName(LocalDate day) {
        return "audit_log_" + day.format(DAY_SUFFIX);
    }

    public boolean exists(YearMonth month) {
        return tableExists(partitionName(month));
    }

    /** День уже покрыт партицией: месячной, если месяц ещё месячный, или дневной. */
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

    /** Месячная партиция (V033): для месяцев, которые ещё месячные, и для тестов обслуживания. */
    public void create(YearMonth month) {
        jdbc.sql("select audit_log_create_partition(:year, :month)")
                .param("year", month.getYear())
                .param("month", month.getMonthValue())
                .query(String.class)
                .single();
    }

    /**
     * Дневная партиция (V127). Если месяц ещё месячный, функция ничего не создаёт и возвращает имя месячной.
     *
     * @return имя партиции, которая покрывает день
     */
    public String createDay(LocalDate day) {
        return jdbc.sql("select audit_log_create_day_partition(:day)")
                .param("day", day)
                .query(String.class)
                .single();
    }

    /**
     * Все партиции журнала: прикреплённые (кроме аварийного приёмника {@code audit_log_default}, у него нет
     * границ) и отцеплённые сроком хранения, по возрастанию начала периода.
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
     * Месячные партиции, прикреплённые к {@code audit_log} и относящиеся к месяцам строго раньше {@code before}.
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
     * Отцепляет месячную партицию и переименовывает её в {@code audit_log_archived_YYYY_MM} (V033, V125).
     *
     * Данные НЕ удаляются: срок хранения кончился для оперативного журнала, а не для самих записей.
     * Удаляет данные только выгрузка в архив ({@link #dropArchived}), и только когда это включено.
     */
    public String detachAndArchive(YearMonth month) {
        return jdbc.sql("select audit_log_detach_partition(:year, :month)")
                .param("year", month.getYear())
                .param("month", month.getMonthValue())
                .query(String.class)
                .single();
    }

    /** Срок хранения для дневной партиции (V127): отцепить и переименовать в {@code audit_log_archived_*}. */
    public String detachDay(LocalDate day) {
        return jdbc.sql("select audit_log_detach_day_partition(:day)")
                .param("day", day)
                .query(String.class)
                .single();
    }

    /** Место, которое партиция занимает на диске, с индексами. */
    public long sizeBytes(AuditPartition partition) {
        Long size = jdbc.sql("select pg_total_relation_size(cast(:name as regclass))")
                .param("name", partition.name())
                .query(Long.class)
                .single();
        return size != null ? size : 0L;
    }

    /**
     * Удаляет закрытую партицию, которую держит проверенный архив (V127). Функция сама проверяет, что архив
     * есть и сверен, и отмечает партицию удалённой.
     */
    public void dropArchived(AuditPartition partition) {
        jdbc.sql("select audit_log_drop_archived_partition(:name)")
                .param("name", partition.name())
                .query()
                .singleValue();
    }

    /** Строки в аварийном приёмнике: их наличие блокирует создание партиции за тот же период. */
    public long countDefaultRows() {
        Long count = jdbc.sql("select count(*) from audit_log_default")
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }
}
