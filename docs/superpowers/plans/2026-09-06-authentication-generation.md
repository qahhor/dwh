# Authentication Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ordinary password changes and existing global access revocation safe against concurrent password changes and credential issuance, with real PostgreSQL and browser evidence.

**Architecture:** A user's monotonic authentication generation binds sessions, API tokens and OTP to the proof that issued them. Password CAS and the sole generation bump in the existing invalidation port share one transaction. The frontend ends the old login and requires an explicit new one.

**Tech Stack:** Java 25.0.2, Spring Boot/JdbcClient/Flyway, PostgreSQL 18, Angular 22, Node 24.15.0, Playwright 1.62.1; existing dependencies only.

**Spec:** `docs/superpowers/specs/2026-09-06-authentication-generation-design.md` (approved 2026-09-06).

## Global Constraints

- Сохраняются ADR-0014, граница `kauth → md` и правила миграций.
- Все новые production INSERT передают версию явно.
- Выдача не может повысить старое доказательство до новой версии: запрещено повторно читать текущую `auth_version` и подставлять её вместо исходной.
- Применяется `@Transactional` с обычным REQUIRED propagation. `REQUIRES_NEW` здесь запрещён.
- Смена пароля, блокировка и анонимизация вызывают этот порт ровно один раз в своей успешной транзакции. Разблокировка его не вызывает.
- Клиент продолжает передавать только `oldPassword` и `newPassword`.
- CSRF и permission interceptor остаются обязательными и не обходятся тестами.
- Каждая гонка воспроизводится на PostgreSQL 18 двумя настоящими транзакциями. Порядок задаётся latch/barrier с ограниченным timeout, а не `sleep`.
- Не писать исходные пароли, OTP, cookie, Bearer или hash в сообщения ошибок, логи и test artifacts.
- Существующий `localhost:4200`, его данные и образы не меняются без отдельного разрешения.
- H01 reset, unsuccessful-OTP attempt accounting, channel address revision binding, RBAC/rate-limit redesign, in-flight cancellation, push and deploy are outside this plan.
- Initial generation zero preserves existing credentials' validity flags; installation does not log out every user. Mixed old/new writers and unverified application-only rollback are unsupported.

## Workspace and verification

The user explicitly chose continued work on `main`; use `D:/Claude/dwh` in place, preserving unrelated dirty work. Starting HEAD is `2a59f680543eeef0233915ff0941bd67c6deb292`. A plan-only commit may precede Task 1. Never stage all files. Existing local password-change drafts belong to this feature, but `.gitignore`, both `.dockerignore`, `graphify-out/` and untracked `audit/` drafts do not belong to the implementation commits.

Run commands in PowerShell. Dependencies are already installed; do not upgrade them.

```powershell
$env:JAVA_HOME = 'C:/Tools/Java/jdk-25.0.2'
& 'D:/Claude/dwh/output/tools/maven/bin/mvn.cmd' -B -q verify
```

Focused Maven invocation uses `-pl apps/server -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=ClassName' test`. Frontend commands use `D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe` and project-local CLI paths. Keep test results and runtime artifacts out of Git. Known JNA/native/Testcontainers warnings must be recorded as existing warnings, not described as pristine output or repaired by an unrelated dependency upgrade.

Only one implementation agent works at a time. Each task owns a report containing commands, exit status, RED/GREEN evidence and file scope; the controller dispatches a separate spec-compliance and quality reviewer. Run `graphify update .` AST-only after code changes, leaving its dirty generated files uncommitted. Final review starts from `2a59f680543eeef0233915ff0941bd67c6deb292`, not `origin/main` (older unrelated local commits are outside this feature).

## File structure and review boundaries

Task 1 is the entire indivisible backend security protocol: schema, proof-bearing repository APIs, callers, filters and causal tests. Introducing a generation bump without version-bound writers would be unsafe, so these are not separate deployable tasks. Split new test files by responsibility to keep concurrency, migration and HTTP evidence readable. A shared test fixture may construct real services but may not add production-only testing hooks.

