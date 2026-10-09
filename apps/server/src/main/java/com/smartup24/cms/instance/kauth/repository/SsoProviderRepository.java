package com.smartup24.cms.instance.kauth.repository;

import com.smartup24.cms.instance.common.security.StoredSecretColumn;
import com.smartup24.cms.instance.common.security.StoredSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SsoProviderRepository implements StoredSecretColumn {

    /** The client secret of a provider is encrypted at rest (ADR-0029); this is its encryption context. */
    public static final String SECRET_COLUMN = "md_sso_providers.client_secret";

    private final JdbcClient jdbcClient;
    private final StoredSecrets secrets;

    public SsoProviderRepository(JdbcClient jdbcClient, StoredSecrets secrets) {
        this.jdbcClient = jdbcClient;
        this.secrets = secrets;
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
            Instant updatedAt) {

        SsoProviderRecord withClientSecret(String secret) {
            return new SsoProviderRecord(
                    id,
                    providerId,
                    name,
                    icon,
                    clientId,
                    secret,
                    authorizationUrl,
                    tokenUrl,
                    userinfoUrl,
                    scopes,
                    isEnabled,
                    autoProvision,
                    createdAt,
                    updatedAt);
        }
    }

    public List<SsoProviderRecord> findEnabledProviders() {
        return jdbcClient.sql("""
                select id, provider_id, name, icon, client_id, client_secret,
                       authorization_url, token_url, userinfo_url, scopes,
                       is_enabled, auto_provision, created_at, updated_at
                from md_sso_providers
                where is_enabled = true
                order by id asc
                """).query(SsoProviderRecord.class).list().stream()
                .map(this::opened)
                .toList();
    }

    public Optional<SsoProviderRecord> findByProviderId(String providerId) {
        return jdbcClient
                .sql("""
                select id, provider_id, name, icon, client_id, client_secret,
                       authorization_url, token_url, userinfo_url, scopes,
                       is_enabled, auto_provision, created_at, updated_at
                from md_sso_providers
                where provider_id = :providerId and is_enabled = true
                """)
                .param("providerId", providerId)
                .query(SsoProviderRecord.class)
                .optional()
                .map(this::opened);
    }

    @Override
    public String secretColumn() {
        return SECRET_COLUMN;
    }

    @Override
    public Optional<String> anySealedSecret() {
        return jdbcClient
                .sql("select client_secret from md_sso_providers where client_secret like 'v1:%' limit 1")
                .query(String.class)
                .optional();
    }

    @Override
    public int countPlainSecrets() {
        return jdbcClient
                .sql("select count(*) from md_sso_providers where client_secret not like 'v1:%'")
                .query(Integer.class)
                .single();
    }

    private SsoProviderRecord opened(SsoProviderRecord row) {
        return row.withClientSecret(secrets.open(row.clientSecret(), SECRET_COLUMN));
    }
}
