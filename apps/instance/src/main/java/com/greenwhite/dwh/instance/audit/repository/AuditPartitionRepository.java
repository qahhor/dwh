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

    /** Строки в аварийном приёмнике: их наличие блокирует создание партиции за тот же месяц. */
    public long countDefaultRows() {
        Long count = jdbc.sql("select count(*) from audit_log_default").query(Long.class).single();
        return count != null ? count : 0L;
    }
}
