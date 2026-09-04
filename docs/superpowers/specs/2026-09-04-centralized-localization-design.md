# Centralized Localization Design

**Status:** Approved on 2026-09-04

## Problem

SmartupCMS advertises eight interface languages, but only Russian, Uzbek and
English contain bundled translations. The remaining entries are registered in
the Angular service with empty dictionaries, so selecting them renders the
Russian fallback. Custom dictionaries are stored only in browser
`localStorage`, the top-bar selector displays only the first three languages,
and the settings page has no translation editor. Most current Angular views
also contain hard-coded Russian strings and therefore cannot react to language
changes.

## Product decisions

- Russian is the mandatory source language and universal fallback.
- Administrators may edit Russian wording, but required Russian values cannot
  be empty.
- Languages and overrides are instance-wide, stored centrally, and visible to
  every user and device.
- Translation changes become active immediately after an explicit Save.
- Incomplete languages may be selected; missing values fall back to Russian.
- The standard distribution includes `ru`, `uz`, `en`, `kk`, `ky`, `tg`, `de`
  and `tr` with complete catalogs for all current static interface keys.
- All static interface copy belongs to the catalog. User-created content such
  as task titles, project names and comments is not translated.

## Architecture

Localization remains in the existing `md` bounded context of the modular
monolith. No new deployable service, message broker or distributed cache is
introduced.

The server owns the authoritative language registry and effective dictionary.
Versioned JSON resources provide the distribution defaults. PostgreSQL stores
language metadata and administrator overrides. This split allows an upgrade to
add or improve bundled strings without overwriting instance-specific wording.

The effective value precedence is:

1. selected-language database override;
2. selected-language bundled value;
3. Russian database override;
4. Russian bundled value;
5. the technical message key.

The Angular `I18nService` holds only runtime state and in-memory dictionaries.
`localStorage` may retain the last selected language code as a startup hint but
must not be an authoritative store for languages or translations. The saved
user preference in `user.language` is authoritative after authentication.

## Persistence

### `md_i18n_languages`

- `code text primary key`, normalized lowercase BCP-47;
- `name text not null`;
- `is_builtin boolean not null`;
- `is_active boolean not null`;
- `revision bigint not null` for optimistic locking;
- `created_by`, `modified_by` references to `md_users`;
- `created_at`, `modified_at` timestamps.

### `md_i18n_translation_overrides`

- `language_code` foreign key to `md_i18n_languages`;
- `translation_key text`;
- `value text not null`;
- `modified_by` reference to `md_users`;
- `modified_at` timestamp;
- composite primary key `(language_code, translation_key)`.

Flyway seeds the eight built-in language records. Bundled text stays in
classpath JSON resources; the overrides table contains only administrator
changes. For a custom language, every translated value is necessarily an
override because no bundled resource exists.

## API

Read endpoints are safe before authentication so that the login route can be
localized:

- `GET /api/v1/i18n/languages` returns active languages, type, revision and
  coverage.
- `GET /api/v1/i18n/{code}` preserves the current endpoint shape and returns
  the effective flat dictionary.

Administrative endpoints require the existing settings permissions:

- `GET /api/v1/i18n/admin/languages/{code}/translations` requires
  `platform.settings:view` and returns the editor model.
- `POST /api/v1/i18n/admin/languages` requires `platform.settings:update` and
  creates a custom language.
- `PUT /api/v1/i18n/admin/languages/{code}/translations` requires
  `platform.settings:update` and atomically applies a batch against an expected
  revision.
- `GET /api/v1/i18n/admin/languages/{code}/export` requires
  `platform.settings:view` and returns the effective JSON dictionary.

The editor response contains the language metadata, current revision, totals,
and entries with `key`, `russianValue`, `bundledValue`, `overrideValue`,
`effectiveValue`, and `isTranslated`. Clearing a non-Russian override restores
the bundled value or Russian fallback. Clearing a required Russian value is
rejected.

An outdated `expectedRevision` returns HTTP 409 without applying any rows.
Invalid codes, unknown keys, non-string values, oversized values, duplicate
language codes and malformed JSON use stable Problem Detail codes.

## Caching and consistency

The server caches merged dictionaries by language and revision in process
memory. Successful mutation invalidates the affected language and Russian
dependents. A single dictionary is assembled with one override query; no
per-key queries are permitted.

The Angular service caches loaded dictionaries in memory. A switch to an
already loaded revision is immediate. Saving translations invalidates and
reloads the affected dictionary; when it is the active language, the current
screen updates reactively without navigation or a full reload.

At the expected load of approximately 100 concurrent users, Redis is not
needed. The warm-server target for dictionary reads is p95 below 100 ms.

## Frontend experience

The languages table displays code, name, built-in/custom type, state,
translated count, total count and coverage. Actions are Edit translations,
Export JSON and Switch. The top bar uses an accessible dropdown containing all
active languages instead of three hard-coded buttons. Settings and user forms
consume the same dynamic language list.

The editor is a full-width state inside Settings. It provides language choice,
search across key/Russian/target values, a missing-only filter, completion
statistics, row editing, reset-to-bundled behavior, JSON import/export, a sticky
dirty-count action bar, Save and Cancel. Navigation with unsaved values requires
confirmation. A 409 response keeps local edits and shows a conflict message.

For non-Russian languages, each row displays the Russian source and editable
target. For Russian, each row displays the bundled Russian value and editable
effective value. Desktop uses a table; narrow screens use labelled rows/cards.
All controls must be keyboard accessible and have programmatic labels.

## Static copy migration

Every user-facing static string in current Angular pages moves to a stable,
namespaced catalog key. This includes headings, labels, buttons, hints, loading,
empty and error states, tooltips, dialog text, and accessibility labels.
Generated/user data is excluded. API errors use stable server codes mapped to
catalog keys; an unknown server detail remains visible in Russian as fallback.

The Russian catalog defines the exact canonical key set. Contract tests require
all eight bundled catalogs to contain that same key set and non-empty values.

## Security

- Read-only dictionaries contain public UI copy only.
- Registry/editor reads require `platform.settings:view`; writes require
  `platform.settings:update`.
- Existing cookie CSRF protection applies to every mutation.
- Language code and name, key membership, value type and value length are
  validated server-side.
- Translation values are plain text and are never inserted as trusted HTML.
- Imports are validated completely before a transaction writes any row.
- Every mutation is recorded through `AuditLogService` with language/key and
  old/new values.

## Legacy browser data

The localization settings page detects a non-empty legacy
`dwh_custom_languages` value for an authorized administrator and offers a
one-time import. Only known string keys are included. Local data is removed only
after a successful server transaction; cancellation or failure leaves it
unchanged.

## Verification and release criteria

- Backend tests cover precedence, fallback, validation, permissions, auditing,
  transactional rollback, caching and optimistic conflicts.
- Angular tests cover initialization, persistence, switching, reactive
  repaint, dynamic selectors, editor filtering, dirty state, import and error
  handling.
- A catalog contract test proves equal, non-empty key sets for all eight
  bundled languages.
- Playwright proves cross-browser persistence, unauthorized write rejection,
  incomplete-language fallback, live repaint and reload persistence.
- Maven tests, Angular tests, TypeScript checks, production builds and the
  existing E2E suite remain green.
- Docker restart preserves languages and overrides.
- No current static user-facing copy remains outside the catalog except
  explicitly documented bootstrap or technical diagnostics.
- Linguistic review by native speakers remains a release activity; automated
  tests prove completeness and structure, not idiomatic quality.
