# Centralized Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all current SmartupCMS interface copy centrally editable and make RU, UZ, EN, KK, KY, TG, DE and TR work consistently for every user.

**Architecture:** The `md` module serves bundled classpath JSON dictionaries merged with PostgreSQL overrides. Angular loads the authoritative language registry and dictionaries through REST, stores only the selected-code hint locally, and renders every static string through the reactive translation service.

**Tech Stack:** Java 25, Spring Boot 4, JdbcClient, PostgreSQL 18, Flyway, Angular 22 signals, Vitest, Playwright 1.62.

**Spec:** `docs/superpowers/specs/2026-09-04-centralized-localization-design.md`

## Global Constraints

- Russian is the mandatory source language and universal fallback.
- Standard languages are exactly `ru`, `uz`, `en`, `kk`, `ky`, `tg`, `de`, `tr`.
- Incomplete custom languages remain selectable and fall back per key to Russian.
- User-created content is never translated.
- No new deployable service or distributed cache is introduced.
- Mutation endpoints require `platform.settings:update`; editor reads require `platform.settings:view`.
- Existing unrelated working-tree changes must be preserved.
- Do not commit, push or deploy unless the user explicitly requests it.

---

## File map

**Server resources and persistence**

- Create `apps/server/src/main/resources/db/migration/V022__centralized_localization.sql`: language registry and override tables plus eight seeded languages.
- Create `apps/server/src/main/resources/i18n/{ru,uz,en,kk,ky,tg,de,tr}.json`: versioned distribution catalogs.
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/md/i18n/I18nModels.java`: internal/REST records for language summaries, editor rows and batch commands.
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdI18nRepository.java`: set-based registry and override queries.
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdI18nCatalog.java`: validated classpath catalog loading and canonical Russian key set.
- Create `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdI18nService.java`: merge, fallback, coverage, optimistic writes, cache invalidation and audit.
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdI18nController.java`: public reads and permission-protected admin endpoints.
- Modify `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/SecurityConfig.java`: expose only the two dictionary read patterns before login.

**Frontend localization core**

- Create `apps/web/src/app/core/models/i18n.models.ts`: API/editor DTOs.
- Rewrite `apps/web/src/app/core/services/i18n.service.ts`: async registry/dictionary state, persistence and translation parameters.
- Modify `apps/web/src/main.ts`: initialize Russian and selected dictionaries before rendering.
- Modify `apps/web/src/app/core/services/auth.service.ts`: reconcile authenticated `user.language` with the startup hint.
- Modify `apps/web/src/app/core/services/api.service.ts`: translate stable Problem Detail codes while keeping Russian detail fallback.
- Modify `apps/web/src/app/layout/app-shell/app-shell.component.ts`: dynamic dropdown and persisted selection.

**Administration UI**

- Create `apps/web/src/app/features/settings/language-editor.component.ts`: accessible editor and legacy import.
- Modify `apps/web/src/app/features/settings/settings.component.ts`: registry table, coverage, dynamic settings options and editor orchestration.
- Modify `apps/web/src/app/features/iam/users/users.component.ts`: dynamic language options.

**Static-copy migration**

- Modify `apps/web/src/app/app.component.ts`, all components under `features/`, `layout/`, and relevant `shared/ui/` controls to use `TranslatePipe` or `I18nService.translate` for static copy.
- Add/modify colocated `*.spec.ts` files so tests assert semantic translated output rather than hard-coded language implementation details.

**Verification and documentation**

- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nCatalogTest.java`.
- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nServiceIntegrationTest.java`.
- Create `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nControllerIntegrationTest.java`.
- Create `apps/web/src/app/core/services/i18n.service.spec.ts`.
- Create `apps/web/src/app/features/settings/language-editor.component.spec.ts`.
- Create `e2e/tests/browser/instance/localization.spec.ts`.
- Modify `docs/technical-specification.md`, `docs/README.md`, and `docs/ai-context.md` with the verified final behavior and commands.

---

### Task 1: Persist the language registry and overrides

**Files:**
- Create: `apps/server/src/main/resources/db/migration/V022__centralized_localization.sql`
- Create: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/i18n/I18nModels.java`
- Create: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdI18nRepository.java`
- Test: `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nRepositoryIntegrationTest.java`

**Interfaces:**
- Produces `LanguageRecord`, `TranslationOverride`, `LanguageSummary`, `TranslationEntry`, `TranslationEditor`, `CreateLanguageRequest`, and `UpdateTranslationsRequest` records in `I18nModels`.
- Produces set-based repository methods `findLanguages(boolean activeOnly)`, `findLanguage(String code)`, `findOverrides(String code)`, `insertLanguage(...)`, and `replaceOverrides(..., long expectedRevision, long userId)`.

