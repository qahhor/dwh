/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path forms/field-registry/field-registry.token.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { InjectionToken, type WritableSignal } from '@angular/core';

/**
 * Live handle for a UI control bound through Angular signal forms `[formField]`.
 *
 * Neutral on purpose: no page / plugin / kernel concepts — only the field key and its
 * live value signal. Host apps may provide {@link UI_KIT_FIELD_REGISTRY} to observe these.
 */
export interface UiKitFieldHandle {
  /** The field's property key in its parent — `FieldState.keyInParent()`. */
  readonly key: string;
  /**
   * The bound field's live value. Read via `value()`, write via `value.set(...)`.
   *
   * Typed as {@link WritableSignal} because signal-forms `FieldState.value` is writable
   * at runtime (public `FieldState` surfaces it as a read `Signal`).
   */
  readonly value: WritableSignal<unknown>;
}

/**
 * Optional host-owned registry. When provided, form controls announce bound fields.
 * When absent, controls behave exactly as before.
 */
export interface UiKitFieldRegistry {
  register(handle: UiKitFieldHandle): void;
  unregister(handle: UiKitFieldHandle): void;
}

export const UI_KIT_FIELD_REGISTRY = new InjectionToken<UiKitFieldRegistry>('UI_KIT_FIELD_REGISTRY');