Task 2 accepts the already drafted frontend login lifecycle, adds permanent real-server browser regression, runs final acceptance and updates handoff/rollout guidance. The backend can be reviewed and tested without Task 2; the complete user workflow cannot be accepted until both pass.

### Task 1: Versioned backend authentication and transactional regression suite

**Files:**

- Create: `apps/server/src/main/resources/db/migration/V025__authentication_generation.sql` (recheck latest version before creation).
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdUserRepository.java`.
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java` and `UserSessionInvalidator.java` (contract documentation only for the port).
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/common/security/SecurityContext.java`.
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/repository/KauthSessionRepository.java`, `KauthApiTokenRepository.java`, `KauthOtpCodeRepository.java`.
- Create: `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/service/KauthCredentialGuard.java` (proof validity, no token generation).
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/service/KauthUserSessionInvalidator.java`, `KauthAuthService.java`, `KauthApiTokenService.java`, `KauthChannelService.java`.
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/KauthAuthenticationFilter.java`.
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/controller/KauthPasswordController.java`, `KauthApiTokenController.java`, `KauthChannelController.java`.
- Adopt/extend existing local test: `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/KauthPasswordChangeIntegrationTest.java`.
- Create: `apps/server/src/test/java/com/greenwhite/dwh/instance/db/AuthenticationGenerationMigrationTest.java`.
- Create: `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/AuthenticationGenerationConcurrencyTest.java`.
- Create: `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/AuthenticationGenerationHttpTest.java`.
- Create if shared setup is necessary: `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/AuthenticationGenerationFixture.java`.
- Adapt explicit constructor/signature fixtures in existing server tests including `KauthOtpLoginIntegrationTest`, `UserBlockingInvariantTest`, `SecurityConfigTest` and any compile-identified callers. Do not add unsafe production overloads just to preserve test constructors.

**Interfaces:**

- Consumes existing `UserSessionInvalidator.invalidateAllAccess(Long userId)` and real Spring transaction boundaries.
- Produces `UserRecord`, `SessionRecord`, `ApiTokenRecord`, `OtpRecord` with appended `long authenticationVersion` (internal JSON ignored).
- Produces principal with existing eight components followed by `long authenticationVersion, Long apiTokenId`. Cookie principals have non-null sessionId/null apiTokenId; Bearer principals have null sessionId/non-null apiTokenId. Existing seven/eight-argument compatibility constructors are replaced with explicit test-fixture values, not current-version lookup fallbacks.
- Produces `MdUserRepository.compareAndSetPassword(Long userId, long expectedAuthVersion, String expectedPasswordHash, String newPasswordHash): boolean` and `incrementAuthenticationVersion(Long userId): void` (require one changed row).
- Produces `MdUserService.changePassword(Long userId, long authenticatedVersion, String oldPassword, String newPassword): void`.
- Repository issuance signatures put version second: `create(Long userId, long authenticationVersion, ...)`, retaining remaining arguments and their types in current order. Return existing record type; missing conditional INSERT result throws invalid credentials. OTP callers map stale issuance to the existing appropriate authentication/OTP error.
- Session/token repositories expose `findActiveById(Long id): Optional<Record>` using the same active predicate as hash lookup.
- OTP repository exposes `consume(Long otpId, Long userId, long authenticationVersion, String purpose): boolean`; no unconditional successful claim remains in supported paths.
- `KauthCredentialGuard.requireCurrent(KauthPrincipal principal): KauthPrincipal` returns the original unchanged principal after validating source kind, ID, owner, active status and generation; invalid proof throws HTTP 401.
- `KauthApiTokenService.createToken(KauthPrincipal principal, String name, Instant expiresAt)` retains `CreatedTokenResult`; channel bind/confirm replace first `Long userId` argument with `KauthPrincipal principal`. Read/list/revoke methods retain current signatures and scope.
- Public HTTP routes, request DTOs, response fields and raw secret formats are unchanged; internal generation and principal proof identifiers are not newly published.

- [ ] **Step 1: Write migration behavior tests, run RED, add forward migration, run GREEN.**

  Use a disposable PostgreSQL 18 database, migrate to target `024`, seed active/closed sessions, valid/revoked/expired API tokens and login/channel OTP with active/used/expired cases. Snapshot representative user/business/audit fields. Assert `SchemaVersionGate` rejects this older schema when the new migration exists. Migrate the rest, check preserved data and validity flags, generation zero in all four tables, and that a second migrate applies zero migrations. A separate empty database must migrate successfully. Reject negative versions in all four tables.

```java
var before = jdbc.sql("select name from md_users where id = :id")
        .param("id", userId).query(String.class).single();
