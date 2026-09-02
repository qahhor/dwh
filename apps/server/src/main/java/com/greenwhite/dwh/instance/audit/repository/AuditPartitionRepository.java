package com.greenwhite.dwh.instance.audit.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Обслуживание месячных партиций {@code audit_log} (FR-AUD-2).
 *
 * Это регламентное обслуживание, а не эволюция схемы: версия Flyway не
 * меняется, структура таблиц не трогается, schema-gate (FR-INST-2) ничего не
 * замечает. Запрет NFR-10 «приложение не мигрирует схему» сюда не относится —
 * иначе партиции пришлось бы досоздавать миграцией каждый год вручную.
 */
@Repository
public class AuditPartitionRepository {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcClient jdbc;

    public AuditPartitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Имя партиции за месяц. Выведено из даты, пользовательский ввод сюда не попадает. */
    public static String partitionName(YearMonth month) {
        return "audit_log_" + month.format(SUFFIX);
    }

    public boolean exists(YearMonth month) {
        Long count = jdbc.sql("select count(*) from pg_class where relname = :name")
                .param("name", partitionName(month))
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    /**
     * Создаёт партицию за месяц. Имя и границы подставляются в DDL строкой —
     * параметризовать имя объекта SQL не позволяет; значения детерминированы
     * и берутся из {@link YearMonth}, а не из запроса.
     */
    public void create(YearMonth month) {
        String name = partitionName(month);
        String from = month.atDay(1) + " 00:00:00+00";
        String to = month.plusMonths(1).atDay(1) + " 00:00:00+00";
        jdbc.sql("create table if not exists " + name
                + " partition of audit_log for values from ('" + from + "') to ('" + to + "')")
                .update();
    }

    /**
     * Партиции, прикреплённые к {@code audit_log} и относящиеся к месяцам
     * строго раньше {@code before}. Аварийный приёмник {@code audit_log_default}
     * не возвращается никогда: отцепить его нельзя, у него нет границ.
     */
    public java.util.List<YearMonth> attachedPartitionsBefore(YearMonth before) {
        var names = jdbc.sql("""
                        select c.relname
                        from pg_inherits i
                        join pg_class c on c.oid = i.inhrelid
                        join pg_class p on p.oid = i.inhparent
                        where p.relname = 'audit_log' and c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'
                        order by c.relname
                        """)
                .query(String.class)
                .list();

        java.util.List<YearMonth> result = new java.util.ArrayList<>();
        for (String name : names) {
            YearMonth month = YearMonth.parse(name.substring("audit_log_".length()), SUFFIX);
            if (month.isBefore(before)) {
                result.add(month);
            }
        }
        return result;
    }

    /**
     * Отцепляет партицию и переименовывает её в {@code audit_log_archived_YYYY_MM}.
     *
     * Данные НЕ удаляются: срок хранения кончился для оперативного журнала, а не
     * для самих записей — что с ними делать дальше, решает эксплуатация
     * (выгрузка в холодное хранилище или удаление вручную). Автоматическое
     * удаление аудита — необратимая операция, её нельзя прятать в ночной воркер.
     */
    public String detachAndArchive(YearMonth month) {
        String name = partitionName(month);
        String archived = "audit_log_archived_" + month.format(SUFFIX);

        jdbc.sql("alter table audit_log detach partition " + name).update();
        jdbc.sql("alter table " + name + " rename to " + archived).update();
        return archived;
    }

    /** Строки в аварийном приёмнике: их наличие блокирует создание партиции за тот же месяц. */
    public long countDefaultRows() {
        Long count = jdbc.sql("select count(*) from audit_log_default").query(Long.class).single();
        return count != null ? count : 0L;
    }
}
