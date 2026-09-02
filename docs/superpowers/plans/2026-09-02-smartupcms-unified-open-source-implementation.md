# SmartupCMS Unified Open Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dual instance/Control Plane architecture with one secure, single-organization SmartupCMS self-hosted product and publish a reproducible Apache-2.0 release pipeline.

**Architecture:** Remove Control Plane as a runtime and trust boundary, migrate the remaining database to local organization semantics, and expose local system/announcement capabilities from the modular monolith. Keep web, server, PostgreSQL, Typesense, and backup as independently healthy containers behind one origin; support local and S3-compatible storage without mandatory Internet egress.

**Tech Stack:** Java 25, Spring Boot 4.1, PostgreSQL 18/Flyway, Angular 20/Vitest, Playwright, Docker Compose, AWS SDK v2 S3, GitHub Actions, GHCR, Cosign, CycloneDX.

**Spec:** `docs/superpowers/specs/2026-09-02-smartupcms-unified-open-source-design.md`

## Global Constraints

- One installation serves one organization; do not introduce `company_id`, RLS, or multi-tenancy.
- Product name is `SmartupCMS`; retain Java namespace `com.greenwhite.dwh` for this release.
- Standard configuration performs no Internet egress; integrations activate only through explicit configuration.
- No Docker socket, host shell, restore, or self-update capability is exposed to the application.
- Preserve V001-V018 checksums; all schema changes enter through forward-only V019.
- Preserve announcement reads and custom-module metadata; never execute existing custom module URLs.
- Use Apache-2.0 with `Copyright 2026 Smartup`; contributions require DCO sign-off.
- Each behavior change follows red-green-refactor and each task ends in a focused commit.
- Keep repository visibility private until the owner gives a separate publication command.

---

### Task 1: Executable unified-boundary contract

**Files:**
- Create: `scripts/architecture/test-unified-boundaries.ps1`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: root `pom.xml`, root `docker-compose.yml`, tracked application directories.
- Produces: a cross-platform PowerShell gate that exits non-zero while Control Plane remains in the active build/runtime.

- [ ] **Step 1: Write the failing architecture gate**

Create a script that loads `pom.xml` as XML, executes `docker compose config --services`, and checks active directories. It must require Maven modules `apps/instance` and forbid `apps/control-plane`; require Compose services `db`, `migrate`, `app`, `web`, `typesense` and forbid `db-cp`, `migrate-cp`, `control-plane`, `web-cp`; and fail when `apps/web-cp` exists.

```powershell
$forbiddenServices = @('db-cp', 'migrate-cp', 'control-plane', 'web-cp')
$services = @(docker compose config --services)
$found = @($forbiddenServices | Where-Object { $services -contains $_ })
if ($found.Count -gt 0) { throw "Control Plane services remain: $($found -join ', ')" }
```

- [ ] **Step 2: Verify RED**

Run: `pwsh -NoProfile -File scripts/architecture/test-unified-boundaries.ps1`

Expected: non-zero exit naming current CP Maven/Compose/UI artifacts.

- [ ] **Step 3: Add the gate to CI**

Add a `unified architecture boundary` step to the existing `release-config` job before production Compose validation.

- [ ] **Step 4: Commit the red contract with the removal in Task 2, not by itself**

The test remains uncommitted until its matching implementation is green.

### Task 2: Remove Control Plane runtime and build surface

