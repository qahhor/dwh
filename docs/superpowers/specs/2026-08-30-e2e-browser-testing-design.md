# Browser E2E testing design

**Date:** 2026-08-30
**Status:** approved
**Scope:** deepen the existing product by testing the two deployed web applications as one system.

## Decision

Create a standalone Playwright project in `e2e/`. It owns browser-level tests for both
the Instance UI and Control Plane UI, while the Angular projects keep their fast unit
tests and the PowerShell scripts keep their API-level live checks.

This layout was selected over duplicating Playwright configuration in both Angular
applications or hiding the runner inside a Docker-only workflow. A single root suite
can share authentication, API setup, diagnostics and CI lifecycle without coupling
the browser tests to either frontend build.

## Test layers

1. **Unauthenticated contracts**
   - protected Instance routes redirect to `/login`;
   - invalid credentials produce an accessible error without navigation;
   - both login screens expose labelled controls.
2. **Authenticated navigation**
   - Instance admin can enter the shell and visit the principal product areas;
   - Control Plane admin can enter the shell and visit fleet operations;
   - pages must not emit browser console errors.
3. **Critical vertical slices**
   - Instance: create a project, create a task, open it and add a comment;
   - Control Plane: create a client and register an instance, then create, publish and
     archive an announcement;
   - records are identified by a unique run id. API cleanup is used where supported;
     CI always destroys the entire isolated Compose project after the run.

## Configuration and secrets

- `INSTANCE_BASE_URL` defaults to `http://localhost:4200`.
- `CP_BASE_URL` defaults to `http://localhost:4300`.
- credentials are read from process environment and, for local development only,
  from the ignored root `.env` file.
- secret values and generated tokens are never written to reports or console output.
- missing credentials fail fast with a message that names only the missing key.

The existing `scripts/dev/test-cp-api.ps1` must follow the same rule: read
`CP_ADMIN_LOGIN` and `CP_ADMIN_PASSWORD`, validate the current response contract, and
stop printing generated heartbeat tokens.

## Stability rules

- Prefer accessible roles, names and labels; do not bind tests to CSS layout.
- Use API responses and visible headings as readiness signals; avoid fixed sleeps.
- Run Chromium first. Additional browsers are deliberately outside this initial scope.
- Retain screenshots only on failure; trace, video and HTML reports stay disabled
  because Playwright can serialize entered credentials into those artifacts.
- A deliberately failing sentinel probe exercises the production authentication and
  token-dismiss paths, then scans reporter output and all generated failure artifacts
  to prevent password/token-redaction regressions.
- Each test owns its data and can run repeatedly against a developer stack.
- Serial execution is used for stateful vertical slices; read-only contracts may run
  independently later when fixture isolation proves safe.

## CI lifecycle

The `e2e` job builds the Compose stack, migrates both empty databases, starts services,
waits for health, installs Chromium, runs Playwright, uploads failure artifacts, and
executes `docker compose down --volumes --remove-orphans` in an unconditional cleanup
step. This guarantees that CI tests the same clean-deploy path verified locally.

## Acceptance criteria

- the new runner can be installed reproducibly with `npm ci`;
- an intentionally absent deployment produces a useful failing test;
- the approved browser flows pass against the local Compose stack;
- no credentials or tokens appear in output or Playwright artifacts;
- frontend unit/build checks, backend regression tests and Graphify remain current.
