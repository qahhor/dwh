# I-01 — Task export security implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute the tasks below in order. Apply test-driven-development and fix-finding validation/review boundaries.

**Goal:** Make every task export respect the authenticated user's existing task data scope, and encode editable CSV cells as text when their prefix is formula-capable.

**Architecture:** Controller supplies actor; service requires a non-null actor before emitting bytes; one private SQL builder appends `MdScopeService.filterForTasks(actor).sql()` with parameter binding. Both writers retain streaming callbacks.

**Tech Stack:** Existing Java 25, JdbcClient, PostgreSQL 18 Testcontainers/Flyway, JUnit, MockMvc.

**Spec:** `docs/adr/ADR-0013-data-scope.md`, `docs/adr/ADR-0008-security-baseline.md`, existing `/api/v1/reports/tasks/export` contract, `docs/guidelines/testing-strategy.md`.

## Global Constraints

- Keep `tasks.items.view`, existing default/unknown-format CSV fallback, case-insensitive `xlsx`/`excel` XML aliases, filenames, Russian headers, UTF-8 BOM, UTC timestamps and descending ID order.
- SELF includes creator, reporter and every member kind, including observer. UNITS/SUBTREE reuse existing materialized scope and both primary/multiple organizational assignments. ALL still sees all rows. Empty scope is HTTP 200 with headers.
- No unscoped overload, post-query filtering, pagination change, migration, frontend change or new permission.
- CSV-only policy: prepend one apostrophe and quote the cell for a leading formula marker (`=`, `+`, `-`, `@`, including full-width variants), including after leading whitespace, controls or Unicode format characters. Also neutralize leading tab/CR/LF; preserve original contents and ordinary CSV quoting. XML strings keep their original text and explicit string type. This is serialization coverage, not evidence of running Excel/LibreOffice.
- The initial-output policy does not guarantee safety after users save/re-import CSV in spreadsheet software. No universal client-independent mitigation is claimed; [OWASP CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection) describes this limitation. Typed XML remains the existing alternative for spreadsheet use.

## Files

- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/report/controller/ReportController.java`: pass `SecurityContext.getCurrentUserId()` to both writers.
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/report/service/ReportService.java`: inject scope service; signatures `(OutputStream, Long currentUserId)`; private shared scoped query; explicit unauthorized null actor; CSV text neutralization.
- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/report/ReportExportIntegrationTest.java`: actual PostgreSQL, actual scope services and report writer through MockMvc/interceptor; no mocked authorization predicate.
- Update this plan, roadmap and `docs/ai-context.md` with measured execution evidence only.

## Task 1 — Reproduce and close scope bypass

- [x] Run existing `MdScopeServiceIntegrationTest,ModularArchitectureTest` natively with Java 25/Maven and Docker; do not disable Ryuk or integration tests.
- [x] Add integration fixtures and tests for SELF participants/hidden rows; UNITS/SUBTREE sibling and descendant boundaries; multiple org assignment; empty and ALL scope; CSV/default/unknown format and both XML aliases; permission denial and required password change.
- [x] Run new tests against unchanged production source. Record assertion failures proving extra unauthorized exported IDs; infrastructure failure is not a red test.
- [x] Implement mandatory actor and shared SQL predicate. Add null-actor regression before changing the service API (or independent test against original API where applicable).
- [x] Rerun report tests and neighboring scope/security/architecture tests: no failures or skips.

## Task 2 — Protect CSV text cells

- [x] Add output-level tests for title/project/status/reporter formula prefixes, alternate leading representations, quoted delimiters/newlines and ordinary Russian text; preserve XML string cells and escaping.
- [x] Observe assertion failure with existing CSV encoder.
- [x] Apply neutralization before existing CSV quoting; rerun focused tests.

## Task 3 — Validate and hand off

- [x] Inspect all direct callers and final diff; `git diff --check`.
- [x] Run full backend `mvn -B verify` without excluding tests and review actual reports.
- [x] One fresh read-only security bypass/regression review; verify concrete findings and rerun affected gates if patched.
- [x] Run public-docs and architecture guards relevant to touched files, refresh Graphify, update handoff and plan status.
- [x] Record exact commands, pass/fail counts, red/green artifacts and residual uncertainties below. No commit/push/deploy in this batch.

## Execution evidence

Outcome: **fixed** for the task-export scope boundary; CSV initial-output neutralization implemented and serialization-tested under the policy above. Implementation began on `codex/release-hardening`, based on `710efeb55c03c2d444cfc8fd22dcefa01635e99c`, and was transferred to `main` for publication at the user's request.

Commands used repository-local `output/tools/maven/bin/mvn.cmd` (Maven 3.9.16 copied from pinned image `maven:3.9-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753`) and host Oracle Java 25.0.2 / Docker Desktop. Native execution avoided the prior container-to-Ryuk networking failure without disabling Ryuk, tests or cleanup. Logs under untracked `output/` are local diagnostics, not committed release evidence.

| Gate | Result on 2026-09-05 | Local log |
|---|---|---|
| Existing scope + architecture baseline | PASS: 26 tests, zero failures/errors/skips | `output/release-hardening-baseline.log` |
| Scope regression before patch | Expected FAIL: 34 tests, 26 assertion failures, zero errors/skips. Example: actual exported IDs `[8,7,6,5,4,3,2,1]`, allowed `[7,6,5,4,3,2,1]`; missing actor did not throw. Test-fixture mismatches were corrected before this recorded reproduction. | `output/task-export-scope-red.log` |
| Scope patch + neighboring tests | PASS: 68 tests, zero failures/errors/skips | `output/task-export-scope-green.log` |
| CSV regression before encoder patch | Expected FAIL: 69 tests, 22 assertion failures, zero errors/skips (`=1+1` emitted instead of text-prefixed cell); ordinary and XML cases passed | `output/task-export-csv-red.log` |
| Export + scope/security/architecture | PASS: 103 tests, zero failures/errors/skips | `output/task-export-final-focused.log` |
| Full backend reactor verify after final source edit | PASS, exit 0; server suite 334 tests, zero failures/errors/skips; packaged server artifact | `output/release-hardening-backend-verify.log` |
| Independent candidate security review | No concrete surviving bypass or compatibility regression; read-only review, no additional patch required | Current task review |
| Public docs / unified boundary / hygiene / whitespace | PASS | Commands below |
| Graphify AST refresh | PASS; 4,650 nodes / 12,113 edges. Existing version/community-label warnings remain; no install or semantic-label changes performed. Generated dirty graph is not publication-ready. | `graphify update .` |

Reproduction and final commands (from repository root):

```powershell
# Baseline: use -Dtest=MdScopeServiceIntegrationTest,ModularArchitectureTest.
# Each red cycle: use -Dtest=ReportExportIntegrationTest at the indicated pre-fix state.
& ./output/tools/maven/bin/mvn.cmd -B -pl apps/server -am '-Dtest=ReportExportIntegrationTest,MdScopeServiceIntegrationTest,TaskFileDataScopeControllerTest,ModularArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
& ./output/tools/maven/bin/mvn.cmd -B verify
& ./scripts/docs/test-public-docs.ps1
& ./scripts/architecture/test-unified-boundaries.ps1
& ./scripts/docs/test-repository-hygiene.ps1
git diff --check
graphify update .
```

The original unauthorized IDs are absent after the patch for every tested format/alias. ALL, empty exports, each participant kind, primary and secondary organizational membership, normal Russian/multiline/quoted CSV values and typed XML remain valid. Default missing-materialized-scope behavior in `MdScopeRepository` is unchanged; this patch does not redefine ADR-0013.

Initial-batch limits: no interactive Excel/LibreOffice testing or re-import guarantee; browser E2E/frontend/remote CI were not rerun in that batch. No commit/push/deploy was performed until the subsequent explicit publication request. Full release acceptance still requires target gates at the release SHA/topology. Next package: I-04, secret-safe idempotency, after its own boundary inspection and detailed plan.

## Publication follow-up — 2026-09-05

User requested commit/push/deploy, removal of obsolete branches and further work on `main`. Pre-publication verification on `main`:

- Native `mvn -B verify`: server 334 tests, zero failures/errors/skips (`output/main-publish-backend-verify.log`).
- Pinned Node 24.15.0: web 107 tests / 31 files, typecheck, production build, localization audit 1009 referenced / 1022 Russian keys (`output/main-publish-web-verify.log`). The first extra localization audit had an incomplete container mount; corrected runner mounting, not application source.
- All seven repository architecture/docs/release/backup/managed config commands and `bash scripts/prod/test-deploy-fail-closed.sh`: PASS.
- Gitleaks 8.28.0 full history: corrected the already-approved OTP test fixture fingerprint to its immutable historical `apps/instance` path; one prior finding became zero findings across 207 commits. No broad allow-list or rule suppression added.
- Chromium: initial run 23/24; another run exposed German language leaking from localization cleanup into three subsequent tests. `localization.spec.ts` now waits for the successful settings PATCH both when switching and restoring the language. Final E2E run: 24/24 in 51.8 seconds, unchanged timeouts, with config/typecheck/artifact-security also green (`output/main-publish-e2e.log`).
- Built candidate server/web images; refreshed web without cache to update curl/libcurl from `8.20.0-r0` to `8.22.0-r0`. Both images pass Trivy 0.74.0 HIGH/CRITICAL with the repository's `--ignore-unfixed` policy; not a claim of zero vulnerabilities of every severity.
- Disposable Compose used a separate database/storage, generated credentials and random ports. Its exact project resources were removed afterward; persistent application data were not used by E2E.

Graphify outputs, local audit drafts, test logs and tool downloads are excluded from publication. Actual pushed SHA, CI outcome and deployed IDs must be verified after publication; this section records pre-publication gates, not production acceptance.
