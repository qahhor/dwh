package com.greenwhite.dwh.cp.config.db;

import com.greenwhite.dwh.common.db.AbstractFlywaySchemaGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Schema-gate control plane: приложение отказывается стартовать, если схема БД
 * не соответствует миграциям на classpath.
 */
@Component
@Profile("!migrate")
public class CpSchemaVersionGate extends AbstractFlywaySchemaGate {

    public CpSchemaVersionGate(DataSource dataSource,
                               @Value("${dwh.schema-gate.enabled:true}") boolean enabled) {
        super(dataSource, enabled, "classpath:db/migration", "Control Plane (cp_clients, cp_instances, cp_licenses)");
    }
}
