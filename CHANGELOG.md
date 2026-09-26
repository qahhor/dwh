# Changelog

All notable changes to SmartupCMS will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and releases use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- ADR-0019 (proposed): the low-code entity model. An entity is one server
  declaration (`EntityDefinition`) from which lists, forms (`form-meta` with
  the actions allowed to the viewer), cards, permissions and menus are built;
  custom fields become registry fields, lists refer to each other. The
  analysis behind it and the order of roadmap wave 9 are in the ADR.
- Extra kit controls (roadmap item 40), our own code after the kit's ideas:
  `smt-rating` (a radio group of stars, arrows/Home/End, a clearable rating
  clears by its own star), `smt-range-slider` (a from–to band on two native
  range inputs that never cross), `smt-weekday-toggle` (pressed-state day
  buttons named by Intl, the value being ISO days Monday first) and
  `smt-cropper` (a crop area in natural pixels moved and resized by pointer
  or keyboard, `toBlob` for the result). The kit's cropper wraps
  ngx-image-cropper, so no new dependency is added. Each field is a Signal
  Forms value control with an ngModel accessor.
- Tables outside the kit reviewed (roadmap item 46). The audit record's
  before/after comparison is the kit table (`ui-local-table`). Editable grids
  stay native tables — the kit table is for reading lists, its virtual rows
  recycle DOM — and get their accessibility right: the role permissions
  matrix names each row by its form (`th scope="row"`), and the UPL format
  columns table is named by its sheet with `scope` on every header. The
  chart's hidden data table stays as it is.
- Dialogs on the kit (roadmap item 45). `smt-dialog` keeps the declarative
  shape screens use — `[open]`, a title, a size, the content in an
  `<ng-template smtDialogContent>` created only while open, a `footer` row —
  and opens a CDK dialog through the kit's modal service and look: an overlay
  above the page, a focus trap that returns focus to the opener, a scroll
  lock, Escape handled by the top dialog only, and correct stacking with
  select lists and confirmations opened from it. Escape, the backdrop and the
  close button ask (`closed`); the screen closes it, so a dialog with unsaved
  changes can stay open. All 49 `ui-modal`s and the hand-made notification
  preferences window use it; `ui-modal` and the
  `body.modal-open` rule are removed. Tests find dialog content with
  `inScreen()` (component and overlays) and redraw OnPush views with
  `redraw()` from `src/testing/in-screen.ts`.
- Button (roadmap item 44). `smt-button` is an attribute on a real `<button>`
  or `<a>` — `<button smt-button smtVariant="danger" smtIcon="delete">` —
  so its type, form, disabled state, click and every aria-* attribute are
  the button's own; four variants and three sizes from our tokens, a
  Material Symbols icon, and a loading state that disables the button, marks
  it busy and says "in progress" to screen readers; a disabled link leaves
  the tab order and does not navigate. It replaces all 222 `ui-button`s,
  which is removed. Attributes that used to sit on the wrapper and never
  reached the button (`aria-expanded`, menu triggers, test ids) now do; a
  button without a type is `type="button"`, so none submits a form by
  accident. The 31 buttons styled by the global `.btn` classes and the three
  `.btn-icon` ones use it too (`smtIconOnly` draws a square icon button), and
  the global and local `.btn*` rules are gone.
- Kit controls everywhere (roadmap item 43). Fifty-two native selects are
  `smt-select`, the remaining text fields `smt-input`, the textareas
  `smt-textarea`, and checkboxes follow one rule: a toggle that acts at once
  (a filter) is `smt-switch`, a flag saved with a form or one of a set is
  `smt-checkbox`, which gets an ngModel bridge. `smt-select` gains
  `smtInvalid` and `smtDescribedBy` (the org-unit editor ties its server
  errors to the field again) and `data-value` on the trigger and options, so
  end-to-end tests pick a value whatever the language (`e2e/support/select.ts`).
  `smt-input` gains `(cleared)` and `smtFocusInitial`, and hides the
  browser's own search clear button; `smt-textarea` gains `smtInvalid`.
  Search boxes use the field's icon and named clear button instead of their
  own (eight keys that named those go); the login, profile and user-create
  password fields share the built-in show/hide button. The users filter menu
  stays open while an option is picked in the overlay, the header hands the
  shell a typed language request instead of a fake change event, and the
  command palette drops a hidden duplicate of its category buttons. Only the
  palette's own combobox field and the Markdown editor's text area stay
  native.
