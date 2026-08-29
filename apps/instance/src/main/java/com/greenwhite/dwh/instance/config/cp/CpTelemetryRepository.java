package com.greenwhite.dwh.instance.config.cp;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Показатели экземпляра для heartbeat (FR-INST-3).
 *
 * Читает счётчики нескольких модулей напрямую. Это осознанное исключение из
 * правила «модуль ходит в чужие данные только через фасад» (ADR-0006 разд. 2.4):
 * телеметрия — внешний наблюдатель, а не бизнес-логика; заводить порт в каждом
 * модуле ради одного count(*) дороже, чем сам показатель. Запросы только на
 * чтение и только агрегаты — ни одной строки данных наружу не уходит.
 */
@Repository
public class CpTelemetryRepository {

    private final JdbcClient jdbc;

    public CpTelemetryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Версия схемы: то же значение, что проверяет schema-gate при старте. */
    @Transactional(readOnly = true)
    public String schemaVersion() {
        return jdbc.sql("""
                        select version from flyway_schema_history
                        where success order by installed_rank desc limit 1
                        """)
                .query(String.class)
                .optional()
                .orElse("unknown");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> metrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("users", count("select count(*) from md_users where state = 'A'"));
        metrics.put("sessions", count("select count(*) from kauth_sessions where closed_at is null"));
        metrics.put("outboxPending",
                count("select count(*) from ms_notification_outbox where status = 'PENDING'"));
        metrics.put("outboxDeadLetter",
                count("select count(*) from ms_notification_outbox where status = 'DEAD_LETTER'"));
        return metrics;
    }

    private long count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value != null ? value : 0L;
    }
}