FlywayUtcConfiguration.configure(Flyway.configure()).dataSource(ds)
        .locations("classpath:db/migration").load().migrate();
assertThat(jdbc.sql("select auth_version from md_users where id = :id")
        .param("id", userId).query(Long.class).single()).isZero();
assertThat(jdbc.sql("select name from md_users where id = :id")
        .param("id", userId).query(String.class).single()).isEqualTo(before);
```

  Initial missing-column failure is the expected RED for this schema addition; record it before writing the migration. Use the current V001/V015 definitions for all mandatory fixture columns. SQL implementation:

```sql
alter table md_users add column auth_version bigint not null default 0
    check (auth_version >= 0);
alter table kauth_sessions add column auth_version bigint not null default 0
    check (auth_version >= 0);
alter table kauth_api_tokens add column auth_version bigint not null default 0
    check (auth_version >= 0);
alter table kauth_otp_codes add column auth_version bigint not null default 0
    check (auth_version >= 0);
```

- [ ] **Step 2: Write stale-credential repository tests, run RED, implement snapshot-bound storage, run GREEN.**

  Create credentials at generation zero; update only the user's generation to one, leaving physical rows active. Assert all three active lookups reject them, session list omits them, and explicit old-generation creation is rejected. A fresh generation-one credential must authenticate. Repeat predicates for passive users and existing expiration/revocation/used flags. Assert JSON serialization lacks `authenticationVersion` without changing previously public field names.

```java
assertThat(sessionRepository.findActiveByTokenHash(oldSessionHash)).isEmpty();
assertThat(apiTokenRepository.findActiveByTokenHash(oldApiHash)).isEmpty();
assertThat(otpCodeRepository.findActiveByTokenHash(oldOtpHash, "login")).isEmpty();
assertThat(sessionRepository.findActiveByUserId(userId)).isEmpty();
```

  First write these assertions against current create signatures; after observing RED, change the signatures and all callers to explicit versions. Factor each repository's mapping/projection/active predicate locally; do not duplicate entire row mappers for each lookup. Example session issuance:

```sql
insert into kauth_sessions
    (user_id, auth_version, token_hash, ip, user_agent, device_info, created_at, last_seen_at)
select u.id, :authenticationVersion, :tokenHash, cast(:ip as inet),
       :userAgent, :deviceInfo, now(), now()
from md_users u
where u.id = :userId and u.state = 'A' and u.auth_version = :authenticationVersion
returning id, user_id, auth_version, token_hash, host(ip) as ip_str,
          user_agent, device_info, created_at, last_seen_at, closed_at
