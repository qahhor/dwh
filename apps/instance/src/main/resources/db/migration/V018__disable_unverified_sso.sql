-- SSO exchange remains disabled until a verified OIDC authorization-code + PKCE
-- implementation is available. Keep provider metadata for a future expand migration,
-- but never advertise seeded demo providers in a release database.
update md_sso_providers
set is_enabled = false,
    auto_provision = false,
    updated_at = now()
where is_enabled or auto_provision;