**Files:**
- Delete: `apps/control-plane/`
- Delete: `apps/web-cp/`
- Delete: `e2e/tests/browser/control-plane/`
- Delete: `scripts/dev/test-cp-api.ps1`
- Delete: `docs/api/control-plane-instance-v1.md`
- Modify: `pom.xml`
- Modify: `Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `.github/workflows/ci.yml`
- Modify: `e2e/support/auth.ts`
- Modify: `e2e/playwright.config.ts`
- Modify: `scripts/security/scan-runtime-images.ps1`
- Modify: `scripts/prod/test-release-config.ps1`
- Test: `scripts/architecture/test-unified-boundaries.ps1`

**Interfaces:**
- Consumes: Task 1 failing gate.
- Produces: one Maven backend module and one Angular application with no CP service, database, UI, E2E project, image, or CI gate.

- [ ] **Step 1: Remove CP from Maven and Docker build graph**

Remove `<module>apps/control-plane</module>`, CP Docker build arguments/stages, and CP image scanning.

- [ ] **Step 2: Remove CP services and variables from local Compose**

Delete `db-cp`, `migrate-cp`, `control-plane`, `web-cp`, `db-cp-data`, `CP_*`, and `DWH_CP_*`. Keep current instance service names until Task 8 performs the mechanical rename.

- [ ] **Step 3: Remove CP UI/backend/tests and stale helper branches**

Delete the exact directories/files above and remove `loginToControlPlane`, `cpBaseUrl`, and CP Playwright projects.

- [ ] **Step 4: Verify GREEN**

Run:

```powershell
pwsh -NoProfile -File scripts/architecture/test-unified-boundaries.ps1
mvn -B -pl apps/instance -am -DskipTests package
Push-Location apps/web-instance; npm ci; npm run typecheck; npm run build; Pop-Location
docker compose config --services
```

Expected: boundary gate passes; backend/web build; services contain no CP names.

- [ ] **Step 5: Commit**

Commit message: `refactor(platform): remove control plane runtime`

### Task 3: Replace heartbeat/license coupling with local system information

**Files:**
- Delete: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/`
- Delete: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/license/`
- Delete: `apps/instance/src/test/java/com/greenwhite/dwh/instance/config/cp/`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/system/BackupStatus.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/system/BackupStatusReader.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/system/SystemInfoResponse.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/system/SystemInfoService.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/system/SystemInfoController.java`
- Create: `apps/instance/src/test/java/com/greenwhite/dwh/instance/config/system/BackupStatusReaderTest.java`
- Create: `apps/instance/src/test/java/com/greenwhite/dwh/instance/config/system/SystemInfoControllerTest.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/bootstrap/InstanceBootstrap.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/health/DwhInfoContributor.java`
- Modify: `apps/instance/src/main/resources/application.yml`
- Modify: `apps/instance/pom.xml`

**Interfaces:**
- Produces: `GET /api/v1/system/info` returning `appVersion`, `schemaVersion`, `organization`, `storageProvider`, `components`, and `backup`; `BackupStatusReader.read(): BackupStatus` reads bounded non-secret JSON from `${dwh.backup.status-file}`.

- [ ] **Step 1: Write failing backup status tests**

Use `@TempDir` and real files. Test valid JSON, missing file (`NEVER`), oversized file (`UNKNOWN`), malformed JSON (`UNKNOWN`), and verify that repository paths/credentials present in input are not represented by `BackupStatus`.

```java
assertThat(reader.read()).isEqualTo(new BackupStatus("SUCCESS", instant, null));
```

- [ ] **Step 2: Verify RED**

Run: `mvn -B -pl apps/instance -Dtest=BackupStatusReaderTest test`

Expected: compilation failure because system types do not exist.

- [ ] **Step 3: Implement bounded status parsing**

Use a 16 KiB maximum, `NOFOLLOW_LINKS`, a typed Jackson record, and neutral states `SUCCESS`, `FAILED`, `NEVER`, `UNKNOWN`. Do not return arbitrary error text from the sidecar.

- [ ] **Step 4: Write and verify failing controller contract**

Use standalone MockMvc with the real controller/service boundary. Assert the JSON has no license/CP fields and permission annotation is `settings:view`.

- [ ] **Step 5: Implement system information**

Reuse `ProviderRegistry.getActiveStorageProvider().getProviderCode()`, Flyway schema lookup, build properties, sanitized component health, and `BackupStatusReader`. Replace `/system/license-info` without redirect or compatibility endpoint.

- [ ] **Step 6: Remove CP/license classes and bootstrap columns**

Bootstrap inserts only organization code/name/resource profile. Remove `dwh.control-plane` configuration and heartbeat build comments.

- [ ] **Step 7: Verify GREEN**

Run: `mvn -B -pl apps/instance -am verify`

- [ ] **Step 8: Commit**

Commit message: `refactor(server): replace control plane status with local system info`

### Task 4: Implement V019 forward migration

**Files:**
- Create: `apps/instance/src/main/resources/db/migration/V019__unified_open_source_core.sql`
- Create: `apps/instance/src/test/java/com/greenwhite/dwh/instance/db/UnifiedOpenSourceMigrationIntegrationTest.java`
- Modify: `apps/instance/src/test/java/com/greenwhite/dwh/instance/db/FlywayMigrationScriptIntegrityTest.java`

