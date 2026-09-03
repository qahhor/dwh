# SmartupCMS

SmartupCMS is a self-hosted content and operations platform for one
organization and many users. It combines user and role administration, tasks,
notifications, audited administrative actions, file storage, search, and
dashboard-oriented workflows in one modular product.

The project is pre-1.0. Use an immutable release tag and complete the
[production launch checklist](docs/ops/production-launch-checklist.md) before a
production rollout.

## Product model

- One installation serves one organization; isolation between organizations is
  provided by separate installations and databases.
- The complete product is available in this repository under Apache-2.0.
- Self-hosted and Smartup-managed deployments use the same product. Commercial
  services may cover hosting, operations, support, and SLA.
- No telemetry, licensing callback, remote enrollment, or phone-home connection
  is enabled by default.
- Local disk and S3-compatible object storage are supported. Smartup-managed
  infrastructure targets Cloudflare R2; self-hosters may use AWS S3, R2, MinIO,
  Garage, or another compatible provider.

## Architecture

SmartupCMS is a modular monolith with separately deployable runtime containers:

| Component | Implementation | Responsibility |
|---|---|---|
| `web` | Angular 22 | Browser UI and the only public application origin |
| `server` | Java 25, Spring Boot 4.1 | APIs, authorization, business modules, audit |
| `postgres` | PostgreSQL 18 | Transactional data and Flyway schema history |
| `typesense` | Typesense 27.1 | Full-text search |
| `backup` | PostgreSQL client, `age`, AWS CLI | Encrypted database backups and local status |

The web container proxies API traffic to the server. PostgreSQL, Typesense, and
management endpoints are not published by the production Compose topology.
Database migrations are a separate, fail-closed step. See the
[architecture overview](docs/ops/architecture-overview.md).

## Quick start

Prerequisites: Docker Engine 26+ with Docker Compose v2.

```bash
cp .env.example .env
docker compose run --rm migrate
docker compose up -d --wait
```

Open <http://localhost:4200>. The development defaults create the initial
administrator `admin` with the password in `ADMIN_PASSWORD`. Change it on first
sign-in. The defaults in `.env.example` are for local development only.

Stop the stack without deleting data:

```bash
docker compose down
```

The optional encrypted backup service requires secret files and an age
recipient. Follow the [deployment guide](docs/ops/deployment-guide.md) instead of
enabling it with development credentials.

## Development

Prerequisites: JDK 25, Maven 3.9+, Node.js from [`.node-version`](.node-version),
npm, and Docker for integration tests.

Backend verification:

```bash
mvn -B verify
```

Web verification:

```bash
cd apps/web
npm ci
npm test
npm run typecheck
npm run build
```

End-to-end verification runs against the Compose deployment started in the
quick start:

```bash
cd e2e
npm ci
npx playwright install chromium
npm test
```

Read [onboarding](docs/onboarding.md) for the code map and
[CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change.

Use the [documentation index](docs/README.md) to navigate authority levels and
the [canonical technical specification](docs/technical-specification.md) for
normative product and release requirements.

## Operations and security

- [Production deployment](docs/ops/deployment-guide.md)
- [Operations runbook](docs/ops/operations-runbook.md)
- [Backup, restore, and maintenance](docs/ops/maintenance-guide.md)
- [Rollback procedure](docs/ops/rollback.md)
- [Threat model and personal-data inventory](docs/security/threat-model.md)
- [Security policy and private reporting](SECURITY.md)
- [Support policy](SUPPORT.md)

Do not report suspected vulnerabilities in public issues. Never commit `.env`,
secret files, database dumps, customer data, or decrypted backups.

## Verifying a release

A stable SemVer tag publishes five `linux/amd64` and `linux/arm64` images:
`server`, `web`, `backup`, `postgres`, and `typesense`. The GitHub Release also
contains a versioned Compose bundle, SHA-256 checksums, SPDX and CycloneDX SBOMs,
and provenance bundles. Images are signed keylessly with GitHub OIDC and Cosign;
no long-lived signing secret is used.

After downloading the assets, verify their checksums:

```bash
sha256sum -c SHA256SUMS
```

Use the digest from `IMAGES.txt` in the Compose bundle, then verify both the
Cosign signature and GitHub provenance. Replace the uppercase placeholders with
the repository coordinates and exact release tag:

```bash
cosign verify \
  --certificate-identity-regexp '^https://github.com/OWNER/REPOSITORY/.github/workflows/release.yml@refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  'ghcr.io/OWNER/smartupcms/server@sha256:DIGEST'
```

```bash
gh attestation verify \
  'oci://ghcr.io/OWNER/smartupcms/server@sha256:DIGEST' \
  --repo OWNER/REPOSITORY
```

Repeat verification for every image used by the deployment. A mutable tag or an
image reference without a matching digest, signature, and provenance is not a
release input.

## Community and license

Contributions require a Developer Certificate of Origin sign-off (`git commit
-s`). Project decisions and conduct are described in
[GOVERNANCE.md](GOVERNANCE.md) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Copyright 2026 Smartup. Licensed under the [Apache License 2.0](LICENSE). See
[NOTICE](NOTICE) and [RELICENSE.md](RELICENSE.md) for attribution and historical
relicensing information.