- Text field (roadmap item 42). `smt-input` covers text, email, url, tel,
  search, password and number (a number model, null when empty): an optional
  icon, a named clear button, a show/hide password button that says which
  and controls the field, native events that bubble to the host, and an
  ngModel bridge that reports every edit like a native field. `smtInvalid`
  shows an error the screen decides at once; Signal Forms keeps `invalid`.
  Fifty-eight hand-styled fields across login, profile, settings, webhooks,
  custom fields, notes, UPL, modules, navigation, project members and the
  list filters and views use it; the file picker stays native. The login and
  profile password fields drop their own show/hide buttons (and eight keys
  that named them) for the built-in one, and the modules search its own icon
  and clear button.
- Every feature checks its own localization keys. Fifteen new
  `*.i18n.spec.ts` (analytics, audit, auth, files, iam, notes, reports,
  settings, system, tasks, upl, the app shell, the command palette, shared UI
  and core) join the four there were, so every catalog namespace has an owner
  that fails on a key missing from ru.json, from en.json where the feature
  ships English (upl stays Russian only), or used by nothing. Keys built at run
  time come from the code that builds them: `QUERY_OPS` (`ui.filter.op.*`),
  `SEARCH_ENTITIES` (`settings.search.entity.*`), `UPL_PACKAGE_CODES`; keys
  the server names are read from its sources by `serverLiteralKeys` (list
  column labels, the xlsx template and error file, task history) and
  `serverCodeKeys` (`upl.err.*` and `error.*` from codes, enum constants and
  validation annotations; search fields from `SearchQueryPolicy`).
  `nav.upl_packages` and `nav.upl_sources` get their English, and the dead
  `projects.uchastniki` goes from all eight catalogs.
- Tabs (roadmap item 41). `smt-tab-bar` follows the WAI-ARIA tabs pattern:
  the chosen tab is the only tab stop, arrows choose (Home and End go to the
  ends), disabled tabs are skipped, a count is read as part of the tab's name,
  and a focused tab in a scrolled bar comes to the middle; a compact look fits
  a toolbar. It replaces the application's own tab bars in settings, audit,
  notes, notifications, announcements (status filter and the languages of an
  announcement), modules, task dictionaries, the user card and the Markdown
  editor — several had no arrow keys, no single tab stop or no name. The user
  card's tabs are named "User card sections".
- Phone and colour fields (roadmap item 39). `smt-phone-input` pairs a
  native country list for the product's markets (and "other") with a
  telephone field that writes digits into the country's mask as they are
  typed and stores E.164; an incomplete number is said under the field. The
  user dialogs use it. `smt-color-input` offers a named palette as a radio
  group — "Blue", not "#2563eb" — then the system picker and the hex code for
  an own colour; the task type and status dialogs use it, and new types and
  statuses start on a palette colour. smt-control skips a field's parts
  marked `data-smt-field-part`, so a label names the number, not the country.
- Segmented filters (roadmap item 38). Eleven single-choice groups built from
  toggle buttons — analytics and data overview periods, files scope, custom
  field entities, role matrix modules, effective permission sources, user,
  task and project status filters, task presets, and the task and project
  view switches — are radio groups in the segmented or chips look: one tab
  stop, arrows choose, and the choice is announced (the permission sources
  announced none at all). Counts are read as part of each option's name.
  Analytics gets its own "Retry" after a failed period instead of clicking the
  chosen period again; a doubled icon left in its alert is removed.
- Alert (roadmap item 37). `smt-alert` shows a message in a tone — danger,
  warning, success, info — with its icon, an optional title and close button,
  and a live role that follows the tone: danger is read at once, the others
  politely, and `smtLive="off"` keeps a result that is part of the page quiet.
  It replaces the twenty-two hand-made `.alert` blocks (UPL sources, formats
  and packages, analytics, exports, files, role permissions), and the global
  `.alert` styles with their hard-coded border colours are gone.
