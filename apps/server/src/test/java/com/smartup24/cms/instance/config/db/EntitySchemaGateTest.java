package com.smartup24.cms.instance.config.db;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 6.4: the schema gate runs at every start and refuses one whose declarations differ from the tables,
 * unless {@code smc.entities.schema-gate-enabled=false}, which only {@code cms migration diff} passes (item 6.1) and
 * only the dev and test profiles accept ({@code ProductionStartGuard}).
 */
class EntitySchemaGateTest extends EmbeddedPostgresTest {

    /** A declaration whose table no migration creates. */
    private static final EntityDefinition DRIFTED = Entity.define("test.drifted", "notes")
            .table("test_schema_gate_missing", "g")
            .scope(EntityScope.all())
            .field(text("title", "notes.col.title").column("title").list(sortable()))
            .section("main", "entity.section.main", "title")
            .defaultSort("title", Entity.Sort.ASC)
            .build();

    @Autowired
    private ObjectProvider<EntitySchemaGate> gate;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theGateIsOnByDefault() {
        assertThat(gate.getIfAvailable()).isNotNull();
    }

    @Test
    void aDeclarationWithoutItsTableStopsTheStart() {
        EntitySchemaGate drifted = new EntitySchemaGate(new EntityRegistry(List.of(DRIFTED)), jdbc, true);

        assertThatThrownBy(drifted::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADR-0033, 7")
                .hasMessageContaining("test_schema_gate_missing");
    }

    @Test
    void switchedOffTheGateComparesNothing() {
        EntitySchemaGate off = new EntitySchemaGate(new EntityRegistry(List.of(DRIFTED)), jdbc, false);

        assertThatCode(off::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}
