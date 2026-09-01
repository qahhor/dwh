package com.greenwhite.dwh.cp.instance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CpInstanceRepository {

    private final JdbcClient jdbc;

    public CpInstanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findClientIdByCode(String clientCode) {
        return jdbc.sql("select id from cp_clients where code = :clientCode")
                .param("clientCode", clientCode)
                .query(Long.class)
                .optional();
    }

    public long create(long clientId, CpInstanceRegistrationService.RegistrationCommand command) {
        return jdbc.sql("""
                        insert into cp_instances(
                            client_id,
                            environment,
                            url,
                            deployment_mode,
                            jurisdiction,
                            cloud_provider,
                            storage_provider,
                            edge_provider,
                            support_tier,
                            lifecycle_status)
                        values (
                            :clientId,
                            :environment,
                            :url,
                            :deploymentMode,
                            :jurisdiction,
                            :cloudProvider,
                            :storageProvider,
                            :edgeProvider,
                            :supportTier,
                            'ENROLLING')
                        returning id
                        """)
                .param("clientId", clientId)
                .param("environment", command.environment())
                .param("url", command.url().toString())
                .param("deploymentMode", command.deploymentMode().name())
                .param("jurisdiction", command.jurisdiction())
                .param("cloudProvider", command.cloudProvider())
                .param("storageProvider", command.storageProvider())
                .param("edgeProvider", command.edgeProvider())
                .param("supportTier", command.supportTier())
                .query(Long.class)
                .single();
    }
}
