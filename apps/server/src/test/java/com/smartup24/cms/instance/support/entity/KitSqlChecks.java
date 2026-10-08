package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;
import org.junit.jupiter.api.DynamicTest;

/**
 * The SQL of the declaration stays in its module (ADR-0026; ADR-0032, 6.11 and 12): its tables, expressions, computed
 * fields and custom scope — as the scope answers the kit's users — read only the relations of the module, by the prefix
 * of the entity's table, and other modules' published views; a custom scope may read the data-scope relations of
 * {@code md} (ADR-0013). {@link EntitySqlBoundaries} holds the rule.
 */
final class KitSqlChecks {

    private final KitWorld world;

    KitSqlChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> boundaries() {
        return List.of(dynamicTest(
                "the declaration's SQL reads its module's tables and published views only", this::readsItsOwn));
    }

    private void readsItsOwn() {
        List<Long> viewers = List.of(world.owner.id(), world.outsider.id(), world.viewer.id(), world.stranger.id());
        List<EntitySqlBoundaries.Fragment> fragments = EntitySqlBoundaries.fragments(world.entity, viewers);
        assertThat(EntitySqlBoundaries.violations(
                        world.entity,
                        fragments,
                        EntitySqlBoundaries.relations(world.jdbc),
                        EntitySqlBoundaries.tablePrefixOf(world.entity)))
                .as("read another module's data through its published view <owner>_pub_* (ADR-0026)")
                .isEmpty();
    }
}
