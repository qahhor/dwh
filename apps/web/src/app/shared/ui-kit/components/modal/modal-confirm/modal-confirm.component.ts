/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/modal-confirm/modal-confirm.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE.
 *
 * Differences from the kit: the message is bound as text (the kit bound it
 * with `[innerHTML]`, so a server string could inject markup); the title and
 * message carry the ids the dialog is labelled and described by; focus
 * starts on the declining button, so Enter pressed by habit never confirms;
 * `destructive` gives the confirming button the danger style; native buttons
 * styled from our tokens replace the kit button; an `action` keeps the
 * dialog open and busy until the work is done and shows its error. */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SMTI18nService } from '../../../i18n';
import type { SMTModalConfirmCloseResult, SMTModalConfirmData } from '../types/modal-confirm.types';

@Component({
  selector: 'smt-modal-confirm',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './modal-confirm.component.html',
  styleUrl: '../modal.scss',
  host: {
    class: 'smt-modal-confirm',
  },
})
export class SMTModalConfirmComponent {
  private readonly dialogRef = inject(DialogRef<SMTModalConfirmCloseResult>);

  private readonly data = inject<SMTModalConfirmData>(DIALOG_DATA);

  private readonly destroyRef = inject(DestroyRef);

  private readonly i18n = inject(SMTI18nService);

  readonly countdown = signal(0);

  /** The confirmed action is running; nothing else may close the dialog meanwhile. */
  readonly busy = signal(false);

  /** Why the last attempt failed, shown under the message. */
  readonly failure = signal('');

  readonly title = computed(() => this.data.title ?? this.i18n.messages().modalConfirm.title);

  readonly message = computed(() => this.data.message);

  readonly yesLabel = computed(() => this.data.yesLabel ?? this.i18n.messages().modalConfirm.yes);

  readonly noLabel = computed(() => this.data.noLabel ?? this.i18n.messages().modalConfirm.no);

  readonly cancelLabel = computed(() => this.data.cancelLabel ?? this.i18n.messages().common.cancel);

  readonly hasCancel = computed(() => !!this.data.cancelLabel || !!this.data.onCancel);

  readonly isConfirmDisabled = computed(() => this.countdown() > 0 || this.busy());

  readonly titleId = this.data.titleId;

  readonly messageId = this.data.messageId;

  readonly destructive = !!this.data.destructive;

  private timerId: ReturnType<typeof setInterval> | null = null;

  constructor() {
    this.startTimer();
    this.destroyRef.onDestroy(() => this.clearTimer());
  }

  confirm(): void {
    if (this.isConfirmDisabled()) return;
    const action = this.data.action;
    if (!action) {
      this.dialogRef.close({ action: 'confirm' });
      return;
    }
    this.busy.set(true);
    this.failure.set('');
    action().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      complete: () => this.dialogRef.close({ action: 'confirm' }),
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(this.data.actionError?.(error) || this.i18n.messages().modalConfirm.failed);
      },
    });
  }

  decline(): void {
    if (this.busy()) return;
    this.dialogRef.close({ action: 'decline' });
  }

  cancel(): void {
    if (this.busy()) return;
    this.dialogRef.close({ action: 'cancel' });
  }

  private startTimer(): void {
    const timer = this.parseTimer(this.data.timer);
    if (timer <= 0) return;

    this.countdown.set(timer);
    this.timerId = setInterval(() => {
      const nextValue = this.countdown() - 1;
      if (nextValue <= 0) {
        this.countdown.set(0);
        this.clearTimer();
        return;
      }

      this.countdown.set(nextValue);
    }, 1000);
  }

  private clearTimer(): void {
    if (this.timerId === null) return;

    clearInterval(this.timerId);
    this.timerId = null;
  }

  private parseTimer(value: number | string | undefined): number {
    const parsed = Number.parseInt(`${value ?? 0}`, 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
  }
}
