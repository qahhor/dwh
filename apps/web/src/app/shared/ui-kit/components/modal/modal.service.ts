/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/modal.service.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit:
 * - Sizes go to CDK Dialog only. The kit also built Tailwind classes such as
 *   `w-[${width}]` at run time; Tailwind never sees a class assembled from a
 *   variable, so they did nothing.
 * - Panel and backdrop are styled by `modal.scss` from our tokens, not by
 *   the kit palette, so both themes work.
 * - The confirm dialog is an `alertdialog` named by its title and described
 *   by its message; the kit left it an unnamed `dialog`.
 * - `confirm()` takes an `action`: the dialog stays open and busy until the
 *   work completes, cannot be dismissed meanwhile and shows its error.
 * - `SMTModalConfig` lost `title`, `onClose`, `onSave` and `showCloseButton`:
 *   `open()` never read them. They belong in `data` for `SMTModalComponent`.
 */
import { filter, map, Observable, take, takeUntil } from 'rxjs';
import { Injectable, inject, TemplateRef, Type } from '@angular/core';
import { Dialog, DialogRef } from '@angular/cdk/dialog';
import { SMTModalConfirmComponent } from './modal-confirm/modal-confirm.component';
import { SMTI18nService } from '../../i18n';
import { SMTModalConfig, SMTModalConfirmConfig } from './types/modal.types';
import type {
  SMTModalConfirmAction,
  SMTModalConfirmCloseResult,
  SMTModalConfirmData,
} from './types/modal-confirm.types';

const PANEL_CLASS = 'smt-modal-panel';
const BACKDROP_CLASS = 'smt-modal-backdrop';

let nextConfirmId = 0;

@Injectable({
  providedIn: 'root',
})
export class SMTModalService {
  private readonly dialog = inject(Dialog);
  private readonly i18n = inject(SMTI18nService);

  open<T, R = unknown>(componentOrTemplate: Type<T> | TemplateRef<T>, config: SMTModalConfig = {}): DialogRef<R, T> {
    const { width, minWidth, maxWidth, maxHeight, data, closeOnBackdropClick = true, closeOnEscape = true } = config;

    const dialogRef = this.dialog.open(componentOrTemplate, {
      width: width ?? 'auto',
      minWidth: minWidth ?? '320px',
      maxWidth: maxWidth ?? 'calc(100vw - 32px)',
      maxHeight: maxHeight ?? '90vh',
      data,
      panelClass: PANEL_CLASS,
      backdropClass: BACKDROP_CLASS,
      hasBackdrop: true,
      disableClose: true,
      closeOnDestroy: true,
      role: config.role ?? 'dialog',
      ariaModal: true,
      ariaLabel: config.ariaLabel ?? null,
      ariaLabelledBy: config.ariaLabelledBy ?? null,
      ariaDescribedBy: config.ariaDescribedBy ?? null,
      viewContainerRef: config.viewContainerRef,
    }) as DialogRef<R, T>;

    this.bindDefaultCloseInteractions(dialogRef, closeOnBackdropClick, closeOnEscape, config.canDismiss);

    return dialogRef;
  }

  /**
   * Opens a confirmation modal with Yes/No buttons.
   * @returns Observable<boolean> - true if user clicks Yes, false if No/close/backdrop/Escape
   */
  confirm(config: SMTModalConfirmConfig): Observable<boolean> {
    const messages = this.i18n.messages();
    const {
      message,
      title = messages.modalConfirm.title,
      yesLabel = messages.modalConfirm.yes,
      noLabel = messages.modalConfirm.no,
      cancelLabel,
      timer,
      destructive = false,
      onConfirm,
      onDecline,
      onCancel,
      action,
      actionError,
      width = '440px',
      minWidth = '320px',
      maxWidth,
      closeOnBackdropClick = true,
      closeOnEscape = true,
    } = config;

    const id = nextConfirmId++;
    const data: SMTModalConfirmData = {
      title,
      message,
      yesLabel,
      noLabel,
      cancelLabel,
      timer,
      destructive,
      onConfirm,
      onDecline,
      onCancel,
      action,
      actionError,
      titleId: `smt-modal-confirm-title-${id}`,
      messageId: `smt-modal-confirm-message-${id}`,
    };

    const dialogRef: DialogRef<SMTModalConfirmCloseResult, SMTModalConfirmComponent> = this.open<SMTModalConfirmComponent, SMTModalConfirmCloseResult>(SMTModalConfirmComponent, {
      canDismiss: () => !dialogRef.componentInstance?.busy(),
      width,
      minWidth,
      maxWidth,
      closeOnBackdropClick,
      closeOnEscape,
      role: 'alertdialog',
      ariaLabelledBy: data.titleId,
      ariaDescribedBy: data.messageId,
      data,
    });

    return dialogRef.closed.pipe(
      take(1),
      map(result => this.resolveConfirmResult(result, { cancelLabel, onConfirm, onDecline, onCancel }))
    );
  }

  private bindDefaultCloseInteractions<T, R>(
    dialogRef: DialogRef<R, T>,
    closeOnBackdropClick: boolean,
    closeOnEscape: boolean,
    canDismiss: () => boolean = () => true
  ): void {
    if (closeOnBackdropClick) {
      dialogRef.backdropClick.pipe(takeUntil(dialogRef.closed)).subscribe(() => {
        if (canDismiss()) dialogRef.close();
      });
    }

    if (closeOnEscape) {
      dialogRef.keydownEvents
        .pipe(
          filter(event => event.key === 'Escape'),
          takeUntil(dialogRef.closed)
        )
        .subscribe(event => {
          // Marks the key as handled, so nothing underneath that listens on the          // document closes as well.
          event.preventDefault();
          if (canDismiss()) dialogRef.close();
        });
    }
  }

  private resolveConfirmResult(
    result: SMTModalConfirmCloseResult | undefined,
    config: Pick<SMTModalConfirmConfig, 'cancelLabel' | 'onConfirm' | 'onDecline' | 'onCancel'>
  ): boolean {
    const action = result?.action ?? this.getDismissAction(config);

    if (action === 'confirm') {
      config.onConfirm?.();
      return true;
    }

    if (action === 'cancel') {
      config.onCancel?.();
      return false;
    }

    config.onDecline?.();
    return false;
  }

  private getDismissAction(config: Pick<SMTModalConfirmConfig, 'cancelLabel' | 'onCancel'>): SMTModalConfirmAction {
    return config.cancelLabel || config.onCancel ? 'cancel' : 'decline';
  }
}