**Interfaces:**
- Produces: local `ms_announcements`, preserved reads, disabled module metadata, no license columns, and RBAC actions for local announcement administration.

- [ ] **Step 1: Write failing V018-to-V019 integration test**

Start PostgreSQL 18, migrate with Flyway target `018`, insert one announcement/read and one approved custom module, then migrate to latest. Assert:

```java
assertThat(columns("md_instance_info"))
    .doesNotContain("license_token", "license_status", "grace_until", "cp_public_keys");
assertThat(count("ms_announcements")).isEqualTo(1);
assertThat(singleString("select status from md_custom_modules")).isEqualTo("DISABLED");
```

Also insert a new announcement without an explicit id and assert its id exceeds the migrated id.

- [ ] **Step 2: Verify RED**

Run: `mvn -B -pl apps/instance -Dtest=UnifiedOpenSourceMigrationIntegrationTest test`

Expected: missing V019/new relation.

- [ ] **Step 3: Implement V019**

Rename the cache table, normalize states to uppercase, add identity/default sequence after `max(id)`, add `created_by`, lifecycle timestamps and `lock_version`, preserve the read FK, disable modules, remove CP ticket data, drop obsolete license columns, and seed idempotent announcement actions.

- [ ] **Step 4: Verify GREEN and migration integrity**

Run: `mvn -B -pl apps/instance -Dtest=UnifiedOpenSourceMigrationIntegrationTest,FlywayMigrationValidationTest,FlywayMigrationScriptIntegrityTest test`

- [ ] **Step 5: Commit**

Commit message: `feat(database): migrate to unified open-source schema`

### Task 5: Implement audited local announcements

**Files:**
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/model/AnnouncementState.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/model/AnnouncementDraftRequest.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/service/MsAnnouncementService.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/controller/MsAnnouncementAdminController.java`
- Create: `apps/instance/src/test/java/com/greenwhite/dwh/instance/ms/notify/MsAnnouncementServiceIntegrationTest.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/repository/MsAnnouncementRepository.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/controller/MsAnnouncementController.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/service/MsNotificationService.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/pref/MsNotifyPref.java`

**Interfaces:**
- Produces: user read endpoints plus admin `GET /manage`, `POST`, `PUT /{id}`, `POST /{id}/publish`, `POST /{id}/archive`; optimistic `lockVersion`; state machine `DRAFT -> PUBLISHED -> ARCHIVED`.

- [ ] **Step 1: Write failing lifecycle integration tests**

Use real PostgreSQL/Flyway and real repository/service. Assert create preserves RU/EN text, update rejects a stale version, publish makes the announcement visible, archive hides it, invalid transitions fail, and each mutation writes audit data.

- [ ] **Step 2: Verify RED**

Run: `mvn -B -pl apps/instance -Dtest=MsAnnouncementServiceIntegrationTest test`

- [ ] **Step 3: Implement repository and service state machine**

Use transactional conditional updates with `where id=:id and lock_version=:lockVersion`. Accept only `INFO`, `WARNING`, `CRITICAL`; require non-blank RU title/body; bound each localized value to 10,000 characters.

- [ ] **Step 4: Implement permission-separated controllers**

Annotate each admin endpoint with the exact action from V019. Return 201 for create, 200 for transitions, 409 for stale versions, 400 for invalid state transitions.

- [ ] **Step 5: Verify GREEN**

Run: `mvn -B -pl apps/instance -am verify`

- [ ] **Step 6: Commit**

Commit message: `feat(announcements): add local audited lifecycle`

### Task 6: Disable unsafe custom modules end-to-end

**Files:**
- Delete: `apps/instance/src/main/java/com/greenwhite/dwh/instance/md/controller/MdCustomModuleController.java`
- Delete: `apps/instance/src/main/java/com/greenwhite/dwh/instance/md/service/MdCustomModuleService.java`
- Delete: `apps/instance/src/main/java/com/greenwhite/dwh/instance/md/repository/MdCustomModuleRepository.java`
- Delete: `apps/instance/src/test/java/com/greenwhite/dwh/instance/md/MdCustomModuleServiceTest.java`
- Modify: `apps/instance/src/test/java/com/greenwhite/dwh/instance/config/security/SecurityConfigTest.java`
- Modify: `apps/web-instance/src/app/features/settings/settings.component.spec.ts`
- Modify: `apps/web-instance/src/app/features/settings/settings.component.ts`

**Interfaces:**
- Produces: no HTTP route or UI path capable of creating, approving, listing active, or executing a custom module; preserved disabled rows remain database-only.

- [ ] **Step 1: Write failing HTTP/UI tests**

Assert `/api/v1/modules` has no handler and settings renders neither `Control Plane` nor module entrypoint/moderation controls.

- [ ] **Step 2: Verify RED**

Run backend security test and `npm test -- --run settings.component.spec.ts`; confirm existing routes/UI make the tests fail.

- [ ] **Step 3: Remove runtime and UI surfaces**

Delete exact classes and remove system-license/module tabs, signals, API calls, dialogs and CP strings from settings.

- [ ] **Step 4: Verify GREEN**

Run backend module tests and full web unit/typecheck/build.

- [ ] **Step 5: Commit**

Commit message: `fix(security): disable unverified custom modules`

### Task 7: Add vendor-neutral S3-compatible storage

**Files:**
- Modify: `pom.xml`
- Modify: `apps/instance/pom.xml`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/mf/storage/S3StorageProperties.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/mf/storage/S3StorageConfiguration.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/mf/storage/S3StorageProvider.java`
- Create: `apps/instance/src/test/java/com/greenwhite/dwh/instance/mf/S3StorageProviderIntegrationTest.java`
- Modify: `apps/instance/src/main/resources/application.yml`
- Modify: `.env.example`

