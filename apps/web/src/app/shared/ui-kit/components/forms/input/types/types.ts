/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/forms/input/types/types.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { SMTIcons } from '../../../../types/svg-icons.type';
import type { SMTControlSize } from '../../../../types/control-size';

export type InputType =
  | 'text'
  | 'password'
  | 'email'
  | 'number'
  | 'date'
  | 'checkbox'
  | 'radio'
  | 'submit'
  | 'reset'
  | 'button'
  | 'file'
  | 'color'
  | 'range'
  | 'tel'
  | 'url'
  | 'search'
  | 'datetime-local'
  | 'hidden';

export type IconColor = 'default' | 'danger';

export type IconType = SMTIcons | { key: SMTIcons; width?: number | string; height?: string | number } | null;

/** Field row scale; same tokens as {@link SMTControlSize}. */
export type InputSize = SMTControlSize;

/** Внешний вид поля: принудительно светлый/тёмный или `auto` из `SMTThemeService.resolvedTheme()`. */
export type SMTInputAppearance = 'light' | 'dark' | 'auto';

export type SMTInputBehavior = 'biruni-number' | 'readonly-link' | null;

export type SMTInputNumberMode = 'signed' | 'unsigned';
