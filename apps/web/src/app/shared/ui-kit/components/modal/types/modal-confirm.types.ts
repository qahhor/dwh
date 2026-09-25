/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/types/modal-confirm.types.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */

import type { Observable } from 'rxjs';

/** Data injected into SMTModalConfirmComponent */
export interface SMTModalConfirmData {
  /** Question or message to show. Rendered as text, never as HTML. */
  message: string;
  /** Modal title */
  title?: string;
  /** Label for Yes button */
  yesLabel?: string;
  /** Label for No button */
  noLabel?: string;
  /** Label for Cancel button */
  cancelLabel?: string;
  /** Disable the confirm button until the countdown reaches zero */
  timer?: number | string;
  /** The confirming action is destructive; Yes takes the danger style. Not in the kit. */
  destructive?: boolean;
  /**
   * The work Yes starts. While it runs the dialog stays open with its buttons
   * disabled and cannot be dismissed; it closes (and `confirm()` emits true)
   * when the work completes, and on an error it stays open with the message
   * from `actionError`, so the person can retry or decline. Not in the kit.
   */
  action?: () => Observable<unknown>;
  /** The message shown when `action` fails; a generic one when not given. Not in the kit. */
  actionError?: (error: unknown) => string;
  /** Optional callback when Yes is clicked */
  onConfirm?: () => void;
  /** Optional callback when No is clicked */
  onDecline?: () => void;
  /** Optional callback when Cancel is clicked or the dialog is dismissed */
  onCancel?: () => void;
  /** Ids the template uses so the dialog is named and described by them. Not in the kit. */
  titleId: string;
  messageId: string;
}

export type SMTModalConfirmAction = 'confirm' | 'decline' | 'cancel';

export interface SMTModalConfirmCloseResult {
  action: SMTModalConfirmAction;
}
