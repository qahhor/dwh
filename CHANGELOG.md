# Changelog

All notable changes to SmartupCMS will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The file storage list pages through every file with a server cursor; it
  used to show at most the 100 newest and hide the rest. It runs on the field
  registry (`mf.files`): sorting of the whole list by name, size or date,
  column settings, saved views, the filter builder and a search over file and
  uploader names, all within the viewer's data scope and the "mine" switch.
- The UPL uploads list now runs on the field registry like the sources list:
  columns from the server, sorting of the whole list by a header click,
  column settings, saved views, the filter builder (status, period, source,
  file, rows, errors, format version, uploader) and a search over source and
  file names. It pages instead of "Load more"; a row still opens its card.
- Bulk actions. Server tables can let people choose rows; a bar above the
  table says how many are chosen and holds the screen's actions, and the
  choice ends when another page arrives. `POST …/bulk` applies one action to
  up to 100 records, each through the same single-record operation — its
  rights, data scope, checks and audit — in its own transaction, and answers
  record by record, so one failure does not undo the rest. The task list is
  the first to use it: a new status or priority for every chosen task, with
  the tasks that could not change named alongside their reasons.
- Lookups: a select can show its options as columns under a header row,
  search the server, load more, and offer "Create “typed text”" as its last
  option. Registry lists take a free-text `q` that matches any searchable
  field (ADR-0016). The UPL upload form now searches sources by code or name
  instead of downloading them all, shows code, periodicity and version beside
  each, and can create a source from the typed name and come back with it
  chosen.
- A filter builder for server lists. "Filter" opens a side panel of
  "field — condition — value" rows joined with "and"; the fields and the
  conditions each offers come from the list's field registry, and the value
  editor follows the field type (text, number, date picker, yes/no, a choice
  or a set of choices). An incomplete row says what is missing and takes
  focus instead of being applied. Active conditions show as chips above the
  table, each removable on its own, and the filter is saved with a view. The
  UPL sources list is the first to offer it.
- Saved list views (ADR-0016). A person can save what a list shows —
  columns, their order and widths, the sort and, later, the filter — under a
  name, switch between views from a menu next to the column settings, update
  or delete a view, and choose the one the list opens with. Views are stored
  on the server per person and list (`/api/v1/list-views/{list}`), checked
  against the list's field registry, audited, and guarded by a lock version.
  The UPL sources list is the first to offer them.
- Column settings for server tables: a "Columns" button opens a panel to
  show or hide columns and move them up or down — by keyboard too, with each
  new place announced — and a Reset; widths are set by dragging a header
  edge. The choice is remembered per table in this browser, and a column
  added later still appears. The UPL sources list is the first to offer it
  and now builds its columns from the server's field registry, sorts the
  whole list by a header click and pages instead of "Load more".
- A server field registry for lists (ADR-0016). A module declares a list's
  fields once — type, label, whether it can be filtered, sorted or empty —
  and `GET /api/v1/query-meta/{list}` gives them to anyone who may see the
  list. Lists take a `filter` of JSON conditions (`contains`, `in`,
  `between`, `empty` and others, by field type) and a `sort` on any sortable
  field; values only ever reach SQL as parameters, every mistake comes back
  as a 422 addressed to its condition, and a cursor continues only the query
  that issued it. The UPL sources list is the first to use it.
- One dialog service for the whole application, vendored from the UI kit:
  `SMTModalService.open()` for any content and `confirm()` for a yes/no
  question. The confirm dialog is announced as an alert dialog named by its
  title and described by its message, starts focus on the declining button,
  shows the message as text, marks destructive actions in the danger colour
  and follows both themes. The two remaining browser `confirm()` prompts in
  Settings (migrating legacy language packs, closing the translation editor
  with unsaved edits) now use it.
- `smt-control`, one wrapper for a form field's label, hint and error. It
  reads Angular Signal Forms fields, the default for new screens, as well as
  legacy `ngModel` fields; names the field with its label, links the hint and
  the error through `aria-describedby`, marks invalid and required fields for
  assistive technology, and shows catalogue messages for the built-in rules
  once the field is touched.
