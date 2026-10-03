package com.smartup24.cms.instance.config.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Plan 10/10, item 6.4: the schema gate runs at every start unless {@code smc.entities.schema-gate-enabled=false},
 * which only {@code cms migration diff} passes (item 6.1).
 */
class EntitySchemaGateTest extends EmbeddedPostgresTest {

    @Autowired
    private ObjectProvider<EntitySchemaGate> gate;

    @Test
    void theGateIsOnByDefault() {
        assertThat(gate.getIfAvailable()).isNotNull();
    }
}
