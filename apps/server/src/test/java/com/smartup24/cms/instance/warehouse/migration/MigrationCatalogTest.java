package com.smartup24.cms.instance.warehouse.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The migration catalog: an empty location and a file named against the rule are configuration errors. */
class MigrationCatalogTest {

    @Test
    void readsTheCatalogAndItsLatestVersion() {
        MigrationCatalog catalog = MigrationCatalog.onClasspath("db/migration");

        assertThat(catalog.location()).isEqualTo("db/migration");
        assertThat(catalog.fileNames()).contains("V001__init_schema.sql").isSorted();
        assertThat(Integer.parseInt(catalog.latestVersion())).isGreaterThanOrEqualTo(132);
    }

    @Test
    void emptyLocationAndBadNamesAreRefused() {
        assertThatThrownBy(() -> MigrationCatalog.onClasspath("db/no-such-catalog"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MigrationCatalog.versionOf("init_schema.sql"))
                .isInstanceOf(IllegalStateException.class);
    }
}
