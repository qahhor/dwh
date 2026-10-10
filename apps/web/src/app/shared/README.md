# shared: where to find a component

Plan 10/10, item 2.6. One component per task: look it up here before writing
a new one, and add a row when a new shared component lands.

## Prefixes

The prefix of a selector says where the component lives; ESLint enforces it
(`prefixRule` in `eslint.config.mjs`).

| Prefix | What                                                                                | Where                                |
| ------ | ----------------------------------------------------------------------------------- | ------------------------------------ |
| `smt-` | kit primitives (controls, table, dialog, badge) and the entity framework (ADR-0019) | `shared/ui-kit`, `shared/entity`     |
| `ui-`  | application blocks built from primitives: server table, filters, headers, cards     | `shared/ui` and the rest of `shared` |
| `app-` | screens and their parts                                                             | `features`, `layout`                 |

A feature never imports another feature; what two features need goes to
`shared` (a component) or `core` (a service, a model).

## Task → component

| Task                                                              | Component                                                                                                                           | Notes                                                                                         |
| ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Screen header: title, counter, tabs beside it, actions            | `ui-page-header`                                                                                                                    | `pageHeaderAside` marks what follows the title                                                |
| A list from a keyset API (server paging, sort, columns, views)    | `ui-server-table`                                                                                                                   | with `KeysetPager` (`shared/paging`)                                                          |
| A list loaded whole (settings, a card's rows)                     | `ui-local-table`                                                                                                                    | sorts locally                                                                                 |
| The table itself inside the two above                             | `smt-table`                                                                                                                         | not used directly on a screen                                                                 |
| Filter button and its panel for a registry list                   | `ui-filter-bar`, `ui-filter-panel`                                                                                                  | conditions from `query-meta`                                                                  |
| Saved views of a list                                             | `ui-list-views`                                                                                                                     | with `ListViewState` (`shared/list-views`)                                                    |
| Export a list to Excel                                            | `ui-export-button`                                                                                                                  | the list as on screen (ADR-0018)                                                              |
| An entity's form, card, toolbar                                   | `smt-entity-form`, `smt-entity-card`, `smt-entity-toolbar`                                                                          | from `form-meta` (ADR-0019)                                                                   |
| A whole screen of a declared entity: list, form, record           | the route `/e/:code` (`shared/entity/page`), no screen code                                                                         | tweaks by key: `provideEntityOverrides` (ADR-0032 7.1, 7.2)                                   |
| Key figure (count, percentage) with an icon and a note            | `ui-kpi-card`                                                                                                                       | compares with a previous value when given                                                     |
| Dashboard widget with loading/empty/error                         | `ui-dashboard-card`                                                                                                                 |                                                                                               |
| Bar chart                                                         | `ui-bar-chart`                                                                                                                      |                                                                                               |
| Status label                                                      | `smt-badge`                                                                                                                         | `smtVariant`: success, error, warning, blue, gray (and the kit palette); content is projected |
| Record change history                                             | `ui-record-history`                                                                                                                 | from the audit log (ADR-0017)                                                                 |
| Result of a bulk action with the failed records                   | `ui-bulk-result`                                                                                                                    |                                                                                               |
| Custom fields of a record in a form                               | `ui-custom-fields`                                                                                                                  | draws each with `smt-dynamic-field`                                                           |
| File upload queue                                                 | `ui-file-upload`                                                                                                                    |                                                                                               |
| Markdown: edit / show                                             | `ui-markdown-editor` / `ui-markdown-view`                                                                                           |                                                                                               |
| Page numbers for a local list                                     | `ui-pagination`                                                                                                                     | server lists page through `ui-server-table`                                                   |
| Dialog                                                            | `smt-dialog` (declarative), `SMTModalService.confirm()` (a question)                                                                |                                                                                               |
| Text, number, date, time, select, lookup, switch, checkbox, radio | `smt-input`, `smt-date-picker`, `smt-time-picker`, `smt-select`, `smt-data-select`, `smt-switch`, `smt-checkbox`, `smt-radio-group` | `shared/ui-kit/components/forms`                                                              |
| Tabs                                                              | `smt-tab-bar`                                                                                                                       |                                                                                               |
| Button                                                            | `button[smt-button]`                                                                                                                |                                                                                               |
| Notices on the page                                               | `smt-alert`                                                                                                                         | a toast only for what outlives the screen                                                     |

## Forms

Plan 10/10, item 2.8; ESLint refuses `FormsModule`, `ReactiveFormsModule` and
`NgModel` outside `shared/ui-kit`.

- An entity declared on the server: `smt-entity-form` (form, rules and
  actions come from `form-meta`).
- A form with its own rules: Signal Forms. `form(model, schema)` with
  validators, `[formField]` on the kit control, `smt-control` for the label
  and the error, `markSMTFormFieldsTouched(form)` before saving.
- A filter, a search box or a single setting: bind the kit control directly,
  `[(value)]="query"` or `[value]` + `(valueChange)`; `(edited)` fires on every
  keystroke when the screen needs that.

The look and behaviour of every form follow
`docs/guidelines/forms-ux-standard.md` (`npm run forms:audit`, part of
`npm run lint`, checks its mechanical rules). Its building blocks:

| Task                                                        | Block                                                  |
| ----------------------------------------------------------- | ------------------------------------------------------ |
| Button row: Cancel, then the primary action; spinner; guard | `ui-form-actions` (`footer` in a dialog)               |
| Errors collected above the form (3 and more, or unplaced)   | `ui-form-error-summary`                                |
| Focus the first invalid field on submit                     | `form[uiFocusFirstInvalid]`, `focusFirstInvalid()`     |
| Field errors of a refused request by form field             | `problemFieldErrors()` (`shared/ui/problem-fields.ts`) |

## Requests

A component asks its feature's typed data service (`<feature>.api.ts`), not
`ApiService`; shared blocks use `RefLookups`, `EntitiesApi`,
`RecordHistoryApi`, and `core` holds what several features read
(`RolesApi`, `CustomFieldsApi`). The module guide shows the reference screen:
`docs/guidelines/module-development-guide.md`.
