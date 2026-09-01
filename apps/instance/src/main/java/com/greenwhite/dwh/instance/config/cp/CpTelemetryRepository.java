package com.greenwhite.dwh.instance.config.cp;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    public CpTelemetrySnapshot snapshot() {
        return jdbc.sql("""
                        select
                            (select count(*) from md_users where state = 'A') as active_users,
                            (select count(*) from ms_notification_outbox
                                where status = 'PENDING') as outbox_pending,
                            (select count(*) from ms_notification_outbox
                                where status = 'DEAD_LETTER') as outbox_dead_letter,
                            (select coalesce(sum(size_bytes), 0) from mf_files) as storage_used_bytes,
                            coalesce((select storage_quota_bytes from md_instance_info limit 1),
                                53687091200) as storage_quota_bytes
                        """)
                .query((rs, rowNum) -> new CpTelemetrySnapshot(
                        rs.getLong("active_users"),
                        rs.getLong("outbox_pending"),
                        rs.getLong("outbox_dead_letter"),
                        rs.getLong("storage_used_bytes"),
                        rs.getLong("storage_quota_bytes")))
                .single();
    }
}
