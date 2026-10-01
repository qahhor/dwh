package com.smartup24.cms.instance.fnd.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The class name of the migrate step before plan 10/10, item 4.2 still starts it: a Compose file of an earlier
 * release names it (ADR-0030). The launcher looks up {@code public static void main(String[])} on the named class,
 * inherited methods included.
 */
@SuppressWarnings("removal")
class MigrateMainAliasTest {

    @Test
    @DisplayName("4.2: the earlier class name of the migrate step runs the warehouse step's main")
    void earlierClassNameRunsTheSameMain() throws NoSuchMethodException {
        Method main = MigrateMain.class.getMethod("main", String[].class);

        assertThat(main.getDeclaringClass())
                .isEqualTo(com.smartup24.cms.instance.warehouse.migration.MigrateMain.class);
        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(main.getReturnType()).isEqualTo(void.class);
        assertThat(new MigrateMain()).isInstanceOf(com.smartup24.cms.instance.warehouse.migration.MigrateMain.class);
    }
}
