/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/types/modal.types.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import type { TemplateRef } from '@angular/core';
import type { Observable } from 'rxjs';

export interface SMTModalData {
  title?: string;
  showCloseButton?: boolean;
  /** Template to render in the modal body when opened via service. Receives `close` and `save` in its context. */
  content?: TemplateRef<unknown>;
  /** Optional callback when close/cancel is clicked */
  onClose?: () => void;
  /** Optional callback when save is clicked */
  onSave?: () => void;
}

export interface SMTModalConfig {
  /** Width of the modal (e.g. '500px', '90vw') */
  width?: string;
  /** Min width */
  minWidth?: string;
  /** Max width */
  maxWidth?: string;
  /** Max height (e.g. '90vh') */
  maxHeight?: string;
  /** Data to inject into the modal component */
  data?: unknown;
  /** Whether clicking the backdrop closes the modal */
  closeOnBackdropClick?: boolean;
  /** Whether pressing Escape closes the modal */
  closeOnEscape?: boolean;
  /**
   * Accessible name of the dialog when the opened content has no heading to
   * point at. Not in the kit: CDK Dialog then leaves the dialog unnamed.
   */
  ariaLabel?: string;
  /** Id of the element naming the dialog. Not in the kit (see `ariaLabel`). */
  ariaLabelledBy?: string;
  /** Id of the element describing the dialog. Not in the kit. */
  ariaDescribedBy?: string;
  /** `alertdialog` for a question that interrupts the flow. Not in the kit. */
  role?: 'dialog' | 'alertdialog';
  /** Asked before a backdrop click or Escape closes the dialog; false keeps it open. Not in the kit. */
  canDismiss?: () => boolean;
}

/** Config for confirm - prompts user with Yes/No, returns Observable<boolean> */
export interface SMTModalConfirmConfig {
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
  /**
   * Marks the confirming action as destructive (delete, discard): the Yes
   * button takes the danger style. Not in the kit.
   */
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
  /** Width of the modal (e.g. '400px') */
  width?: string;
  /** Min width */
  minWidth?: string;
  /** Max width */
  maxWidth?: string;
  /** Whether clicking the backdrop closes the modal (with false) */
  closeOnBackdropClick?: boolean;
  /** Whether pressing Escape closes the modal (with false) */
  closeOnEscape?: boolean;
}