- [ ] Write a PostgreSQL integration test that applies Flyway, asserts the eight seeded rows, inserts two overrides in one batch, reads them with one set query, and proves a stale revision changes no rows.
- [ ] Run `mvn -pl apps/server -am -Dtest=MdI18nRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` and verify the test fails because the migration/repository do not exist.
- [ ] Add `V022` with lowercase-code and nonblank-name checks, FK/audit columns, composite override PK, revision and modified-time indexes.
- [ ] Implement immutable Java records with Jakarta validation: language code `^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$`, name 1–100 characters, values at most 4000 characters, and non-null `expectedRevision`.
- [ ] Implement repository writes as one transaction and use `update ... where revision = :expectedRevision returning revision`; throw the project conflict exception when no revision is returned.
- [ ] Re-run the targeted test and verify it passes.

### Task 2: Load and contract-test the eight bundled catalogs

**Files:**
- Create: `apps/server/src/main/resources/i18n/ru.json`
- Create: `apps/server/src/main/resources/i18n/uz.json`
- Create: `apps/server/src/main/resources/i18n/en.json`
- Create: `apps/server/src/main/resources/i18n/kk.json`
- Create: `apps/server/src/main/resources/i18n/ky.json`
- Create: `apps/server/src/main/resources/i18n/tg.json`
- Create: `apps/server/src/main/resources/i18n/de.json`
- Create: `apps/server/src/main/resources/i18n/tr.json`
- Create: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdI18nCatalog.java`
- Test: `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nCatalogTest.java`

**Interfaces:**
- Produces `Set<String> russianKeys()`, `Map<String,String> bundled(String code)`, `boolean isBundled(String code)`, and `Set<String> bundledCodes()`.
- Catalog values are flat JSON objects of string keys to non-empty strings.

- [ ] Write a test loading all eight resources and asserting their code set, exact key equality with Russian, nonblank values, unique keys, and absence of HTML tags.
- [ ] Run `mvn -pl apps/server -am -Dtest=MdI18nCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test` and verify missing resources fail.
- [ ] Extract every existing `DICTIONARIES.ru` key and every remaining current static Angular string into stable domain keys such as `tasks.list.title`, `users.form.language`, and `common.state.empty`.
- [ ] Build the complete Russian resource first and review key namespaces for duplicates or component-specific leakage.
- [ ] Add complete UZ, EN, KK, KY, TG, DE and TR resources with exactly the Russian key set.
- [ ] Implement `MdI18nCatalog` with Jackson duplicate-key detection, UTF-8 loading, immutable maps and fail-fast startup validation.
- [ ] Re-run the contract test and verify all eight catalogs pass.

### Task 3: Implement merge, fallback, coverage, audit and cache behavior

**Files:**
- Create: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdI18nService.java`
- Test: `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nServiceIntegrationTest.java`

**Interfaces:**
- Consumes `MdI18nCatalog` and `MdI18nRepository` from Tasks 1–2.
- Produces `listLanguages(boolean activeOnly)`, `effectiveDictionary(String code)`, `editor(String code)`, `createLanguage(CreateLanguageRequest,long)`, and `updateTranslations(String,UpdateTranslationsRequest,long)`.

- [ ] Write failing tests for precedence `target override → target bundle → RU override → RU bundle → key`, inactive/unknown-language RU fallback, coverage, custom-language creation, required Russian values, unknown keys, atomic rejection, revision conflict, audit output and cache invalidation.
- [ ] Run the targeted service integration test and record the expected missing-service failure.
- [ ] Implement normalized code handling, bulk dictionary assembly and coverage calculation without per-key SQL.
- [ ] Implement a revision-keyed `ConcurrentHashMap` cache and invalidate the target entry; a Russian mutation clears every merged entry.
- [ ] Implement transactional create/update methods and `AuditLogService.logChange` calls using row keys `languageCode:translationKey`.
- [ ] Re-run service and catalog tests and verify they pass.

### Task 4: Expose safe public reads and protected admin writes

