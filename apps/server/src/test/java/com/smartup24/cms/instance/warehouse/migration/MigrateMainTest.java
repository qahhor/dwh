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
        assertThatThrownBy(() -> MigrateMain.run(
                        Map.of("DWH_MIGRATE_SCOPE", "dwh", "DWH_DATA_DB_URL", "jdbc:postgresql://127.0.0.1:1/none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("DB_URL")
                .hasMessageNotContaining("WAREHOUSE_URL");
    }

    @Test
    @DisplayName("пустой WAREHOUSE_URL считается незаданным")
    void blankWarehouseUrlIsMissing() {
        assertThatThrownBy(() -> MigrateMain.run(Map.of("SMC_MIGRATE_SCOPE", "warehouse", "WAREHOUSE_URL", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith("WAREHOUSE_URL");
    }

    @Test
    @DisplayName("заданный WAREHOUSE_URL проходит проверку и ведёт к подключению")
    void givenWarehouseUrlIsUsed() {
        // An unreachable address: the run gets past the missing-variable check and fails on the connection.
        assertThatThrownBy(() -> MigrateMain.run(Map.of(
                        "SMC_MIGRATE_SCOPE", "warehouse", "WAREHOUSE_URL", "jdbc:postgresql://127.0.0.1:1/none")))
                .hasMessageNotContaining("Не задана переменная окружения");
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