**Interfaces:**
- Produces: provider code `s3`, endpoint/region/path-style configuration, upload/download/delete/exists/health behavior through the existing `StorageProvider` SPI.

- [ ] **Step 1: Pin AWS SDK BOM from Maven Central and write failing MinIO test**

Use a real MinIO Testcontainer. Create a bucket, upload bytes, assert returned literal SHA-256, download identical bytes/content type, delete, and verify health does not reveal endpoint or credentials.

- [ ] **Step 2: Verify RED**

Run: `mvn -B -pl apps/instance -Dtest=S3StorageProviderIntegrationTest test`

- [ ] **Step 3: Implement conditional S3 client/provider**

Activate only when `dwh.providers.storage=s3`. Use explicit credentials, endpoint override, region, path-style access, HTTP connect/read timeouts, checksum validation, bounded errors, and close download streams.

- [ ] **Step 4: Verify GREEN**

Run the integration test and existing file/provider tests.

- [ ] **Step 5: Commit**

Commit message: `feat(storage): add S3-compatible provider`

### Task 8: Build local System and Announcements UI

**Files:**
- Create: `apps/web-instance/src/app/features/system/system.component.ts`
- Create: `apps/web-instance/src/app/features/system/system.component.spec.ts`
- Create: `apps/web-instance/src/app/features/announcements/announcements.component.ts`
- Create: `apps/web-instance/src/app/features/announcements/announcements.component.spec.ts`
- Modify: `apps/web-instance/src/app/features/settings/settings.component.ts`
- Modify: `apps/web-instance/src/app/app.routes.ts`
- Modify: `apps/web-instance/src/app/layout/app-shell/app-shell.component.ts`
- Modify: `apps/web-instance/src/app/core/services/permission.service.ts`

**Interfaces:**
- Consumes: Task 3 system info and Task 5 announcement APIs.
- Produces: `/system` read-only operations screen and `/announcements` local admin lifecycle with loading, empty, error, modal, confirmation, focus and mobile states.

- [ ] **Step 1: Write failing component tests**

System test asserts semantic status list, backup `NEVER/FAILED/SUCCESS`, no secret/raw path rendering, and CLI-only copy. Announcement tests assert accessible draft form, disabled publish while invalid, stale-update error, publish confirmation, archive confirmation and empty/error states.

- [ ] **Step 2: Verify RED**

Run both Vitest spec paths; expect imports/components to be missing.

- [ ] **Step 3: Implement minimal typed components**

Use signals, OnPush, existing UI primitives and ApiService. Do not use `any`; define response/request interfaces next to each feature until a shared API package exists.

- [ ] **Step 4: Wire navigation and remove legacy settings surface**

Add permission-aware links and routes. Keep general/security/channel settings in Settings; move system status out.

- [ ] **Step 5: Verify GREEN**

Run full web tests, typecheck and production build.

- [ ] **Step 6: Commit**

Commit message: `feat(web): add local system and announcement administration`

### Task 9: Unify names, paths, Compose and backup sidecar

