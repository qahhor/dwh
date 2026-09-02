-- ============================================================================
-- V017: Клиентские кастомные модули и расширения (Plugin SDK) + SSO OAuth2
-- ============================================================================

-- 1. Таблица кастомных модулей инстанса
create table if not exists md_custom_modules (
    id bigserial primary key,
    code text not null unique,
    name text not null,
    version text not null default '1.0.0',
    description text,
    category text not null default 'custom',
    icon text not null default 'extension',
    route_path text not null,
    entrypoint_url text not null,
    permissions_json jsonb not null default '[]'::jsonb,
    settings_schema_json jsonb not null default '{}'::jsonb,
    status text not null default 'DRAFT', -- DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, DISABLED
    rejection_reason text,
    cp_ticket_id text,
    approved_at timestamptz,
    created_by bigint references md_users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_md_custom_modules_status on md_custom_modules(status);

-- 2. Таблица настроек SSO OAuth2 провайдеров
create table if not exists md_sso_providers (
    id bigserial primary key,
    provider_id text not null unique, -- google, azure, keycloak, corporate_sso
    name text not null,
    icon text not null default 'lock',
    client_id text not null,
    client_secret text,
    authorization_url text not null,
    token_url text not null,
    userinfo_url text not null,
    scopes text not null default 'openid profile email',
    is_enabled boolean not null default true,
    auto_provision boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Seed default SSO placeholder configurations for demo / testing
insert into md_sso_providers (provider_id, name, icon, client_id, client_secret, authorization_url, token_url, userinfo_url, scopes, is_enabled, auto_provision)
values
    ('google', 'Google Workspace', 'account_circle', 'dwh-google-client-id.apps.googleusercontent.com', 'secret', 'https://accounts.google.com/o/oauth2/v2/auth', 'https://oauth2.googleapis.com/token', 'https://openidconnect.googleapis.com/v1/userinfo', 'openid profile email', true, true),
    ('azure', 'Microsoft Azure AD', 'corporate_fare', 'dwh-azure-client-id', 'secret', 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize', 'https://login.microsoftonline.com/common/oauth2/v2.0/token', 'https://graph.microsoft.com/oidc/userinfo', 'openid profile email', true, true),
    ('keycloak', 'Corporate Keycloak SSO', 'security', 'dwh-keycloak-client', 'secret', 'https://sso.company.local/auth/realms/master/protocol/openid-connect/auth', 'https://sso.company.local/auth/realms/master/protocol/openid-connect/token', 'https://sso.company.local/auth/realms/master/protocol/openid-connect/userinfo', 'openid profile email', true, true)
on conflict (provider_id) do nothing;
