package com.smartup24.cms.instance.warehouse.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The migration scope {@code SMC_MIGRATE_SCOPE} and the names it reads (ADR-0027): checks without a database. */
class MigrateMainTest {

    @Test
    @DisplayName("неизвестная область отвергается с именем переменной в сообщении")
    void rejectsUnknownScope() {
        assertThatThrownBy(() -> MigrateMain.run(Map.of("SMC_MIGRATE_SCOPE", "oltp")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_MIGRATE_SCOPE");
    }

    @Test
    @DisplayName("область warehouse пропускает OLTP и идёт сразу к pg-dwh")
    void scopeWarehouseSkipsOltp() {
        assertThatThrownBy(() -> MigrateMain.run(Map.of("SMC_MIGRATE_SCOPE", "warehouse")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAREHOUSE_URL");
    }

    @Test
    @DisplayName("старая область dwh отвергается")
    void oldScopeIsRejected() {
        assertThatThrownBy(() -> MigrateMain.run(Map.of("SMC_MIGRATE_SCOPE", "dwh")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMC_MIGRATE_SCOPE");
    }

    @Test
    @DisplayName("старые имена DWH_MIGRATE_SCOPE и DWH_DATA_DB_URL не читаются")
    void oldNamesAreIgnored() {
        assertThatThrownBy(() -> MigrateMain.run(Map.of(
                        "DWH_MIGRATE_SCOPE", "dwh", "DWH_DATA_DB_URL", "jdbc:postgresql://127.0.0.1:1/none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("DB_URL")
                .hasMessageNotContaining("WAREHOUSE_URL");
    }

    @Test
    @DisplayName("по умолчанию миграция начинается с OLTP")
    void defaultScopeStartsWithOltp() {
        assertThatThrownBy(() -> MigrateMain.run(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("DB_URL")
                .hasMessageNotContaining("WAREHOUSE_URL");
    }
}
