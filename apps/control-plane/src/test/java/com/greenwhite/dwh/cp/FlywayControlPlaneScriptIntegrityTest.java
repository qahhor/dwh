package com.greenwhite.dwh.cp;

import com.greenwhite.dwh.cp.support.CpPostgresIntegrationSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayControlPlaneScriptIntegrityTest extends CpPostgresIntegrationSupport {

    @Test
    void appliesEveryControlPlaneMigrationToAnEmptyPostgresSchema() {
        cleanAndMigrateTo("6");

        assertThat(jdbc().sql("""
                        select version
                        from flyway_schema_history
                        where success
                        order by installed_rank desc
                        limit 1
                        """)
                .query(String.class)
                .single()).isEqualTo("006");
        assertThat(tableExists("cp_releases")).isTrue();
        assertThat(tableExists("cp_audit_events")).isTrue();
    }
}
