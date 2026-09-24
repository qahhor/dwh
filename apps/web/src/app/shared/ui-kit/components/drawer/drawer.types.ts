/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/drawer/drawer.service.ts
 * (the config, ref and tokens were split out of it). Per ADR-0015 this copy
 * is ours to change. See NOTICE. */
import { InjectionToken } from '@angular/core';
import type { Observable } from 'rxjs';

export interface SMTDrawerConfig<D = unknown> {
  /** Panel width, e.g. '480px' or '60vw'. Capped at the viewport; full width on phones. */
  width?: string;
  /** Injected into the content component as SMT_DRAWER_DATA. */
  data?: D;
  /** Heading shown in the drawer header; it also names the dialog. Not in the kit. */
  title?: string;
  /** Accessible name when there is no `title`. Not in the kit. */
  ariaLabel?: string;
  closeOnEscape?: boolean;
  /**
   * Close when the user clicks the dimmed area beside the drawer.
   * Default `false`.
   */
  closeOnBackdropClick?: boolean;
  /**
   * Close when the app URL changes (Router.navigate / browser back).
   * Default `true`. Uses `Location.onUrlChange` — CDK `disposeOnNavigation` alone only
   * reacts to popstate, not programmatic navigation.
   */
  closeOnNavigation?: boolean;
  /** Handles backdrop, close-button and Escape close requests before the drawer is disposed. */
  onCloseRequest?: () => void;
}

export interface SMTDrawerRef<R = unknown> {
  close: (result?: R) => void;
  /**
   * Emits once with the result when the drawer closes. The kit returned the
   * Subject itself, so callers could complete it; this is read-only.
   */
  afterClosed: () => Observable<R | undefined>;
  componentInstance: unknown;
}

export const SMT_DRAWER_REF = new InjectionToken<SMTDrawerRef>('SMT_DRAWER_REF');
export const SMT_DRAWER_DATA = new InjectionToken<unknown>('SMT_DRAWER_DATA');
/** The drawer's own config, for its container component. */
export const SMT_DRAWER_CONFIG = new InjectionToken<SMTDrawerConfig>('SMT_DRAWER_CONFIG');