```

  Token/OTP INSERT use the identical explicit snapshot condition and their own existing fields. Active selects join user by ID and require `u.state='A' AND u.auth_version=c.auth_version` plus their existing validity conditions; OTP also requires expiry in the future and purpose. Annotate internal record components with existing Jackson `JsonIgnore` compatible with the current Jackson stack.

- [ ] **Step 3: Write password conflict and rollback tests, run RED, implement CAS and sole bump, run GREEN.**

  Use real proxied `MdUserService` and JDBC repositories. Pause two threads after both read the same user proof/hash using a test-only delegating repository/hasher and bounded latches. Release both; one update succeeds and one throws `INVALID_CREDENTIALS`. Assert winner's password, one success audit and generation one. A stale principal with the winner's new correct password is still rejected. Extend existing forced/normal rollback tests to include generation and standalone proxied invalidator atomicity; inject failure after real revocation writes and after audit write.

```java
assertThat(outcomes.stream().filter(o -> o.succeeded()).count()).isEqualTo(1);
assertThat(outcomes.stream().filter(o -> o.invalidCredentials()).count()).isEqualTo(1);
assertThat(userRepository.findById(userId).orElseThrow().authenticationVersion()).isEqualTo(1L);
assertThat(passwordChangedAuditCount(userId)).isEqualTo(1L);
```

  Implement the repository conditional update (exact parameter bindings, no retry):

```sql
update md_users
set password_hash = :newPasswordHash, password_changed_at = now(),
    force_password_change = false, modified_at = now()
where id = :userId and state = 'A' and auth_version = :expectedAuthVersion
  and password_hash is not distinct from :expectedPasswordHash
```

  Service requires active user and matching authenticated generation before checking the old password and current validator; it hashes once, requires one CAS row, calls invalidator once and then existing success audit. CAS does not bump. `KauthUserSessionInvalidator` injects `MdUserRepository`, is REQUIRED transactional, increments generation first, closes sessions second, revokes API tokens third. Its increment uses `auth_version = auth_version + 1`; an absent row or overflow fails. Do not bulk-consume OTP. Preserve logout and single-credential revocation scope.

- [ ] **Step 4: Write issuance and OTP races, run RED, implement proof propagation/claim/guard, run GREEN.**

  Real `KauthAuthService` and real Spring transaction proxy are mandatory. Mock/fake only external send/index operations, capturing synthetic OTP in memory without logging it. Use a fixture-level delegating repository or hasher to pause after the proof is captured but before issuance. Each `Future.get` and latch await has a bounded timeout and cleanup releases latches/shuts down executor in finally. Do not insert production barriers or `sleep`.

  Cover both serial orders and the overlap order for password login, login-OTP creation, valid-OTP verification and API issuance from cookie and Bearer. After revocation commit, late attempts may fail or return only old-generation unusable records, never new valid proof. In the overlap assertion, a fresh lookup is required, not only generation inspection. Double correct OTP verification permits at most one successful claim/session. Session-insert failure rolls back OTP claim. Block/unblock and anonymization reject old session/token/OTP, preserve another user's access and complete without deadlock. Channel verification rejects old-generation OTP even from a new session, wrong-owner/purpose proofs, and accepts a newly issued correct code.

```java
assertThat(proofCaptured.await(10, TimeUnit.SECONDS)).isTrue();
userService.changePassword(userId, originalVersion, originalPassword, replacementPassword);
continueIssuance.countDown();
var outcome = future.get(20, TimeUnit.SECONDS);
if (outcome.rawSessionCookie() != null) {
    assertThat(sessionRepository.findActiveByTokenHash(
            KauthPasswordHasher.sha256(outcome.rawSessionCookie()))).isEmpty();
}
```

  `outcome` in each concrete fixture represents either the returned credential or the expected ApiException; assert its allowed status and do not swallow arbitrary exceptions. Keep exact latches local to the behavior being tested.

  Login passes `user.authenticationVersion()` read with the password hash. OTP verification passes `otp.authenticationVersion()`, checks current user agrees and uses conditional consume before INSERT in the same transaction. Conditional consume SQL:

```sql
update kauth_otp_codes o set is_used = true
where o.id = :otpId and o.user_id = :userId and o.purpose = :purpose
  and o.auth_version = :authenticationVersion and not o.is_used
  and o.attempts_left > 0 and o.expires_at > now()
  and exists (select 1 from md_users u where u.id = o.user_id
              and u.state = 'A' and u.auth_version = o.auth_version)
