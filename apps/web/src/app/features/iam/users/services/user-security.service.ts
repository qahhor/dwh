import { Injectable, inject, signal } from '@angular/core';
import { Observable, finalize, tap } from 'rxjs';
import { ApiService } from '../../../../core/services/api.service';
import { ToastService } from '../../../../core/services/toast.service';
import { I18nService } from '../../../../core/services/i18n.service';
import { UserSecuritySummary } from '../../../../core/models/auth.models';
import { SMTModalService } from '../../../../shared/ui-kit/components/modal';
import { problemText } from '../../../../shared/ui/problem-text';

@Injectable({
  providedIn: 'root'
})
export class UserSecurityService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly userSecurity = signal<UserSecuritySummary | null>(null);
  readonly isLoadingSecurity = signal<boolean>(false);
  readonly isSecurityActionPending = signal<boolean>(false);

  loadUserSecurity(userId: number): void {
    this.isLoadingSecurity.set(true);
    this.api.get<UserSecuritySummary>(`/iam/users/${userId}/security`).subscribe({
      next: (res) => {
        this.userSecurity.set(res);
        this.isLoadingSecurity.set(false);
      },
      error: () => {
        this.isLoadingSecurity.set(false);
      }
    });
  }

  terminateUserSessions(userId: number): void {
    this.askThenRun({
      title: 'iam.zavershit_vse_sessii',
      message: 'iam.podtverdit_zavershenie_vseh_sessiy',
      destructive: true,
      request: () => this.api.delete(`/iam/users/${userId}/sessions`, { notifyError: false }),
      success: 'iam.vse_sessii_zaversheny',
      userId
    });
  }

  terminateSingleSession(sessionId: number, userId: number): void {
    this.isSecurityActionPending.set(true);
    this.api.delete(`/iam/users/${userId}/sessions/${sessionId}`).subscribe({
      next: () => {
        this.isSecurityActionPending.set(false);
        this.toast.success(this.uiI18n.translate('iam.sessiya_zavershena'));
        this.loadUserSecurity(userId);
      },
      error: () => this.isSecurityActionPending.set(false)
    });
  }

  forcePasswordChange(userId: number, onComplete?: () => void): void {
    this.askThenRun({
      title: 'iam.trebovanie_smeny_parolya',
      message: 'iam.podtverdit_trebovanie_smeny_parolya',
      destructive: false,
      request: () => this.api.post(`/iam/users/${userId}/force-password-change`, undefined, { notifyError: false }),
      success: 'iam.smena_parolya_potrebovana',
      userId,
      onComplete
    });
  }

  resetUser2fa(userId: number, onComplete?: () => void): void {
    this.askThenRun({
      title: 'iam.sbrosit_2fa',
      message: 'iam.podtverdit_sbros_2fa',
      destructive: true,
      request: () => this.api.post(`/iam/users/${userId}/reset-2fa`, undefined, { notifyError: false }),
      success: 'iam.2fa_sbroshena',
      userId,
      onComplete
    });
  }

  /**
   * Asks before a security action and runs it from the dialog: it stays open
   * while the request runs and shows the server's reason if it fails.
   */
  private askThenRun(ask: {
    title: string;
    message: string;
    destructive: boolean;
    request: () => Observable<unknown>;
    success: string;
    userId: number;
    onComplete?: () => void;
  }): void {
    this.modal.confirm({
      title: this.uiI18n.translate(ask.title),
      message: this.uiI18n.translate(ask.message),
      yesLabel: this.uiI18n.translate('iam.vypolnit'),
      noLabel: this.uiI18n.translate('common.cancel'),
      destructive: ask.destructive,
      action: () => {
        this.isSecurityActionPending.set(true);
        return ask.request().pipe(
          tap(() => {
            this.toast.success(this.uiI18n.translate(ask.success));
            this.loadUserSecurity(ask.userId);
            ask.onComplete?.();
          }),
          finalize(() => this.isSecurityActionPending.set(false))
        );
      },
      actionError: problemText
    }).subscribe();
  }
}