- A drawer service, vendored from the UI kit, for details that open beside a
  list instead of leaving it. The drawer is a modal dialog named by its
  title, keeps focus inside while open and returns it to the control that
  opened it, closes on Escape and on navigation, spans the full width on
  phones and skips its slide when the system asks for reduced motion.
- Keyboard shortcuts for toolbar buttons, vendored from the UI kit:
  `smtHotkey="save"` (Alt+S) or any combination such as `ctrl+enter`. A
  shortcut matches the physical key, so it works on a Russian layout; it
  never presses a button behind an open dialog; and the button announces its
  shortcut to assistive technology through `aria-keyshortcuts`.
- Date and period pickers. `smt-date-picker` takes a typed date in the
  language's format or one picked from a calendar dialog, optionally with a
  time; `smt-date-range-picker` offers quick periods (today, last 7 days,
  this month…) and a range calendar that applies on confirmation. The
  calendar is fully keyboard operable and names every day in full. Values
  are ISO dates. Both bind to Signal Forms directly and to `ngModel` through
  a value accessor.
- `smt-select`, a searchable single-choice field built as an accessible
  combobox: one tab stop, arrows to move through the options, Enter to pick,
  Escape to close, typing on the closed field to start a search. Its list
  opens in an overlay, so a dialog no longer cuts it off, and stays
  reachable for screen readers inside modal dialogs.
- `smt-multi-select` for choosing several values: chosen values show as
  chips with labelled remove buttons, the list stays open while Enter or a
  click toggles options, and Backspace in an empty search removes the last
  chip. It shares `smt-select`'s keyboard, overlay and screen reader
  behaviour.
- `smt-tree-select` for picking one node of a hierarchy: a combobox whose
  list is a tree, with Right and Left to open, close and move between levels,
  every node announced with its level and position, and a search that keeps
  each match inside its parents.
- `smt-dropzone`, a drop area that is a label for a real file input, so it
  is reachable and named for keyboard and screen reader users; files of the
  wrong type or over an optional size limit are listed in an alert instead
  of being dropped silently.
- `smt-progress-stepper`, a navigation of steps in which the caller sets
  each step's status (done, has errors) and people can move to any step in
  any order; the current step is marked with `aria-current="step"` and the
  status is part of each step's name. The UPL format editor now uses it:
  File → Sheets and columns → Publication, one step at a time, with the
  error summary on every step leading to the step and sheet that hold the
  error, and a publication step that summarises what will be published.
  A published version walks through the same steps read-only.
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

- The user's manager, a task's responsible person and a task's parent are
  chosen with `smt-select`; remote search, "Load more" and retry work as
  before.
- A task's co-executors and observers are chosen with `smt-multi-select`.
- An organizational unit's parent is chosen from the structure as a tree
  instead of a flat list of every unit.
- File uploads (the file store and task attachments) go one file at a time,
  each with its own progress, cancel and retry. Several files no longer
  share one progress bar or run into the server's concurrent upload limit,
  and a failed file stays in the list with the server's message until it is
  retried or dismissed.

- Every date field uses the new date picker instead of the browser's own:
  the audit log and security event filters, the UPL upload period and
  format validity date, date custom fields and the task deadline (date with
  time). Dates are typed in the language's format and picked from the same
  keyboard-operable calendar on every screen; the stored values are
  unchanged.

- The local Compose stack passes `DWH_RATE_LIMIT_USER_PER_MINUTE` to the
  server (default 600, as before). CI raises it for its disposable E2E stack,
  where one administrator runs the whole browser suite and used to exhaust
  the per-user budget part-way.
- The language list in Settings renders through the vendored table
  foundation instead of a hand-written table, the first screen to do so.
- The project list renders on the shared table foundation. Its ID, name,
  status, closed-tasks and created columns sort the whole filtered list (all
  projects are loaded at once), not just the page on screen: names compare
  the way people read them ("сети 9" before "сети 10"), and projects whose
  statistics are unknown stay last whichever way progress is sorted. Without
  a chosen column the server's order is kept.
