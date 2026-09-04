# Page dependency trees

Every authenticated page is rendered inside `AppShellComponent`, which depends
on authentication, theme, notification, permission, i18n, command-palette and
toast services. Trees below list UI-touching local dependencies for the ten
principal routes.

## /tasks

Entry: `apps/web/src/app/features/tasks/tasks.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
  - `apps/web/src/app/layout/command-palette/command-palette.component.ts`
  - `apps/web/src/app/shared/ui/ui-toast.component.ts`
- `apps/web/src/app/features/tasks/tasks.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`
  - `apps/web/src/app/shared/ui/ui-custom-fields.component.ts`
  - `apps/web/src/app/shared/ui/ui-searchable-select.component.ts`
  - `apps/web/src/app/shared/ui/ui-user-multi-select.component.ts`
  - `apps/web/src/app/shared/ui/ui-markdown-editor.component.ts`
  - `apps/web/src/app/shared/ui/ui-markdown-view.component.ts`
  - `apps/web/src/app/shared/ui/ui-pagination.component.ts`
  - `apps/web/src/app/shared/ui/ui-file-upload.component.ts`

## /tasks/projects

Entry: `apps/web/src/app/features/tasks/projects/projects.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/tasks/projects/projects.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`
  - `apps/web/src/app/shared/ui/ui-pagination.component.ts`

## /analytics

Entry: `apps/web/src/app/features/analytics/analytics.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/analytics/analytics.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-badge.component.ts`

## /iam/users

Entry: `apps/web/src/app/features/iam/users/users.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/iam/users/users.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-badge.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`
  - `apps/web/src/app/shared/ui/ui-custom-fields.component.ts`
  - `apps/web/src/app/shared/ui/ui-pagination.component.ts`

## /iam/roles

Entry: `apps/web/src/app/features/iam/roles/roles.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/iam/roles/roles.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`

## /iam/custom-fields

Entry: `apps/web/src/app/features/iam/custom-fields/custom-fields.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/iam/custom-fields/custom-fields.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`

## /iam/profile

Entry: `apps/web/src/app/features/iam/profile/profile.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/iam/profile/profile.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-badge.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`

## /files

Entry: `apps/web/src/app/features/files/files.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/files/files.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-file-upload.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`
  - `apps/web/src/app/shared/ui/ui-pagination.component.ts`

## /audit

Entry: `apps/web/src/app/features/audit/audit.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/audit/audit.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`
  - `apps/web/src/app/shared/ui/ui-pagination.component.ts`

## /settings

Entry: `apps/web/src/app/features/settings/settings.component.ts`

- `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- `apps/web/src/app/features/settings/settings.component.ts`
  - `apps/web/src/app/shared/ui/ui-button.component.ts`
  - `apps/web/src/app/shared/ui/ui-modal.component.ts`
  - `apps/web/src/app/core/services/i18n.service.ts`

## Global theme

- `apps/web/src/styles.css`
- `apps/web/src/app/core/services/theme.service.ts`
