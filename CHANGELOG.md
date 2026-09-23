# Changelog

All notable changes to SmartupCMS will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Explicit primitive colour scales (`--color-<hue>-<step>`) behind the existing
  semantic design tokens, so a neighbouring step is available where one is
  needed for contrast.
- `npm run contrast:audit` gate, enforced in CI. It discovers every rule that
  sets both a background and a text colour, resolves both through the design
  tokens in each theme, and fails below the WCAG AA minimum.
- CI enforcement of the design token contract: a colour property must name a
  token, so a theme can move it. The four files where a colour is data rather
  than styling are listed as exceptions with their reason.
- CI enforcement of the localization contract: the audit documented in the
  contribution guide now runs on every change, and the generated Russian
  fallback dictionary is regenerated and compared, so it can no longer drift
  from the canonical catalog unnoticed.
- The design token audit now covers the vendored UI kit's Tailwind
  utilities. Every colour utility the kit uses must map through the
  `@theme` bridge to a token both themes define, the bridge must be
  inlined, and a background and a text utility used together must meet
  WCAG AA in both themes. A newly ported component that brings a new
  colour fails the gate instead of rendering colourless.
- The vendored table is announced as a table. It was drawn with bare divs,
  so a screen reader met unrelated blocks; it now carries table, row,
  columnheader and cell roles, an accessible name, a row count that stays
  correct under virtualization, and `aria-sort` on sortable columns, which
  are reachable by Tab and sort with Enter or Space.
- The organizational structure screen shows divisions as a tree table with
  kind and state columns and a search that keeps every match inside its
  parent divisions. It follows the WAI-ARIA treegrid pattern: one Tab stop,
  arrow keys to move and to open or close a branch, Home and End, Enter or
  Space to select, and level, position and expanded state announced for
  each row. Expand all and Collapse all act on the whole tree.
- A user's division assignments are chosen in the same tree table as the
  organizational structure screen, with search and expand or collapse all.
  The tree follows the multi-select treegrid pattern: rows announce whether
  they are checked, Space or Enter toggles one, and the checkboxes are a
  pointer affordance rather than extra Tab stops. The old nested-list tree
  component is removed.
- The task form's four lookups (parent task, responsible, executors,
  observers) share one searchable lookup built on the keyset pager instead
  of four copies of the same request bookkeeping. Their behaviour is pinned
  by new tests written before the change: the typing pause, the latest
  search winning, load more, selected entries kept across searches, retry
  of the failed request, and cancellation when the screen goes away.
- An accessibility gate in the CI frontend job: axe checks the rebuilt
  screens against WCAG 2.1 A and AA in both themes, on the production build
  with a mocked API, so it needs no backend. A static ARIA audit
  (`npm run aria:audit`) fails any template that names an element whose
  role takes no name, on every screen, including those the gate does not
  open.
- The localization audit reads every source file, not only component
  classes: external templates and services are checked too, 273 more
  referenced keys in all. Cyrillic outside the catalog fails it, except in
  two named files where it is data (language endonyms, the offline
  dictionary).
- `ui-server-table`: a table over a keyset API, composed of the vendored
  table, the application's pagination and the shared pager. Loading is
  announced and shown as skeleton rows, a failure keeps the rows on screen
  with a retry of exactly the failed request, and a screen supplies its own
  empty state. Both audit lists, the change log and security events, use it.
- A vendored table that overflows sideways becomes a named, focusable
  region, so its hidden columns can be scrolled to without a pointer.
- The design token audit also fails on arbitrary Tailwind colours
  (`text-[#…]`, `bg-(--x,#…)`) and on colour literals in kit templates,
  which bypass the bridge and ignore the theme.
- Theme changes propagate to the application's other open tabs over a
  same-origin broadcast channel, so a window left open no longer keeps the
  previous theme until it is reloaded.
- Local, audited announcement authoring and lifecycle management.
- Read-only system status API and administration screen.
- S3-compatible object storage for AWS S3, Cloudflare R2, MinIO, and compatible
  providers.
- Encrypted database backup sidecar with sanitized local status reporting.
- Apache-2.0 community, governance, security, and contribution policies.

### Changed

- The language list in Settings renders through the vendored table
  foundation instead of a hand-written table, the first screen to do so.
- The user list pages through the server a page at a time on the shared
  server table, with a retry on failure and loading announced to screen
  readers. It replaces a list that loaded 50 users with "load more", then
  sorted and paged only the rows loaded so far, so its sorting, page count
  and header counter described the loaded rows rather than the users. Column
  sorting is gone until the server can sort; blocking, editing or deleting a
  user keeps the page on screen instead of jumping back to the first.
- Exporting users to CSV exports every user the filters match, page by page
  up to 10,000 rows, and says so when the limit cuts the export short. It
  used to export only the rows loaded on screen.

- Unified the product as one SmartupCMS installation for one organization and
  many users.
- Renamed runtime applications to `server` and `web` and consolidated the
  production Compose topology.
- Made all standard runtime behavior local and disabled outbound telemetry by
  default.

### Fixed

- The user list named a manager only when the manager happened to be among
  the loaded rows, and showed `ID: #42` otherwise. The manager's record is
  now looked up once and remembered for the screen's lifetime.
- The manager picker in the create and edit user forms offered only the users
  loaded on the list, so in a larger organisation most managers could not be
  chosen. It now searches active users on the server, pages with "load more",
  and keeps the current manager selected even when the search does not return
  them.