- Dynamic field (roadmap item 36). `smt-dynamic-field` draws a field described
  as data — text, long text, number, yes/no, date, date and time, time, a
  choice from a list, a person — with the matching kit field inside
  smt-control. Custom fields on users, tasks, projects and notes use it: a
  person field searches the server instead of offering the first hundred
  users, a list field is a searchable select, yes/no is a switch, and every
  field gets the same label, required mark and error.
- Task dialogs on the kit's pickers (roadmap item 35). Parent, responsible,
  co-executors and observers are data selects over the shared users and tasks
  sources; the people and parent a task's card already names are passed in
  as known rows, so nobody is asked for by id. Twenty-four inputs and outputs
  per dialog, the task lookup channels and the user options pipe are gone.
  Type and priority are radio groups — chips with the type's coloured icon
  and a segmented bar — instead of toggle buttons that announced "pressed"
  for a single choice. A user-typed custom field on a task card now shows a
  name, asked for once, instead of relying on whoever the pickers had loaded.
- Sortable list, file card and image preview (roadmap item 34).
  `smt-sortable-list` orders rows by dragging the handle or with each row's
  up and down buttons; a button move keeps focus on the moved row and is
  announced with its new place, and locked rows stay put. The task types and
  statuses dialog uses it: system rows now move by keyboard as they did by
  mouse, focus no longer jumps away after a move, and the dialog's copied
  reorder code is gone. `smt-file-card` shows a file's kind, name, size in the
  person's language and named download, preview and remove buttons; the
  attachments field uses it. `smt-file-preview` shows images one at a time in
  a dialog with previous, next, the arrow keys and download; the attachments
  field and the files list open it for images. Other files are downloaded:
  the API serves them as attachments and forbids framing on purpose.
