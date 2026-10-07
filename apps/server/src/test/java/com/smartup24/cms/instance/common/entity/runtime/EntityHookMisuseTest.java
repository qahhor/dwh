package com.smartup24.cms.instance.common.entity.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0032, 6.5: a hook's misuse of the save's values is reported as the module's defect, naming the entity, the hook
 * and the field; an exception the hook throws itself passes unchanged.
 */
class EntityHookMisuseTest {

    private static final Object HOOK = new Object() {};

    @Test
    void aMisuseOfTheValuesNamesTheEntityTheHookAndTheField() {
        EntityValues values = EntityValues.writable(
                EntityRuntimeFixture.DEFINITION, new HashMap<>(Map.of("title", "a")), (entity, key, value) -> true);
        assertThatThrownBy(() ->
                        EntityHookMisuse.guarding(EntityRuntimeFixture.CODE, HOOK, () -> values.set("createdBy", 1L)))
                .isInstanceOf(EntityHookMisuse.class)
                .hasMessageContaining(EntityRuntimeFixture.CODE)
                .hasMessageContaining(HOOK.getClass().getName())
                .hasMessageContaining("createdBy is no written field")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityHookMisuse.guarding(
                        EntityRuntimeFixture.CODE, HOOK, () -> values.set("title", "refused")))
                .isInstanceOf(EntityHookMisuse.class)
                .hasMessageContaining("the value of title is refused by its type");
        EntityValues read = EntityValues.readOnly(EntityRuntimeFixture.DEFINITION, Map.of());
        assertThatThrownBy(
                        () -> EntityHookMisuse.guarding(EntityRuntimeFixture.CODE, HOOK, () -> read.set("title", "a")))
                .isInstanceOf(EntityHookMisuse.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anExceptionOfTheHookItselfPassesUnchanged() {
        IllegalArgumentException own = new IllegalArgumentException("the hook's own problem");
        assertThatThrownBy(() -> EntityHookMisuse.guarding(EntityRuntimeFixture.CODE, HOOK, () -> {
                    throw own;
                }))
                .isSameAs(own);
        assertThat(own).isNotInstanceOf(EntityHookMisuse.class);
    }
}
