# Extractable components

## AppShell

- Source: `apps/web/src/app/layout/app-shell/app-shell.component.ts`
- Category: layout
- Description: Persistent sidebar, top bar and content shell shared by authenticated routes.
- Extractable props: activeRoute, collapsed, notificationCount, currentLanguage, currentTheme.
- Hardcoded: SmartupCMS identity, navigation groups, Material Symbol names and structural CSS.

## CommandPalette

- Source: `apps/web/src/app/layout/command-palette/command-palette.component.ts`
- Category: layout
- Description: Global search and command overlay.
- Extractable props: open, query, selectedIndex.
- Hardcoded: overlay structure, result layout and keyboard hints.

## UiButton

- Source: `apps/web/src/app/shared/ui/ui-button.component.ts`
- Category: basic
- Description: Shared action button.
- Extractable props: variant, size, loading, disabled, type.
- Hardcoded: spinner, spacing, focus and variant styles.

## UiModal

- Source: `apps/web/src/app/shared/ui/ui-modal.component.ts`
- Category: basic
- Description: Accessible modal with header/body/footer slots.
- Extractable props: open, title, size, closeOnOverlay, closeOnEscape.
- Hardcoded: overlay, close icon and focus behavior.

## UiBadge

- Source: `apps/web/src/app/shared/ui/ui-badge.component.ts`
- Category: basic
- Description: Semantic status badge.
- Extractable props: variant, dot.
- Hardcoded: pill geometry and semantic token mapping.

## UiPagination

- Source: `apps/web/src/app/shared/ui/ui-pagination.component.ts`
- Category: basic
- Description: Shared paging controls and page-size selector.
- Extractable props: page, pageSize, total, disabled.
- Hardcoded: navigation icon names and responsive structure.

## UiSearchableSelect

- Source: `apps/web/src/app/shared/ui/ui-searchable-select.component.ts`
- Category: basic
- Description: Searchable single-select form control.
- Extractable props: options, value, placeholder, disabled, clearable.
- Hardcoded: dropdown geometry and search icon.

## UiUserMultiSelect

- Source: `apps/web/src/app/shared/ui/ui-user-multi-select.component.ts`
- Category: basic
- Description: User multi-select with tags and filtered dropdown.
- Extractable props: users, selectedIds, placeholder, disabled.
- Hardcoded: tag structure and user avatar treatment.

## UiFileUpload

- Source: `apps/web/src/app/shared/ui/ui-file-upload.component.ts`
- Category: basic
- Description: Drag-and-drop and file-picker upload surface.
- Extractable props: disabled, multiple, accept, maxSize.
- Hardcoded: upload iconography and progress layout.

## UiMarkdownEditor

- Source: `apps/web/src/app/shared/ui/ui-markdown-editor.component.ts`
- Category: basic
- Description: Markdown editing and preview surface.
- Extractable props: value, disabled, placeholder, rows.
- Hardcoded: toolbar actions and preview structure.

## UiToast

- Source: `apps/web/src/app/shared/ui/ui-toast.component.ts`
- Category: basic
- Description: Global transient feedback stack.
- Extractable props: items.
- Hardcoded: placement, semantic icons and dismissal behavior.
