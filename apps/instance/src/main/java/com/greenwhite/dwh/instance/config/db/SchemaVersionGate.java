package com.greenwhite.dwh.instance.config.db;

import com.greenwhite.dwh.common.db.AbstractFlywaySchemaGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Schema-gate (FR-INST-2, ADR-0007 разд. 2.3): приложение ОТКАЗЫВАЕТСЯ стартовать,
 * если схема БД не совпадает с миграциями на classpath.
 */
@Component
@Profile("!migrate")
public class SchemaVersionGate extends AbstractFlywaySchemaGate {

    public SchemaVersionGate(DataSource dataSource,
                             @Value("${dwh.schema-gate.enabled:true}") boolean enabled) {
        super(dataSource, enabled, "classpath:db/migration", "Instance (md, kauth, tsk, ms, mf, audit, kwh)");
    }
}
