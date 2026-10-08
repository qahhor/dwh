set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: the only plain values are the placeholder client secrets ('secret') that V017 seeded for the disabled demo
--         SSO providers; no installation exists before the final release, so no real secret is lost.
-- approved_by: product owner (no client installations before the final release, 2026-10-01)
-- ADR-0029: secrets are stored only encrypted (v1: prefix) and the server refuses a plain value at start. The seeded
-- placeholders are cleared; an administrator sets a real client secret through the application, which encrypts it.
update md_sso_providers
set client_secret = null,
    updated_at = now()
where client_secret is not null
  and client_secret not like 'v1:%';
