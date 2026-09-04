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
- [ ] Commit only application, tests, migration, specification, and this plan.
- [ ] Push `main` and capture the remote CI result for the pushed SHA.

### Task 2: Rebuild and deploy the local Docker runtime from clean images

**Files:**
- Verify: `compose.yaml`
- Verify: Dockerfiles referenced by Compose
- Verify: `apps/server/src/main/resources/db/migration/V023__audit_keyset_pagination.sql`

- [ ] Record current containers and named volumes.
- [ ] Build the application images with `docker compose build --no-cache`.
- [ ] Recreate services with `docker compose up -d` without deleting named volumes.
- [ ] Wait for service health checks and verify the public web endpoint.
- [ ] Verify Flyway applied V023 and the audit indexes exist.
- [ ] Capture container status and relevant startup logs.

### Task 3: Add actionable audit loading and error states

**Files:**
- Modify: `apps/web/src/app/features/audit/audit.component.ts`
- Modify: `apps/web/src/app/features/audit/audit.component.spec.ts`
- Modify: `apps/server/src/main/resources/i18n/messages_ru.json`
- Modify: other supported locale catalogs with reviewed fallback wording
- Generate: packaged frontend locale catalogs through `npm run i18n:sync-ru`

- [ ] Write failing component tests for visible load errors, retry, and loading semantics.
- [ ] Add localized inline error states and retry actions for statistics, log history, and security events.
- [ ] Keep existing data visible during a recoverable refresh failure and clear errors after a successful retry.
- [ ] Verify keyboard focus, accessible names, `aria-live`, and `aria-busy` behavior.
- [ ] Run focused component tests and the localization audit.

### Task 4: Add route protection and complete server-backed audit filters

**Files:**
- Modify: `apps/web/src/app/app.routes.ts`
- Create/Modify: `apps/web/src/app/app.routes.spec.ts`
- Modify: `apps/web/src/app/features/audit/audit.component.ts`
- Modify: `apps/web/src/app/features/audit/audit.component.spec.ts`

- [ ] Write a failing route test requiring `permissionGuard('audit.log', 'view')` on `/audit`.
- [ ] Add the guard while retaining backend authorization as the source of truth.
- [ ] Write failing tests for audit period, user, and row-PK filters and for security period/user filters.
- [ ] Bind filters to the existing server query parameters and reset cursor history on every filter change.
- [ ] Add one explicit reset action per filter group and verify no duplicate rows after paging.

### Task 5: Add endpoint-specific authorization and browser regression coverage

**Files:**
- Modify/Create: backend audit security integration tests
- Create: `e2e/tests/browser/instance/audit.spec.ts`
- Modify only if required: `e2e/playwright.config.ts` and test helpers

- [ ] Add a failing integration test proving `/api/audit/logs`, `/api/audit/security-events`, and `/api/audit/stats` return 403 without `audit.log:view`.
- [ ] Add permitted-role assertions for all three endpoints.
- [ ] Add Playwright coverage for paging forward/backward, filter reset, stable unique rows, visible redaction, forbidden access, malformed cursor handling, and retryable network failure.
- [ ] Run focused backend tests and the audit browser specification against the clean Compose runtime.

### Task 6: Release verification and publication

**Files:**
- Update: this plan's checkboxes
- Update only when evidence exists: `docs/project-status.md` and applicable release evidence

- [ ] Run the full backend, frontend, architecture, documentation, release, and production configuration gates documented by the repository.
- [ ] Run `graphify update .` after the final source change, but keep dirty-checkout Graphify artifacts out of the commit.
- [ ] Review the final diff for secrets, generated noise, and unrelated files.
- [ ] Commit and push the P1/E2E slice.
- [ ] Rebuild and redeploy the local Compose runtime; rerun the audit E2E smoke against the deployed image.

### Task 7: Continue release blockers in separate focused plans

- [ ] Create a decision-and-test plan for `P0-14` task/file data scope based on ADR-0013.
- [ ] Create an acceptance plan for `P0-15` covering Hetzner, Cloudflare/R2, alerts, 100-user load/soak, and combined database/object restore.
- [ ] Implement every locally provable item in order.
- [ ] Record target-only checks as unverified until the required environment, DNS, R2, alerting, and release credentials are available.
