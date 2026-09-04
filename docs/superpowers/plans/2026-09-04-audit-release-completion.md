# Audit Release Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Release the hardened `/audit` workflow, add its remaining operator-facing safeguards and browser coverage, and prove it in the clean Docker runtime.

**Architecture:** Keep the existing Spring Boot modular monolith and Angular application. Audit pagination remains server-owned and cursor-based; the web client stores only opaque cursor history. Authorization remains enforced by the backend and is mirrored by an Angular route guard for navigation UX. All new UI states use the existing shared UI and localization catalogs.

**Tech Stack:** Java 21, Spring Boot 3, PostgreSQL/Flyway, Angular 21, Vitest, Playwright, Docker Compose.

**Spec:** `docs/technical-specification.md` (`FR-ADMIN-02`, `NFR-SEC-03`).

## Global Constraints

- Preserve the canonical Russian locale and keep packaged locale catalogs synchronized.
- Never expose credential-like values in audit responses, including nested maps, arrays, iterables, or serializable objects.
- Keep `audit.log:view` enforced on the server; the route guard is defense in depth, not the security boundary.
- Preserve Docker data volumes during rebuild and deployment.
- Do not commit `output/` or Graphify output generated from a dirty checkout.
- Do not claim target Hetzner/Cloudflare/R2 acceptance without real credentials and captured evidence.

---

### Task 1: Publish the completed audit P0 slice

**Files:**
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/audit/**`
- Create: `apps/server/src/main/resources/db/migration/V023__audit_keyset_pagination.sql`
- Modify/Create: `apps/server/src/test/java/com/greenwhite/dwh/instance/audit/**`
- Modify: `apps/web/src/app/features/audit/audit.component.ts`
- Modify: `apps/web/src/app/shared/ui/ui-pagination.component.ts`
- Modify: corresponding frontend specifications and `docs/technical-specification.md`

- [x] Run `mvn -B verify` from the repository root.
- [x] Run web unit tests, typecheck, localization audit, and production build with the bundled Node runtime.
- [x] Run `git diff --check` and inspect the exact staged diff.
- [x] Commit only application, tests, migration, specification, and this plan (`4a7fece`).
- [x] Push `main` and capture the final remote CI result for the pushed SHA ([run 33883389351](https://github.com/qahhor/dwh/actions/runs/33883389351): success).

### Task 2: Rebuild and deploy the local Docker runtime from clean images

**Files:**
- Verify: `compose.yaml`
- Verify: Dockerfiles referenced by Compose
- Verify: `apps/server/src/main/resources/db/migration/V023__audit_keyset_pagination.sql`

- [x] Record current containers and named volumes.
- [x] Build the application images with `docker compose build --no-cache`.
- [x] Recreate services with `docker compose up -d` without deleting named volumes.
- [x] Wait for service health checks and verify the public web endpoint.
- [x] Verify Flyway applied V023 and the audit indexes exist.
- [x] Capture container status and relevant startup logs.

### Task 3: Add actionable audit loading and error states

**Files:**
- Modify: `apps/web/src/app/features/audit/audit.component.ts`
- Modify: `apps/web/src/app/features/audit/audit.component.spec.ts`
- Modify: `apps/server/src/main/resources/i18n/ru.json`
- Generate: packaged frontend locale catalogs through `npm run i18n:sync-ru`

- [x] Write failing component tests for visible load errors, retry, and loading semantics.
- [x] Add localized inline error states and retry actions for statistics, log history, and security events.
- [x] Keep existing data visible during a recoverable refresh failure and clear errors after a successful retry.
- [x] Verify accessible names, alert semantics, and `aria-busy` behavior.
- [x] Run focused component tests and the localization audit.

### Task 4: Add route protection and complete server-backed audit filters

**Files:**
- Modify: `apps/web/src/app/app.routes.ts`
- Create/Modify: `apps/web/src/app/app.routes.spec.ts`
- Modify: `apps/web/src/app/features/audit/audit.component.ts`
- Modify: `apps/web/src/app/features/audit/audit.component.spec.ts`

- [x] Write a failing route test requiring `permissionGuard('audit.log', 'view')` on `/audit`.
- [x] Add the guard while retaining backend authorization as the source of truth.
- [x] Write failing tests for audit period, user, and row-PK filters and for security period/user filters.
- [x] Bind filters to the existing server query parameters and reset cursor history on every filter change.
- [x] Add one explicit reset action per filter group and verify no duplicate rows after paging.

### Task 5: Add endpoint-specific authorization and browser regression coverage

**Files:**
- Modify/Create: backend audit security integration tests
- Create: `e2e/tests/browser/instance/audit.spec.ts`
- Modify only if required: `e2e/playwright.config.ts` and test helpers

- [x] Add a failing integration test proving `/api/v1/audit/logs`, `/api/v1/audit/security-events`, and `/api/v1/audit/stats` return 403 without `audit.log:view`.
- [x] Add permitted-role assertions for all three endpoints.
- [x] Add Playwright coverage for paging forward/backward, filter reset, stable unique rows, visible redaction, forbidden access, malformed cursor handling, and retryable network failure.
- [x] Run focused backend tests and the audit browser specification against the clean Compose runtime.

### Task 6: Release verification and publication

**Files:**
- Update: this plan's checkboxes
- Update only when evidence exists: `docs/project-status.md` and applicable release evidence

- [x] Run the full backend, frontend, architecture, documentation, release, and production configuration gates documented by the repository.
- [x] Run `graphify update .` after the final source change, but keep dirty-checkout Graphify artifacts out of the commit.
- [x] Review the final diff for secrets, generated noise, and unrelated files.
- [x] Commit and push the P1/E2E slice.
- [x] Rebuild and redeploy the local Compose runtime; rerun the audit E2E smoke against the deployed image (4/4 focused; 24/24 full browser suite).

### Task 7: Continue release blockers in separate focused plans

- [x] Create a decision-and-test plan for `P0-14` task/file data scope based on ADR-0013.
- [x] Create an acceptance plan for `P0-15` covering Hetzner, Cloudflare/R2, alerts, 100-user load/soak, and combined database/object restore.
- [x] Implement every locally provable item in order.
- [x] Record target-only checks as unverified until the required environment, DNS, R2, alerting, approved thresholds/load users, and release credentials are available.