- Avatar, tags and menu button (roadmap item 33). `smt-avatar` shows a photo
  or up to two initials on one of six token tones picked from the name —
  decorative beside a written name, an image named by the person when alone —
  and replaces eight hand-made avatars with their own colours (users list and
  card, profile, sidebar, workload, task members and comments, project
  members). `smt-tag` is a toned label with a named remove button;
  `smt-tag-group` is a set of toggle tags, and the user dialogs pick roles
  with it (the admin's own admin role shows locked, with the reason read out).
  `smt-dropdown-button` is a menu button on the CDK menu (APG keyboard,
  focus back to the trigger); the users list keeps view and edit on the row
  and puts block, unblock and delete behind "More actions: <name>".
- Data selects (roadmap item 32): `smt-data-select` and
  `smt-multi-data-select` feed themselves from a lookup source — the first
  page when opened, search after a pause, "load more", retry, and the name of
  a record chosen before any page arrived, asked once by id and shown as
  "ID: #…" when the server will not return it. A source is written once per
  reference list (`shared/lookups/lookup-sources.ts`, `restLookup` over any
  keyset endpoint); screens bind `[source]` with Signal Forms, ngModel or
  reactive forms. The manager picker of the user dialogs uses it: seven
  inputs and outputs per dialog are gone, and its "Manager" label now names
  the field.
- Form fields from the kit, wave 6 (roadmap item 31), written as our code after
  the kit's ideas, on semantic tokens, for Signal Forms and — through value
  accessors — ngModel and reactive forms: `smt-textarea` grows with its text
  and shows "used of allowed" linked to the field; `smt-switch` is a real
  role="switch" button; `smt-radio-group` follows the APG radio group (one tab
  stop, arrows choose, plain or card look); `smt-time-picker` reads 930, 9:30
  or 9.30 and offers a list of times every `step` minutes within
  `minTime`/`maxTime`. smt-control now names radio groups and switches.
  In use: the data-scope rule of a role, the API token lifetime and the UPL
  new-draft choice (radio groups, the lifetime group now has a name); project
  descriptions and announcement texts (textarea, the announcement's own
  counter replaced); notification sound, required 2FA and module switches
  (switch; a module switch shows the change at once and takes it back if the
  server refuses).
- Front-end tooling (roadmap item 30). `npm run signals:audit` checks the
  member order of Angular classes (inject, inputs, outputs, models, queries,
  signals, computed, effects, fields, constructor, methods; public before
  private), ported from the kit's `order-angular-signals` rule to the
  TypeScript API; `--fix <file>` reorders a file. The 84 classes that broke
  the order are listed in a baseline that may only shrink. A feature can now
  carry an `*.i18n.spec.ts` (`src/testing/feature-i18n.ts`): its keys are in
  ru.json, in en.json where it ships English, keys built at run time are
  declared, and none of its keys is dead — exports, notifications,
  announcements and the data overview have one. CI runs the web tests with
  coverage and puts the table in the job summary of every pull request,
  failing below a floor just under today's figures. The web tests type-check
  against Node types through `tsconfig.spec.json`.
- HTTP interceptors (roadmap item 29). A 401 from the API while signed in
  signs the tab out once — not a toast per failed request — tells the other
  tabs, explains why and, after signing in again, returns to the same page;
  a deep link opened without a session also lands there after sign-in. A 401
  on logout counts as signed out. Every change sent to the API (POST, PUT,
  PATCH, DELETE) carries its own `Idempotency-Key`, and a change whose answer
  was lost (network error, 502, 503, 504) is repeated twice under the same
  key, honouring a short `Retry-After`, so the server does it only once.
  Sign-in, secrets, file uploads, downloads and bodies over 60 KB go without
  a key, as the server requires.
- Idle lock (roadmap item 28): after `security.idle_lock_minutes` (30 by
  default, 0 — off, set in Settings → Security) without a click, key or scroll
  in any tab, the session is closed on the server and the sign-in page says
  why. A minute before, a dialog counts down and offers to keep working.
  Activity in one tab keeps the others open. `GET /api/v1/settings/session`
  returns the limit to a signed-in user.
- Open tabs stay in step (roadmap item 27): signing out — or changing the
  password — in one tab signs out the others with a note why; signing in
  wakes the tabs still on the sign-in page; a language chosen in one tab
  follows in the others without saving it again. The theme already synced.
  Only well-formed messages on the channel are applied; without
  BroadcastChannel the tabs simply do not sync.
- KPI cards and charts on the data overview (roadmap item 26): each figure
  shows its change against the same number of days before, coloured by
  which way is good for it (fewer rejections is good), and reads as one
  sentence to screen readers. Uploads by day are a stacked bar chart —
  applied, in progress, rejected — drawn in plain SVG from theme colours,
  with a tooltip per bar and the same figures as a table for screen
  readers. No chart library: the start of the application does not grow.
- Data freshness and Needs attention (roadmap item 25): the data overview
  lists every source worst first — fresh, waiting for data, overdue, never
  delivered or without a schedule — from its periodicity and deadline, with
  the last period delivered and the due date. Needs attention names overdue
  sources (linking to the upload form with the source chosen), rejected
  uploads nobody replaced and checked uploads waiting to be applied
  (linking to their card, which `/upl/packages?open=<id>` now opens;
  `GET /api/v1/upl/packages/{id}` returns one upload).
- Data overview (roadmap wave 5): a lazily loaded page, Data overview in the
  menu, shows what came into the warehouse over 7, 30 or 90 days — uploads,
  applied, waiting to be applied, rejected and the rows that reached the
  warehouse (`GET /api/v1/upl/overview`). Each widget (`ui-dashboard-card`)
  has its own loading, failure and empty state; the page says when the
  figures were taken and refreshes by itself every five minutes while the
  tab is visible. The start of the application does not grow with it.
- UPL header synonyms: a format column can accept other headers besides its
  name ("Сумма, руб", "Итого") — up to ten, entered separated by semicolons
  in the format editor. The parser finds the column by any of them and does
  not call them unknown; a synonym that equals another column's header is
  refused as a duplicate. Headers now also match across runs of spaces. The
  template's instruction sheet lists the accepted headers.
- Exports to Excel (ADR-0018): registry lists (UPL sources and uploads,
  files) have a To Excel button that queues the list exactly as on screen —
  filter, sort, search and the columns shown. A background job writes the
  xlsx as the person who asked, with their rights at that moment, and the
  file waits for a week in My exports (profile menu), which refreshes itself
  while an export is being prepared. Numbers, dates and choices keep their
  kind in the file; long lists stop at the configured row limit and say so.
- UPL errors file: an upload's errors can be downloaded as xlsx — what was
  uploaded and what came of it, then every stored error with its sheet, row,
  column, the value found and what is wrong in words (the card's words, in
  the reader's language), with a filter and a fixed header. The upload card
  links to it for checked uploads with errors and for rejected ones.
- UPL file templates: every format version can be downloaded as the file a
  supplier fills in — an instruction sheet (what the file is for, how to fill
  it, each column with its type, whether it is required, its unit and key
  format) and the data sheets with headers where the parser looks for them,
  a note on each header, and number, integer and date columns formatted and
  checked by Excel as the supplier types. A CSV format gets its header line.
  The link is in the source's version list and in the upload form, for the
  chosen source's published version. fastexcel writes the file; Apache POI
  is not needed.
- Field rights in the field registry (ADR-0016, 2.9): a list field can require
  its own right. Without it the field is absent from query-meta, a filter,
  sort or search on it is refused as for an unknown field, and its value is
  left out of the response. Who uploaded a UPL package is the first such
  field: it is shown to those who may see the user directory.
- Record history (ADR-0017): the task card and the user card have a
  "Change history" section that shows who changed which field from what to
  what and when, newest first, from the audit log. It opens with the
  record's own right and data scope, not with the audit log right, and loads
  only when opened. `GET /api/v1/history/{kind}/{id}` serves tasks, projects
  and users; a module adds a kind with one `RecordHistorySource` bean.
- `ui-local-table`: the kit table over a list that is loaded whole, sorting
  every row by a header click (by keyboard too), with empty values last and
  ties kept in place. Modules, custom menu items, webhooks, custom fields,
  active sessions and API tokens moved to it from hand-written tables; row
  actions now name their item ("Delete “Sales report”") instead of repeating
  "Delete" on every row, and custom fields can be sorted from the keyboard.
  Later moved as well: UPL format versions and cell errors, team workload in
  analytics, project members, a user's sessions and login attempts, search
  generations and jobs, and communication channels. The search job history
  pages newest first, so it offers no header sorting. Hand-written tables stay
  only where the grid is the editor or not a list: the role permission matrix,
  UPL sheet mapping, notification preferences, the audit before/after diff and
  the calendar.
- Period presets in filters: the audit log and security events take one
  period (today, last 7 days, this month…) instead of two date fields, and
  it applies at once; a "between" date condition in the filter builder is
  edited the same way.
- Form fields in UPL sources and uploads, tasks, users and projects are
  wrapped in `smt-control`: the label, a required mark, the hint and the
  error are linked to the field for screen readers (hints and UPL errors
  were not linked before), and a required field says so once it is left
  empty, not only after submitting.
- Confirmations run their action: deleting a file, a user, a custom field,
  a menu item, a webhook, a task type or status; removing a project member;
  revoking a token, ending sessions, unbinding a channel; the user security
  actions; publishing or archiving an announcement and removing a UPL sheet
  now ask in one accessible alert dialog. It stays open and busy while the
  request runs, cannot be dismissed meanwhile, and shows the server's reason
  in the dialog (not as a toast) so the person can retry or decline. Rights
  to delete a file are checked again when Yes is pressed. Discard-changes
  prompts, role deletion and the data scope change keep their own dialogs:
  they are part of the leave guards and nested decisions of those screens.
- The project in the task create and edit forms and in the task filter is
  chosen from a searchable list (`smt-select`) instead of a native select,
  so a long project list can be filtered by name. Short fixed lists
  (priority, language, yes/no) stay native selects.
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

- Every Angular class lists its members in the agreed order; the signal order
  baseline is empty (82 classes before). `signal-order-audit --fix` reordered
  them; two initializers that read a field now declared below were changed by
  hand: the i18n service seeds its dictionaries from the offline catalog
  directly, and smt-control sets its field id in the constructor.
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

- Menu items limited to a right are shown only to its holders (FR-MOD-02,
  roadmap item 47). `required_permission` was stored but applied nowhere:
  `/navigation/items/active` now drops items the viewer lacks the right for,
  together with their nested items, and `by-code` answers 404 for them as
  for a missing item; the web checks the same pair. The right is a live
  catalog pair (unknown or deprecated ones get 422 at `requiredPermission`),
  chosen in the menu settings by name (`GET /navigation/items/permissions`),
  and audited. Editing an item no longer clears its right and its parent.
- The DWH connection pool has time limits (DWH P0). It holds four connections
  and had none: one heavy mart read or a transaction left open held a
  connection for good. PostgreSQL now cancels a statement and ends a
  transaction idle longer than `APP_DWH_STATEMENT_TIMEOUT` (default 60s); the
  raw cleanup and the raw check, which scan all of raw, get
  `APP_DWH_MAINTENANCE_STATEMENT_TIMEOUT` (default 30m) inside their own
  transaction only, and the connection returns to the pool with the usual
  limit. A socket timeout above both guards against a silent network.
- An interrupted package apply no longer sticks (DWH P0). Applying is three
  steps — take a load number, write raw into the DWH, close the package — and a
  crash or database failure after the first left the package "verified" with a
  load number and the load `pending` for good: applying again answered 409 and
  the raw cleanup never saw it. The new `upl.apply_recovery` job (every 15
  minutes, V121) closes an apply older than an hour whose load is still
  pending: the load is failed, so its raw rows are cleaned, and the package is
  "rejected by the system" with `UPL_PKG_APPLY_INTERRUPTED` ("upload the file
  again"), as after a failed raw write — a load's package reference is unique,
  so the same package cannot be applied twice.
- The DWH database is backed up and restored (ADR-0001, P0). Every backup run
  now writes `smartupcms_dwh-<timestamp>.dump.age` next to the CMS archive of
  the same timestamp, each with its checksum and manifest, and fails the status
  when either dump fails. `backup-bootstrap` creates the DWH database on an
  installation from before the DWH (init-dwh.sh ran only on an empty data
  directory) and gives the backup role read access to every DWH schema,
  including those that later migrations add. `restore.sh`, `restore.ps1` and
  `restore-combined.ps1` take the DWH archive and restore it with the
  application role as owner. `backup-bootstrap` itself failed on every run
  since 2026-09-18: psql variables inside a `DO $$ … $$` body are not
  substituted, and `pg_sequences` has no `sequence_name` column — so a
  deployment over existing data stopped at the pre-migration backup. CI now
  runs `scripts/prod/test-backup-databases.sh` against a real PostgreSQL.
- Checkbox and the new fields tell Signal Forms when they are touched: Angular
  22 listens to a `touch` output, and the kit's `touchedChange` never reached
  the form, so a checkbox's required error could stay hidden.

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

- 102 localization keys nothing uses any more (784 entries across the eight
  catalogs): old toasts and confirmations, retired navigation, notes and task
  labels, superseded kit texts. A key counts as used when its text appears in
  web or server code, or it falls under a prefix the code composes at run time
  (`upl.err.` + code and the like). The feature i18n helper takes catalogs in
  its own spec, which no longer depends on a dead key, and checks that the
  notes catalogs hold no dead copy.
- `ui-searchable-select`, replaced everywhere by `smt-select`.
- `ui-user-multi-select`, replaced by `smt-multi-select`.
- Control Plane, fleet management, heartbeat, enrollment, and license gates.

### Security

- `qs` 6.15.3 → 6.16.0 in the web lockfile (moderate: array-limit bypass and a
  denial of service through `isBuffer`). It came only through the Angular CLI
  toolchain (MCP SDK → express), never into the bundle; `npm audit` is clean in
  `apps/web` and `e2e`.
- Personal settings are limited to `user.*` and `ui.*` keys: saving any other
  key as a personal setting is rejected with 422 `SETTING_NOT_PERSONAL`, and a
  stored personal value no longer shadows an instance setting, so nobody can
  switch their own idle lock or other security settings off.

- Pinned ClamAV to `clamav/clamav-debian:1.5.4` (Debian 13.7) by digest.
  The previous pin, 1.5.3 on Debian 13.6, carried 44 HIGH/CRITICAL fixable
  vulnerabilities (perl-base, openssl, util-linux, pcre2, sqlite and others),
  which failed the runtime image gate and with it the end-to-end job.

[Unreleased]: https://github.com/qahhor/dwh/commits/main
