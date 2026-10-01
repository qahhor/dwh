import { ChangeDetectionStrategy, Component, inject, signal, input, output } from '@angular/core';

import { PasswordApi } from '@features/auth/password.api';
import { ToastService } from '@core/services/toast.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-login-reset-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, SMTDialogComponent, SMTDialogContentDirective, SMTButtonComponent, TranslatePipe],
  template: `
    <smt-dialog [open]="isOpen()" [smtTitle]="'auth.reset_request.title' | t" smtSize="sm" (closed)="onClose()">
      <ng-template smtDialogContent>
        <div body class="reset-body">
          <p id="reset-hint" class="reset-hint">{{ 'auth.reset.request_hint' | t }}</p>
          <div class="form-group">
            <label class="form-label" for="reset-email">Email</label>
            <smt-input
              smtFieldId="reset-email"
              name="resetEmail"
              type="email"
              [(value)]="resetEmail"
              placeholder="user@company.com"
              autocomplete="email"
              smtDescribedBy="reset-hint"
              [smtInvalid]="resetError() ? 'true' : null"
            />
          </div>
          @if (resetError()) {
            <p class="form-error" role="alert">{{ resetError() }}</p>
          }
        </div>
        <div footer>
          <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="onClose()">
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="primary"
            smtSize="md"
            [smtLoading]="isResetLoading()"
            (click)="sendResetRequest()"
          >
            {{ 'auth.reset.send_link' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './login-reset-modal.component.css',
})
export class LoginResetModalComponent {
  private readonly passwordApi = inject(PasswordApi);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly isOpen = input(false);

  readonly closeModal = output<void>();

  readonly resetError = signal<string>('');
  readonly isResetLoading = signal<boolean>(false);

  readonly resetEmail = signal('');
  onClose(): void {
    this.resetEmail.set('');
    this.resetError.set('');
    this.closeModal.emit();
  }

  sendResetRequest(): void {
    if (!this.resetEmail()) return;
    this.resetError.set('');
    this.isResetLoading.set(true);
    this.passwordApi.requestReset(this.resetEmail()).subscribe({
      next: () => {
        this.isResetLoading.set(false);
        this.onClose();
        this.toast.success(this.i18n.translate('auth.reset.request_sent'));
      },
      error: (err) => {
        this.isResetLoading.set(false);
        this.resetError.set(this.errorMessage(err, this.i18n.translate('auth.reset_request.send_failed')));
      },
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error && typeof error === 'object') {
      const value = error as { detail?: unknown; message?: unknown };
      if (typeof value.detail === 'string' && value.detail.trim()) return value.detail;
      if (typeof value.message === 'string' && value.message.trim()) return value.message;
    }
    return fallback;
  }
}