- Screen readers dropped several names because they sat on elements that
  cannot be named: the pagination's current page, the language coverage
  figures and the two-factor status in the user list, which read the icon's
  ligature ("check_circle") instead. The names are now real text or carry a
  role that takes a name; redundant labels on count badges are removed and
  the search settings' scroll areas are named regions.

- In the tree table, two arrow presses in quick succession moved one row:
  focus follows after a render, so the second press still reached the row
  just left. Movement now starts from the row the tree last moved to, and a
  row focused another way (a click, a screen reader) becomes that row.

- The audit list's rows-per-page picker showed blank: the list pages by 20,
  which was not among the offered sizes. The current size is now always one
  of the options.

- The users list could mix results: a "load more" still in flight when a
  filter changed appended users from the old filter to the new list (a
  blocked-users view showed active users), and a slower answer to an
  earlier search replaced the answer to the latest one. The UPL sources
  list appended a stale page after a refresh the same way. Both lists, and
  the task list, now page through the shared keyset pager.

- The audit screen could show results for a filter the user had already
  replaced: a slower response to an earlier request overwrote the newer
  one. After a failed "next page" it showed the new page number over the
  old rows. Both lists now page through a shared keyset pager that cancels
  superseded requests, moves the page only when its data arrives, and
  retries exactly the request that failed.

- Creating a custom field without a name or code showed a raw key such as
  `iam.ukazhite_nazvanie_polya` instead of the validation message; the three
  missing keys are in every catalog.
- Connection and request errors, the sign-in welcome and confirmation
  toasts, notification and announcement titles, the default error title and
  the language-pack failure were fixed Russian strings in code, shown in
  Russian whatever the user's language. They are catalog keys now; the
  connection messages are also in the offline dictionary, since they appear
  exactly when the catalog cannot be fetched.
- Table, tree and organizational-structure strings added earlier existed
  only in Russian and English and fell back to Russian elsewhere; they are
  in all eight catalogs.
- The "good" password strength colour was a fixed blue; it follows the
  theme's info token.

- Vendored components rendered without any padding or margins: the
  application's global reset was unlayered and outranked every layered
  Tailwind utility. The reset now sits in the `base` layer; the
  application's own styles override it as before.
- The table's sort indicator never showed a direction, and `aria-sort` kept
  its old value after sorting, because the column config was mutated in
  place. The indicator is a direction glyph and the config is replaced.
- The table skeleton and column-resize line used fixed greys that vanished
  on the dark surface; they follow the theme tokens.
- The language list's action buttons were clipped; columns now have fixed
  tracks and the buttons wrap.

- The Settings language list rendered as a white panel in dark theme. The
  Tailwind bridge declared `--color-white` as a layered theme variable, which
  the application's own unlayered token of the same name overrode. The bridge
  is now inlined and mapped per utility role, and 63 kit colours that had no
  mapping, and rendered uncoloured, now follow the theme.
- Dark-theme text on solid accent fills no longer uses a hard-coded white.
  Primary buttons, active pagination and sidebar items, avatars, and tabs now
  read the per-theme `--on-primary` ink, raising the worst measured contrast
  from 2.14:1 to 9.07:1. Destructive buttons (3.76:1) and danger badges and
  alerts (4.29:1) were below WCAG AA in dark theme and now pass at 5.16:1 and
  5.84:1.
- Category accents for marking what a record is rather than how it is doing:
  navigation entry types and the note icon now read `--accent-violet-*` and
  `--accent-indigo-*` instead of fixed hues that ignored the theme.
- The application fetched Inter and Material Symbols from a third-party CDN,
  so in a closed network every icon rendered as its own name in text and the
  interface was visibly broken, while each page load disclosed the viewer's
  address to that third party. Both fonts now ship with the build and are
  served from the application's own origin.
- Status colours across features painted fixed hues that ignored the theme:
  success, warning, danger and info were written as literals, and 83 `var()`
  fallbacks masked their own tokens. Colour properties now read the semantic
  tokens, leaving only data, the note card palette and category hues literal.
- The collapsed sidebar's flyout panel ignored the theme: it painted the
  light-theme sidebar colour in both, so in dark theme it did not match the
  sidebar it belongs to. Its active entry also failed WCAG AA at 4.10:1 in
  both themes, and now passes at 5.67:1. The sidebar chrome reads a named set
  of tokens for its permanently dark surface instead of 27 literals.
- Invisible and unreadable text in dark theme caused by colour properties
  pointing at design tokens that were never defined, so their `var()` fallback
  painted the same light-theme colour in both themes. Notes measured 1.04:1,
  and twelve invented token names across nine files now point at the tokens
  they were synonyms for.
- Every remaining colour pair below WCAG AA, found by the new audit: danger and
  info text on their subtle backgrounds (4.29:1 and 4.00:1) now use dedicated
  `--danger-text` and `--info-text` inks; the dark tertiary text tier
  (3.17:1-3.61:1) moved one scale step lighter; and hard-coded light-theme
  literals in the sidebar count badge, the navigation row hover, and the
  embedded report error and fallback bars now read theme tokens, so those
  surfaces are no longer light-on-light in dark theme.

### Removed

- Control Plane, fleet management, heartbeat, enrollment, and license gates.

### Security

- Pinned ClamAV to `clamav/clamav-debian:1.5.4` (Debian 13.7) by digest.
  The previous pin, 1.5.3 on Debian 13.6, carried 44 HIGH/CRITICAL fixable
  vulnerabilities (perl-base, openssl, util-linux, pcre2, sqlite and others),
  which failed the runtime image gate and with it the end-to-end job.

[Unreleased]: https://github.com/qahhor/dwh/commits/main