```

  A false consume result gives existing `OTP_INVALID`. API creation and channel operations use `KauthCredentialGuard`, comparing the original proof's ID/owner/generation against the corresponding active repository record. They never replace the principal version with a newer user version. Guard depends directly on repositories, avoiding circular API service dependencies. Filter constructs principal from the credential version/ID and rejects a later user read with a different version. Controllers take scalar password proof and principal token/channel proof from `SecurityContext`, never DTO fields.

- [ ] **Step 5: Write and run full-chain HTTP acceptance, then run all backend gates and commit only backend files.**

  Use actual Spring Security filter chain, permission interceptor, real auth/password services and disposable PostgreSQL. Existing standalone MockMvc password tests remain useful but do not alone satisfy this step. Cover both URL aliases, forced and normal users, cookie with real CSRF handshake, cookie without CSRF (403), anonymous (401), Bearer password change, old cookie/API 401 after commit, new-password login success, old-password login failure, password policy 422 and wrong-current password 401. Assert no internal generation in JSON. Existing permission tests must still pass.

```java
mockMvc.perform(post("/api/v1/auth/password")
        .cookie(sessionCookie, csrfCookie).header("X-XSRF-TOKEN", csrfValue)
        .contentType(MediaType.APPLICATION_JSON).content(passwordBody))
        .andExpect(status().isNoContent());
mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
        .andExpect(status().isUnauthorized());
