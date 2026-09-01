package com.greenwhite.dwh.cp.db;

import com.greenwhite.dwh.cp.support.CpPostgresIntegrationSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CpFleetFoundationMigrationTest extends CpPostgresIntegrationSupport {

    @Test
    void createsFleetFoundationTablesAndBackfillsLegacyCredential() {
        cleanAndMigrateTo("5");
        long clientId = jdbc().sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values ('migration_client', 'Migration Client', 'S') returning id
                        """)
                .query(Long.class)
                .single();
        long instanceId = jdbc().sql("""
                        insert into cp_instances(client_id, environment, url, heartbeat_token_hash)
                        values (:clientId, 'production', 'https://migration.invalid', 'legacy-hash') returning id
                        """)
                .param("clientId", clientId)
                .query(Long.class)
                .single();

        migrateLatest();

        assertThat(tableExists("cp_releases")).isTrue();
        assertThat(tableExists("cp_release_components")).isTrue();
        assertThat(tableExists("cp_instance_enrollment_tokens")).isTrue();
        assertThat(tableExists("cp_instance_credentials")).isTrue();
        assertThat(tableExists("cp_instance_targets")).isTrue();
        assertThat(tableExists("cp_deployments")).isTrue();
        assertThat(tableExists("cp_deployment_events")).isTrue();
        assertThat(tableExists("cp_instance_backup_reports")).isTrue();
        assertThat(tableExists("cp_heartbeat_daily")).isTrue();
        assertThat(tableExists("cp_audit_events")).isTrue();
        assertThat(jdbc().sql("""
                        select count(*)
                        from cp_instance_credentials
                        where instance_id = :instanceId and credential_hash = 'legacy-hash'
                        """)
                .param("instanceId", instanceId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }
}
