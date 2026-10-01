import { Injectable, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, catchError, finalize, of, tap } from 'rxjs';
import { lastLoaded } from '@features/iam/last-loaded';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { UserSecuritySummary } from '@core/models/auth.models';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';

@Injectable({
  providedIn: 'root',
})
export class UserSecurityService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly isSecurityActionPending = signal<boolean>(false);
  /** Whose summary is asked; a new object asks again for the same user. */
  private readonly summaryOf = signal<{ userId: number } | undefined>(undefined);
  readonly isLoadingSecurity = computed(() => this.summaryRead.isLoading());

  /* A read only: reloading after a security action asks for the summary again and never
     repeats the action. Asking for another user drops the answer still due for the previous one. */
  private readonly summaryRead = rxResource({
    params: () => this.summaryOf(),
    stream: ({ params }) =>
      this.api.get<UserSecuritySummary>(`/iam/users/${params.userId}/security`).pipe(catchError(() => of(null))),
  });
  /** The screen clears it when it shows another user; a failed load keeps the one on screen. */
  readonly userSecurity = lastLoaded<UserSecuritySummary | null>(() => this.summaryRead.value(), null);

  loadUserSecurity(userId: number): void {
    this.summaryOf.set({ userId });
  }

  terminateUserSessions(userId: number): void {
    this.askThenRun({
      title: 'iam.users.terminate_all_sessions',
      message: 'iam.users.security.end_all_sessions_confirm',
      destructive: true,
      request: () => this.api.delete(`/iam/users/${userId}/sessions`, { notifyError: false }),
      success: 'iam.users.security.all_sessions_ended',
      userId,
    });
  }

  terminateSingleSession(sessionId: number, userId: number): void {
    this.isSecurityActionPending.set(true);
    this.api.delete(`/iam/users/${userId}/sessions/${sessionId}`).subscribe({
      next: () => {
        this.isSecurityActionPending.set(false);
        this.toast.success(this.uiI18n.translate('iam.users.security.session_ended'));
        this.loadUserSecurity(userId);
      },
      error: () => this.isSecurityActionPending.set(false),
    });
  }

  forcePasswordChange(userId: number, onComplete?: () => void): void {
    this.askThenRun({
      title: 'iam.common.force_password_change',
      message: 'iam.users.security.force_password_change_confirm',
      destructive: false,
      request: () => this.api.post(`/iam/users/${userId}/force-password-change`, undefined, { notifyError: false }),
      success: 'iam.users.security.password_change_forced',
      userId,
      onComplete,
    });
  }

  resetUser2fa(userId: number, onComplete?: () => void): void {
    this.askThenRun({
      title: 'iam.users.reset_two_factor',
      message: 'iam.users.security.reset_two_factor_confirm',
      destructive: true,
      request: () => this.api.post(`/iam/users/${userId}/reset-2fa`, undefined, { notifyError: false }),
      success: 'iam.users.security.two_factor_reset',
      userId,
      onComplete,
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
    this.modal
      .confirm({
        title: this.uiI18n.translate(ask.title),
        message: this.uiI18n.translate(ask.message),
        yesLabel: this.uiI18n.translate('iam.users.security.confirm'),
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
            finalize(() => this.isSecurityActionPending.set(false)),
          );
        },
        actionError: problemText,
      })
      .subscribe();
  }
}
