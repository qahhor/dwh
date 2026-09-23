/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path types/control-size.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
/**
 * Unified interactive control scale used across buttons, fields, select triggers, etc.
 *
 * Row height is **fixed** (`h-*` / `min-h-*`); typography stays default for `text-xs` / `text-sm`.
 * Content is vertically centered with `items-center` (no vertical padding for row sizing).
 *
 * - **sm**: **24px** — `h-6` / `min-h-6`
 * - **md**: **30px** (default) — `h-7.5` / `min-h-7.5`
 * - **lg**: **36px** — `h-9` / `min-h-9`
 */
export type SMTControlSize = 'sm' | 'md' | 'lg';
