# D-02 — Fail-closed deploy, rollback and full restore

**Priority:** P0 · **Effort:** M · **Owner:** TBD

## Problem and evidence

`deploy.sh:23-46` ignores backup failure and never proves every service healthy; `deploy.ps1:23-38` prints success after one `ps`; `backup.sh:7-24` covers only instance DB. Automatic rollback and full-system restore evidence were not found.

## Minimal change

1. Preflight validates env schema, disk, registry digest and current/previous release.
2. Backup receives the same compose/env and captures instance DB, CP DB and file/object manifest; failure stops deploy.
3. Deploy exact digest; wait for explicit expected services, migrations and HTTP smoke.
4. On failure redeploy previous digest and rerun smoke; emit nonzero regardless.
5. Scheduled isolated restore drill verifies checksums, schema and representative counts/files; record measured RPO/RTO.

## Verification

Failure injection: bad image, unhealthy app, missing UI, failed migration and corrupt backup. Each case must stop promotion; app cases rollback; corrupt backup cannot be marked verified.
