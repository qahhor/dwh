# Browser E2E implementation plan

> Execute this plan inline in the current task. Apply test-driven development to the
> runner helpers and prove each browser flow against the deployed stack.

**Goal:** add a reproducible Playwright E2E suite for the Instance and Control Plane
web applications and make the clean-deploy path executable in CI.

**Architecture:** a standalone Node package under `e2e/` with one Playwright config,
shared environment/auth helpers, separate Instance and Control Plane specs, and a CI
job that owns an isolated Docker Compose lifecycle.

**Stack:** Node.js 24, TypeScript, `@playwright/test`, Docker Compose, GitHub Actions.

## Task 1: Bootstrap the runner

**Files:** `e2e/package.json`, `e2e/package-lock.json`, `e2e/tsconfig.json`,
`e2e/playwright.config.ts`, `e2e/playwright.artifact-security.config.ts`,
`e2e/.gitignore`.

1. Add a configuration contract test that fails when required credentials are absent.
2. Implement an environment loader with ignored root `.env` fallback and redacted
   errors.
3. Add Playwright configuration for Chromium, failure-only artifacts and serial-safe
   execution.
4. Add a deliberately failing sentinel probe that scans reporter output and every
   failure artifact for credential leaks.
5. Run the helper tests and TypeScript validation.

## Task 2: Instance browser contracts

**Files:** `e2e/tests/browser/instance/auth.spec.ts`,
`e2e/tests/browser/instance/tasks.spec.ts`, shared fixtures under `e2e/support/`.

1. Prove protected-route redirect and invalid-login behaviour.
2. Add authenticated session setup using the visible login form.
3. Verify principal navigation landmarks and console cleanliness.
4. Implement the project → task → comment vertical slice using unique data.
5. Run the Instance project until green.

## Task 3: Control Plane browser contracts

**Files:** `e2e/tests/browser/control-plane/auth.spec.ts`,
`e2e/tests/browser/control-plane/fleet.spec.ts`,
`e2e/tests/browser/control-plane/fleet-registration.spec.ts`, shared fixtures under
`e2e/support/`.

1. Add valid/invalid authentication checks.
2. Verify navigation across fleet, clients, backups and announcements.
3. Implement client → instance and announcement → publish → archive vertical slices.
4. Run the Control Plane project until green.

## Task 4: Repair the legacy CP live suite

**Files:** `scripts/dev/test-cp-api.ps1`.

1. Reproduce failure with a non-default `.env` password.
2. Replace hard-coded credentials with environment/ignored `.env` resolution.
3. Remove heartbeat-token output and assert the actual login response contract.
4. Re-run all nine scenarios.

## Task 5: CI and operational entry points

**Files:** `.github/workflows/ci.yml`, `scripts/dev/test-e2e.ps1`, relevant docs.

1. Add a local orchestration script that validates health and invokes Playwright
   without owning unrelated Docker projects.
2. Add an isolated CI job with clean migration, failure artifacts and unconditional
   volume cleanup.
3. Document local commands and required environment keys.

## Task 6: Verification and delivery

1. Run E2E helper tests, Chromium suites, both frontend tests/builds and relevant
   backend regression tests.
2. Scan runtime and browser logs for errors.
3. Run `graphify update .` and remove query stamps.
4. Inspect the final diff, commit to `main`, push, and verify `origin/main` parity.