```

  Construct `passwordBody` from synthetic fixtures without logging content. A real bootstrap/login/CSRF response supplies the cookies, not an authentication mock. Run focused classes as each RED/GREEN step evolves, then once run full Maven `verify`, `scripts/architecture/test-unified-boundaries.ps1` and `git diff --check`. Inspect Surefire totals and report unchanged warnings separately. Run Graphify AST update, do not stage its output.

```powershell
git add -- apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdUserRepository.java apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java
```

  Add the other exact changed backend paths listed above plus explicitly inspected compile-adapted test files (no directory-wide staging, no frontend/i18n/audit/ignore files). Inspect `git diff --cached --stat` and `git diff --cached --check`, then commit `fix(auth): bind access to authentication generations`. Report every criterion from spec section 11 with test name and result. Review must pass before Task 2.

### Task 2: Explicit re-login, permanent browser regression and final acceptance handoff

**Files:**

- Adopt/review local edits: `apps/web/src/app/core/services/auth.service.ts`, `apps/web/src/app/features/auth/login/login.component.ts`, `apps/web/src/app/features/iam/profile/profile.component.ts`, `apps/web/src/app/app.component.ts`, `apps/web/src/app/layout/app-shell/app-shell.component.ts`.
- Adopt/review local tests: `apps/web/src/app/features/auth/login/password-change.spec.ts`, `apps/web/src/app/app.component.spec.ts`.
- Adopt localization: `apps/server/src/main/resources/i18n/ru.json`, generated `apps/web/src/app/core/i18n/packaged-russian.ts`.
- Adopt/adapt: `e2e/support/auth.ts`.
- Create: `e2e/tests/browser/instance/password-change.spec.ts`.
- Modify only if necessary for secret-safe synthetic-user setup/relogin: existing `e2e/support` auth/API helpers. No production test hooks.
- Modify: `docs/ai-context.md`, `docs/ops/rollback.md`, `docs/superpowers/specs/2026-09-06-authentication-generation-design.md` (status/evidence, not expanded requirements).
- Record the point-by-point acceptance matrix in the task's internal implementer report; keep screenshots and any browser artifacts outside the repository. Do not add a standalone committed QA report.

**Interfaces:**

- Consumes Task 1's unchanged password/login/token HTTP DTOs and version-enforced credential checks; database generation is never exposed to UI.
- Produces `AuthService.onPasswordChanged(): void` clearing current user/permissions, invalidating pending session refresh results, showing localized re-login success and navigating to `/login` with replacement.
- Existing `checkSession()` emits `null` for a stale pre-change response; stale `refreshMe()` response must not emit a user or restore state. Root hosts exactly one notification container, including on `/login`.
- Existing `loginToInstance(page: Page): Promise<void>` explicitly submits credentials a second time after successful mandatory change. It must not mistake an error toast for successful login.
- New E2E cases use dedicated synthetic non-admin accounts where possible; changing the suite bootstrap admin password must preserve the helper's known rotated-password convention. No secrets in screenshots, attachments, tracing or log output.

- [ ] **Step 1: Verify/adopt existing TDD-proven UI behavior and add a failing regression only for any newly discovered defect.**

  Existing drafts already have recorded RED/GREEN evidence from the earlier increment; do not delete or reimplement them to manufacture a second RED. Freshly run the two focused specs and inspect the actual changes against the contract. The tests must assert forced/profile success clears secrets/auth/permissions and requires explicit login, error keeps editable draft, stale checkSession returns null, stale refreshMe cannot emit user, and the root has one toast host.

```typescript
expect(authService.currentUser()).toBeNull();
expect(authService.isAuthenticated()).toBe(false);
expect(router.navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });
```

  These are the existing public signal names. For a new failure: assert the consumer-visible break, run RED, minimally fix, rerun focused GREEN. No automatic `/me` retry or hidden new login after the password endpoint revokes the cookie. Use `auth.change_password` and `auth.password_changed_sign_in_again` from Russian source catalog; synchronize generated fallback with the existing script.

- [ ] **Step 2: Add permanent real-server browser regressions and run them against an isolated candidate.**

  Inspect existing E2E API/secret helpers and reuse them. Exercise bootstrap mandatory password change plus explicit second login; dedicated user's profile change plus return to login and explicit second login; wrong current password/policy rejection followed by successful retry; old session and API token rejection after success. Obtain at least two sessions and two API tokens before normal profile change, and verify all are rejected after commit using fresh API requests. Verify another synthetic user remains authenticated. Avoid snapshot mocks for these security assertions.

```typescript
await page.getByRole('button', { name: 'Сменить пароль', exact: true }).click();
await expect(page.getByText('Пароль изменён. Войдите снова с новым паролем.', { exact: true })).toBeVisible();
await expect(page).toHaveURL(/\/login(?:\?.*)?$/u);
await expect(page.getByLabel('Пароль', { exact: true })).toHaveValue('');
expect((await oldSessionContext.get('/api/v1/auth/me')).status()).toBe(401);
```

  `oldSessionContext` is a retained authenticated APIRequestContext from real login, not a fake response. Use `fillSecret`/`clearSecret` in try/finally for password fields. Never add authentication headers or request bodies to failure attachments. Read `e2e/playwright.config.ts` and `.env` loader implementation without printing private `.env` contents; supply all runtime credentials via fresh synthetic environment.

  Build candidate images from committed Task 1 backend plus current feature frontend, using unique local tags and an external Compose file with explicit synthetic env/no inherited project `.env`. Start fresh PostgreSQL/Typesense/application services on a verified free loopback-only port, distinct Compose project and volumes. Do not modify old candidate or live4200 containers/images/volumes. Run explicit migrate before server readiness. External runtime/test artifact directories are allowed; create configs with `apply_patch`, start background helpers hidden on Windows, and redact all secrets from reported output. Render desktop and mobile, verify no horizontal overflow or console/runtime overlay, and capture only cleared-password screens. Read frontend-testing-debugging and applicable browser/Playwright guidance before UI testing.

- [ ] **Step 3: Run full local gates and resolve feature regressions with TDD.**

  Run Angular full tests, app typecheck, i18n audit, production build; E2E typecheck/config/artifact-security; full Playwright instance suite on the isolated real candidate. Task 1 Maven results remain valid only if no backend code changed; final controller verification may rerun full Maven on the final tree. If full E2E finds unrelated pre-existing failures, preserve the failure evidence, run the exact focused auth regression independently, and report partial whole-suite status truthfully. Do not expand to unrelated fixes.

```powershell
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/@angular/cli/bin/ng.js test --watch=false
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/typescript/bin/tsc --noEmit -p tsconfig.app.json
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' scripts/localization-audit.mjs
& 'D:/Claude/dwh/output/tools/node-v24.15.0-win-x64/node.exe' node_modules/@angular/cli/bin/ng.js build --configuration production
```

  These commands run from `apps/web`. E2E commands run from `e2e` using the corresponding local TypeScript/Playwright CLI and the explicit isolated environment. Execute public-docs, repository-hygiene and architecture boundary gates from root. Run `git diff --check`, Graphify AST update and inspect scoped diff; do not commit dirty Graphify output.

- [ ] **Step 4: Publish only local truthful handoff documentation, commit scoped files, and request final review.**

  The internal implementer report maps spec sections 1–12 and each section-11 row to implementation/test evidence and an honest status. Record migration number, exact tested commit/tree identity, runtime environment, counts, known unrelated failures/warnings, desktop/mobile screenshots' external paths, no push/deploy/live4200 changes, and remaining H01/OTP/non-goals. Keep browser artifacts outside the repository; no standalone committed QA report is requested. No local audit drafts become requirements or evidence. Update design status to reflect actual accepted scope only.

  Add rollback guidance explicitly: drain old writers before activating this version; after generations have advanced, application-only rollback to an old writer is not validated; use a tested compatible forward fix or separately authorized restore. Do not instruct automatic global logout or destructive reset. Handoff `docs/ai-context.md` leads with this current package's verified result without overwriting historical evidence.

```powershell
git add -- apps/web/src/app/core/services/auth.service.ts apps/web/src/app/features/auth/login/login.component.ts apps/web/src/app/features/iam/profile/profile.component.ts
```

  Stage remaining exact UI/E2E/i18n/docs paths after inspection, never whole directories or unrelated dirty files. Run cached whitespace/stat checks and commit `test(auth): verify explicit re-login and versioned access acceptance`. Controller reviews the task package and then the complete feature range. If final review finds issues, one grouped fix wave and one scoped re-review precede final handoff; residual blockers are stated, not declared resolved.

## Coverage self-check

| Spec requirement | Implementation and evidence |
|---|---|
| Sections 1–4 scope, original proof, concurrency guarantee | Task 1 steps 2–5; Task 2 report limitations |
| Section 5 schema/private fields/backfill | Task 1 steps 1–2, HTTP JSON check |
| Section 6 sole REQUIRED bump/block/anonymize/no logout scope expansion | Task 1 steps 3–4 |
| Section 7 password CAS/principal/version/atomic audit | Task 1 steps 3 and 5 |
| Section 8.1 login and login OTP snapshot | Task 1 steps 2 and 4 |
| Section 8.2 atomic OTP/channel verification | Task 1 step 4 |
| Section 8.3 cookie/Bearer proof and API issuance | Task 1 steps 2, 4 and 5 |
| Section 8.4 active filter/list/no version upgrade | Task 1 steps 2, 4 and 5 |
| Sections 9–10 secrets/UI/CSRF/rollout/rollback | Task 1 step 5; Task 2 steps 1–4 |
| Section 11 every concurrency/migration/HTTP/browser row | Task 1 named regressions; Task 2 real-server E2E and final matrix |
| Section 12 approved implementation and reviewed handoff | Both task reviews plus whole-feature final review |

No further design choice is required to start: the user approved this approach and asked to go through every point and fix as needed. Implementation-specific rulings stay inside this scope and are recorded in the SDD ledger; security scope expansion, destructive operations and publication require separate authority.