- The task list renders on the shared server table, like the user list and
  the audit logs. A click anywhere on a row opens the task; its priority and
  status selects still change the task in place. The page size can be
  chosen, loading is announced to screen readers, and a failed page keeps
  the rows already shown with a retry. The board view is unchanged.
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

- Opening and at once closing a dialog with a date, select or tree field
  bound through `ngModel` no longer throws NG0953: the fields ignore a form
  that registers with them after they are gone.

- The design token audit also checks `.scss` files. It had skipped them, so
  the dragged table row kept a fixed white background in the dark theme;
  that background now follows the theme.

- Success, warning and info notifications are read by screen readers. They
  sat in a polite live region created together with its text, which screen
  readers often skip; they are now announced through a region that is in the
  page from the start. Errors keep their alert role. A notification no
  longer disappears while the pointer or keyboard focus is on it, the same
  message shown again restarts the visible one instead of stacking a copy,
  and at most five are on screen at once.

- Tooltips reach keyboard and screen reader users. They appeared on mouse
  hover only; now they also show when the element takes keyboard focus,
  close on Escape without closing a dialog around them, stay while the
  pointer moves onto them, and their text is announced as the element's
  description unless it only repeats the element's own truncated text.

- `npm run i18n:audit` passes on Windows. Its allow-list of files that may
  hold Cyrillic text was compared with backslash paths there, so the
  generated Russian catalog was reported as unlocalized copy.

- Table cells broke words at any letter where the column ended ("торго|вой"
  across two lines), in every table on the shared foundation. A word is now
  broken only when it alone does not fit the column.

- Screen readers read icon glyphs aloud as English words, for example
  "account_tree Оргструктура" for a profile tab, across 74 icons in 22
  templates. Decorative icons are now hidden from assistive technology.
  Icons that carry meaning say it instead: whether each password requirement
  is met, the task type on kanban cards and subtasks, the protected admin
  role, and the sort direction of the analytics workload table (as
  `aria-sort` on its header). `npm run aria:audit` now fails on an icon that
  is neither hidden nor named.
- After a failed filter or page-size change, the list's Next and Back still
  worked and paged the new filters from a cursor of the old query, showing
  rows from a different result as the next page. Paging now waits for the
  retry, on every server-paged list.
- Typing in the user search left Next usable for the 250 ms pause, paging
  the new search text from the old query; the old query is now dropped as
  soon as the text changes.
- A manager the viewer cannot see (outside their scope, or deleted) showed
  as "no manager" in the edit form while one was set. It shows by id, and
  can be cleared deliberately.
- A page emptied by deleting its last row stays on screen as an empty page
  only on the users list; every server-paged list now steps back to the
  page before it. A page moved to that arrives empty still stays.
- Pressing Right on a search match in the organizational tree silently
  changed which branches the user had opened.
- The generic "operation failed" message showed its catalog key when the
  language packs could not be loaded.
- A server table whose first page failed showed the error and, beneath it,
  the empty state ("no audit records found"), claiming a result the server
  never returned. It now shows only the error and its retry.
- The audit change log and security events lost their card surface when they
  moved onto the server table, so the table sat on the page background in
  both themes. The card is back, holding the table, its pagination and any
  load error.
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

- `ui-searchable-select`, replaced everywhere by `smt-select`.
- `ui-user-multi-select`, replaced by `smt-multi-select`.
- Control Plane, fleet management, heartbeat, enrollment, and license gates.

### Security

- Pinned ClamAV to `clamav/clamav-debian:1.5.4` (Debian 13.7) by digest.
  The previous pin, 1.5.3 on Debian 13.6, carried 44 HIGH/CRITICAL fixable
  vulnerabilities (perl-base, openssl, util-linux, pcre2, sqlite and others),
  which failed the runtime image gate and with it the end-to-end job.

[Unreleased]: https://github.com/qahhor/dwh/commits/main