**Files:**
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdI18nController.java`
- Modify: `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/SecurityConfig.java`
- Test: `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdI18nControllerIntegrationTest.java`
- Test: `apps/server/src/test/java/com/greenwhite/dwh/instance/md/RbacSystemRolesIntegrationTest.java`

**Interfaces:**
- `GET /api/v1/i18n/languages` returns `List<LanguageSummary>`.
- `GET /api/v1/i18n/{code}` returns the effective flat `Map<String,String>`.
- `GET /api/v1/i18n/admin/languages/{code}/translations` returns `TranslationEditor`.
- `POST /api/v1/i18n/admin/languages` accepts `CreateLanguageRequest`.
- `PUT /api/v1/i18n/admin/languages/{code}/translations` accepts `UpdateTranslationsRequest`.
- `GET /api/v1/i18n/admin/languages/{code}/export` returns JSON attachment content.

- [ ] Write MockMvc/integration tests proving anonymous dictionary reads, authenticated editor view permission, update permission, CSRF enforcement, 400 validation, 404 language, 409 revision conflict and unchanged compatibility response for `/i18n/ru`.
- [ ] Run the controller tests and verify the new paths fail.
- [ ] Replace the static controller map with `MdI18nService`, annotate admin methods with existing settings permissions, and add stable Problem Detail mappings using existing exception conventions.
- [ ] Add only `/api/v1/i18n/languages` and `/api/v1/i18n/*` read requests to public security matching; do not expose `/api/v1/i18n/admin/**`.
- [ ] Update the RBAC coverage assertion for the now permission-bearing controller methods.
- [ ] Re-run the targeted tests and the full server suite.

### Task 5: Replace the browser-only localization service

**Files:**
- Create: `apps/web/src/app/core/models/i18n.models.ts`
- Rewrite: `apps/web/src/app/core/services/i18n.service.ts`
- Create: `apps/web/src/app/core/services/i18n.service.spec.ts`
- Modify: `apps/web/src/main.ts`
- Modify: `apps/web/src/app/core/services/auth.service.ts`

**Interfaces:**
- `initialize(): Promise<void>` loads languages, Russian, and the saved-code hint.
- `setLanguage(code: string, persist = true): Observable<void>` loads/caches a dictionary, updates signals, and persists `/settings/user` when authenticated.
- `refreshLanguage(code: string): Observable<void>` invalidates then reloads a dictionary.
- `translate(key: string, params?: Record<string,string|number>): string` resolves reactive values and `{name}` parameters.
- Signals: `languages`, `currentLang`, `isLoading`, `loadError`, and computed `currentLanguage`.

- [ ] Write Vitest cases for initialization, unavailable saved code fallback, per-key Russian fallback, cached switching, concurrent request deduplication, authenticated preference reconciliation, persistence failure rollback, live refresh and safe interpolation.
- [ ] Run `npm test -- --include src/app/core/services/i18n.service.spec.ts` from `apps/web` and verify failures against the current synchronous service.
- [ ] Add DTOs and implement API-backed signals with a single in-flight request per code; retain only `dwh_lang` and stop reading/writing `dwh_custom_languages` as authoritative data.
- [ ] Register `initialize()` with Angular's application initializer and keep a minimal Russian technical loading fallback if the public endpoint is unavailable.
- [ ] Reconcile the authenticated user's language after `checkSession()` succeeds without creating an AuthService↔I18nService dependency cycle.
- [ ] Re-run targeted tests and `npm run typecheck`.

### Task 6: Make every language selectable in shared flows

**Files:**
- Modify: `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- Modify: `apps/web/src/app/layout/app-shell/app-shell.component.spec.ts`
- Modify: `apps/web/src/app/features/settings/settings.component.ts`
- Modify: `apps/web/src/app/features/settings/settings.component.spec.ts`
- Modify: `apps/web/src/app/features/iam/users/users.component.ts`
- Modify: `apps/web/src/app/features/iam/users/users.component.spec.ts`

**Interfaces:**
- Consumes the `I18nService` signals/methods from Task 5.
- Produces one accessible language combobox shared by the shell and dynamic options in system/user forms.

- [ ] Add failing tests with five or more languages proving the shell exposes all active values, settings defaults and preferences contain dynamic options, user create/edit forms contain the same registry, and selection persists through the service.
- [ ] Run the three targeted component specs and verify current hard-coded three-language assertions fail.
- [ ] Replace `.slice(0, 3)` buttons with a labelled select/menu that exposes every active language and loading/error states.
- [ ] Replace hard-coded language `<option>` elements in Settings and Users with `*ngFor`/`@for` over the registry.
- [ ] Re-run targeted tests and keyboard-check the selector in Chrome.

### Task 7: Build the translation editor and legacy import

**Files:**
- Create: `apps/web/src/app/features/settings/language-editor.component.ts`
- Create: `apps/web/src/app/features/settings/language-editor.component.spec.ts`
- Modify: `apps/web/src/app/features/settings/settings.component.ts`
- Modify: `apps/web/src/app/features/settings/settings.component.spec.ts`

**Interfaces:**
- Inputs: `languageCode` and `open`.
- Outputs: `closed` and `saved(code)`.
- Uses admin endpoints from Task 4 and `I18nService.refreshLanguage(code)` from Task 5.

- [ ] Write component tests for source/target rendering, Russian mode, search, missing-only filter, coverage, dirty count, blank-Russian validation, Save/Cancel, reset, import validation, 409 preservation, unload confirmation and successful active-language repaint.
- [ ] Run the editor spec and verify it fails because the component is absent.
- [ ] Implement the editor with signal-based immutable row state, explicit accessible labels, sticky actions and responsive table/card CSS using existing theme tokens.
- [ ] Change the language list to display coverage and provide Edit, Export and Switch actions; keep Add Language but submit to the server.
- [ ] Implement legacy detection without mutation; after explicit confirmation, validate and upload non-empty known entries, then remove `dwh_custom_languages` only on success.
- [ ] Re-run editor/settings specs, typecheck and production build.

### Task 8: Localize all current static Angular copy

**Files:**
- Modify: `apps/web/src/app/app.component.ts`
- Modify: every `*.component.ts` under `apps/web/src/app/features`, `apps/web/src/app/layout`, and user-facing `apps/web/src/app/shared/ui`
- Modify: affected colocated component specs
- Modify: the eight catalog JSON files created in Task 2 whenever migration finds an uncovered key

**Interfaces:**
- Consumes `TranslatePipe` and `I18nService.translate(key, params)`.
- Preserves dynamic/user-provided values without translation.

- [ ] Add a static-copy guard test/script that parses component templates and fails on user-facing Cyrillic text/attributes unless the occurrence is an allowed dynamic example or technical diagnostic.
- [ ] Run the guard and capture the initial failing file list.
- [ ] Migrate shell, login and shared primitives first; add namespaced keys to all eight catalogs and keep the catalog contract green.
- [ ] Migrate Settings, Users, Roles, Custom Fields and Profile; update tests after each component.
- [ ] Migrate Tasks, Projects, Files, Notifications and Announcements; update tests after each component.
- [ ] Migrate Analytics, Audit, System and Command Palette; update tests after each component.
- [ ] Map known Problem Detail `code` values and client validation messages to catalog keys while preserving unknown Russian details.
- [ ] Re-run the static-copy guard, catalog contract, all Angular tests and typecheck until no undocumented static copy remains.

### Task 9: Prove cross-session behavior with Playwright and Docker

**Files:**
- Create: `e2e/tests/browser/instance/localization.spec.ts`
- Modify: `e2e/README.md`

**Interfaces:**
- Uses existing authenticated instance fixtures and security-safe artifact helpers.

- [ ] Write E2E tests that edit a non-Russian translation as admin, observe live repaint, open a second browser context, reload and observe the same value, then restore the original value in `finally`.
- [ ] Add an incomplete custom language, verify translated and Russian-fallback keys on two routes, and clean up only test-created data through supported APIs.
- [ ] Prove a user without `platform.settings:update` receives 403 and the UI has no editing controls.
- [ ] Run `npm run typecheck` and `npm run test:instance -- localization.spec.ts` from `e2e`.
- [ ] Restart the Docker application without deleting volumes and rerun the persistence assertion.
- [ ] Run the complete existing E2E instance project.

### Task 10: Final verification and canonical documentation

**Files:**
- Modify: `docs/technical-specification.md`
- Modify: `docs/README.md`
- Modify: `docs/ai-context.md`
- Modify: `graphify-out/*` through the Graphify updater

**Interfaces:**
- Documents only behavior verified by the preceding tests; no aspirational claims.

- [ ] Add localization functional requirements, permission matrix, fallback rules, API contracts, migration/rollback notes and operator troubleshooting to the canonical specification.
- [ ] Add developer instructions for creating a key and keeping all eight catalog resources in parity.
- [ ] Run `mvn test`, Angular `npm test`, `npm run typecheck`, production builds, E2E configuration/security checks and targeted localization E2E.
- [ ] Run `docker compose config`, rebuild from scratch while preserving the intended data volume, and verify health plus localization persistence.
- [ ] Run `graphify update .` and inspect the resulting scoped query for the new localization path.
- [ ] Review `git diff`, confirm unrelated changes remain intact, and report exact tests, known linguistic-review limitation and uncommitted file list.