**Files:**
- Move: `apps/instance/` -> `apps/server/`
- Move: `apps/web-instance/` -> `apps/web/`
- Create: `deploy/images/backup/Dockerfile`
- Create: `deploy/images/backup/backup-loop.sh`
- Create: `deploy/images/backup/write-status.sh`
- Create: `deploy/images/backup/bootstrap-role.sh`
- Modify: `pom.xml`
- Modify: `Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `deploy/compose/docker-compose.prod.yml`
- Delete: `deploy/compose/docker-compose.fleet.prod.yml`
- Modify: `deploy/compose/.env.example`
- Modify: `deploy/nginx/nginx.prod.conf`
- Modify: `apps/web/nginx.conf`
- Modify: `scripts/prod/*.ps1`
- Modify: `scripts/prod/*.sh`
- Modify: `scripts/security/scan-runtime-images.ps1`
- Modify: `.github/workflows/ci.yml`
- Modify: `scripts/architecture/test-unified-boundaries.ps1`
- Create: `scripts/prod/test-backup-status.ps1`

**Interfaces:**
- Produces: services `postgres`, `migrate`, `server`, `web`, `typesense`, `backup`; images `smartupcms/server`, `smartupcms/web`; one web origin proxies API to `server:8080`; atomic backup status JSON shared read-only with server.

- [ ] **Step 1: Tighten boundary test and verify RED**

Require new paths/image/service names and forbid old `instance`, `app`, `web-instance`, and fleet Compose runtime identifiers.

- [ ] **Step 2: Perform exact mechanical moves**

Use `git mv` only after confirming source paths exist and destination paths do not. Update Maven relative paths, Angular project names/output paths, Docker contexts, E2E paths and CI caches.

- [ ] **Step 3: Implement backup image and behavior test**

The test runs the status writer in a temporary volume, verifies atomic valid JSON, `0600` file mode, no DSN/password/archive path, and explicit `SUCCESS/FAILED` outcomes. `bootstrap-role.sh` creates/rotates a dedicated read-only PostgreSQL backup role through `psql` variables without echoing its password. The backup loop fails closed when encryption configuration is absent, always encrypts `pg_dump` output with `age`, applies local retention, and performs S3 upload only in explicit S3 mode.

- [ ] **Step 4: Consolidate production Compose**

Make `docker-compose.prod.yml` the only production topology. Add health checks, read-only status mount, non-root containers, internal networks and required production secrets. Remove the fleet file and CP proxy upstream.

- [ ] **Step 5: Verify GREEN**

Run architecture, release-config, backup-status and `docker compose config` gates; build both Maven/Angular artifacts.

- [ ] **Step 6: Commit**

Commit message: `refactor(platform): unify SmartupCMS runtime`

### Task 10: Publish Apache-2.0 governance surface

**Files:**
- Replace: `LICENSE`
- Create: `NOTICE`
- Create: `RELICENSE.md`
- Create: `DCO`
- Create: `SECURITY.md`
- Create: `CODE_OF_CONDUCT.md`
- Create: `GOVERNANCE.md`
- Create: `SUPPORT.md`
- Create: `CHANGELOG.md`
- Create: `.github/pull_request_template.md`
- Create: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `.github/workflows/dco.yml`
- Modify: `CONTRIBUTING.md`
- Modify: `README.md`
- Modify: `docs/onboarding.md`
- Modify: `docs/adr/ADR-0004-deployment-model.md`
- Modify: `docs/adr/ADR-0007-fleet-strategy.md`

**Interfaces:**
- Produces: OSI-approved Apache-2.0 distribution terms, Smartup notice/relicensing statement, private vulnerability reporting through GitHub Security Advisories, DCO PR gate and public contributor documentation.

- [ ] **Step 1: Fetch canonical legal/community texts from their official stewards**

Use the Apache Software Foundation Apache-2.0 text, Developer Certificate of Origin 1.1, and Contributor Covenant 2.1 without custom clauses inside those canonical documents.

- [ ] **Step 2: Replace proprietary language and document superseded ADRs**

README quickstart uses one Compose stack and has no CP/license claims. Mark ADR-0004/0007 superseded by the unified design rather than erasing decision history.

- [ ] **Step 3: Implement DCO behavior gate**

Workflow checks every PR commit message for a matching `Signed-off-by: Name <email>` trailer and uses read-only permissions.

- [ ] **Step 4: Validate**

Run repository text scan for active proprietary/confidential restrictions and broken Markdown links; review all matches in historical/audit documents explicitly.

- [ ] **Step 5: Commit**

Commit message: `docs(oss): publish Apache governance and contribution policy`

### Task 11: Add signed multi-architecture release supply chain

**Files:**
- Create: `.github/workflows/release.yml`
- Create: `scripts/release/verify-release.ps1`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`

**Interfaces:**
- Produces: tag-only SemVer release, `linux/amd64` and `linux/arm64` GHCR manifests, SBOM, GitHub provenance attestation, keyless Cosign signatures, checksums and versioned Compose bundle.

- [ ] **Step 1: Write failing release workflow verifier**

Parse workflow YAML and run behavioral checks where possible: invalid/non-SemVer refs fail, required images are present, action refs are full commit SHAs, workflow permissions are minimal, and fork PRs cannot access release secrets.

- [ ] **Step 2: Verify RED**

Run: `pwsh -NoProfile -File scripts/release/verify-release.ps1`

- [ ] **Step 3: Implement pinned release workflow**

Resolve current official action tag SHAs, pin each SHA with a version comment, use OIDC only in the tag workflow, build images from `apps/server`/`apps/web`, emit SBOM/provenance, sign digest references and attach checksums/Compose/env bundle to GitHub Release.

- [ ] **Step 4: Verify GREEN**

Run verifier, Docker Buildx bake/build in no-push mode, and validate generated Compose against immutable test tags.

- [ ] **Step 5: Commit**

Commit message: `ci(release): sign multi-architecture SmartupCMS images`

### Task 12: History audit, E2E and final clean deployment

**Files:**
- Modify: `e2e/tests/browser/instance/auth.spec.ts` after path rename to `server` naming in diagnostics only
- Modify: `e2e/tests/browser/instance/tasks.spec.ts`
- Create: `e2e/tests/browser/system/system.spec.ts`
- Create: `e2e/tests/browser/announcements/announcements.spec.ts`
- Modify: `e2e/playwright.config.ts`
- Create: `scripts/security/test-no-default-egress.ps1`
- Create: `audit/architecture-2026-09-02.md`
- Create: `audit/evidence/smartupcms-unified-open-source-2026-09-02.md`
- Modify: `audit/fixes/00-implementation-tracker.md`

**Interfaces:**
- Produces: release evidence for code, migrations, UI, clean deployment, upgrade, no-egress posture, image vulnerabilities, full-history secrets and public-readiness blockers.

- [ ] **Step 1: Write E2E tests before final UI adjustments**

Cover login, system status, announcement draft/publish/archive, tasks, users and files through the single web origin. Include keyboard focus, mobile viewport and axe checks for new screens.

- [ ] **Step 2: Verify RED where new flows are incomplete, then make only required fixes**

Use the Playwright skill and preserve traces/screenshots for failures.

- [ ] **Step 3: Run full repository gates**

```powershell
mvn -B verify
Push-Location apps/web; npm ci; npm test -- --run; npm run typecheck; npm run build; Pop-Location
pwsh -NoProfile -File scripts/architecture/test-unified-boundaries.ps1
pwsh -NoProfile -File scripts/prod/test-release-config.ps1
pwsh -NoProfile -File scripts/release/verify-release.ps1
```

- [ ] **Step 4: Verify V018 upgrade and clean install**

Use isolated Compose project names and synthetic credentials. Verify pre-migration backup, V019, all service health checks and HTTP 200 through the single origin.

- [ ] **Step 5: Run E2E, no-egress and security scans**

Run Playwright Chromium, Trivy on every runtime image, dependency audits, and Gitleaks against all refs/history. `test-no-default-egress.ps1` starts the default stack on an isolated monitored network, waits longer than every startup/scheduled task window, and fails on attempted external destinations while allowing declared Compose service addresses. Record exact commands, versions, counts and unresolved findings.

- [ ] **Step 6: Update graph and evidence**

Run `graphify update .`, update the implementation tracker and write the evidence report without secrets.

- [ ] **Step 7: Commit and push**

Commit message: `test(release): verify unified open-source deployment`

- [ ] **Step 8: Finish the branch**

Invoke `superpowers:finishing-a-development-branch`, rerun fresh verification, compare local/remote SHA, and keep GitHub visibility private pending the owner's explicit publication command.
