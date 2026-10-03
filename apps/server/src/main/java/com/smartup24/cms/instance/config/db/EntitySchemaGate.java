package com.smartup24.cms.instance.config.db;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntitySchemaCheck;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The application refuses to start while an entity declaration differs from its tables (ADR-0033, 7; plan 10/10, item
 * 6.4): a missing table or column, a column of a type the field does not accept, a missing revision or archive column, a
 * column that must be filled and nobody writes. Such a difference would fail the runtime's statements later, on a
 * request; the start names every one of them instead. Not in the migrate profile: it runs before the migrations of the
 * modules. {@code smc.entities.schema-gate-enabled=false} switches it off only for {@code cms migration diff}
 * (plan 10/10, item 6.1), which starts the declarations on a schema that lacks them in order to write their DDL; the
 * switch is accepted only with the {@code dev} or {@code test} profile ({@code config.env.ProductionStartGuard}), and a
 * start with it off says so in the log.
 */
@Component
@Profile("!migrate")
public class EntitySchemaGate implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EntitySchemaGate.class);

    private final EntityRegistry entities;
    private final JdbcClient jdbc;
    private final boolean enabled;

    public EntitySchemaGate(
            EntityRegistry entities,
            JdbcClient jdbc,
            @Value("${smc.entities.schema-gate-enabled:true}") boolean enabled) {
        this.entities = entities;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            log.warn("entity_schema_gate_disabled: the entity declarations are not compared with the database schema"
                    + " (smc.entities.schema-gate-enabled=false, only for cms migration diff)");
            return;
        }
        List<String> problems = EntitySchemaCheck.of(jdbc).problems(entities.all());
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The entity declarations differ from the database schema (ADR-0033, 7):\n  - "
                            + String.join("\n  - ", problems));
        }
        log.info("entity_schema_checked entities={}", entities.all().size());
    }
}
