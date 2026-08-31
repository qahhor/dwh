package com.greenwhite.dwh.instance.kauth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SsoProviderRepository {

    private final JdbcClient jdbcClient;

    public SsoProviderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record SsoProviderRecord(
            Long id,
            String providerId,
            String name,
            String icon,
            String clientId,
            String clientSecret,
            String authorizationUrl,
            String tokenUrl,
            String userinfoUrl,
            String scopes,
            boolean isEnabled,
            boolean autoProvision,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public List<SsoProviderRecord> findEnabledProviders() {
        return jdbcClient.sql("""
                select id, provider_id, name, icon, client_id, client_secret,
                       authorization_url, token_url, userinfo_url, scopes,
                       is_enabled, auto_provision, created_at, updated_at
                from md_sso_providers
                where is_enabled = true
                order by id asc
                """)
                .query(SsoProviderRecord.class)
                .list();
    }

    public Optional<SsoProviderRecord> findByProviderId(String providerId) {
        return jdbcClient.sql("""
                select id, provider_id, name, icon, client_id, client_secret,
                       authorization_url, token_url, userinfo_url, scopes,
                       is_enabled, auto_provision, created_at, updated_at
                from md_sso_providers
                where provider_id = :providerId and is_enabled = true
                """)
                .param("providerId", providerId)
                .query(SsoProviderRecord.class)
                .optional();
    }
}
