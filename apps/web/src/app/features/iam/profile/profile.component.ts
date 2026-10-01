import { ChangeDetectionStrategy, Component, signal, computed, inject, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { AuthService } from '@core/services/auth.service';
import { ProfileApi } from './profile.api';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { Observable, catchError, finalize, map, of, tap } from 'rxjs';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';
import { lastLoaded } from '@features/iam/last-loaded';

import {
  UserSession,
  UserChannel,
  ApiToken,
  PasswordForm,
  PasswordStrength,
  TokenExpirationOption,
  passwordStrengthOf,
  tokenExpiresAt,
} from './profile.models';

import { UserProfileCardComponent } from './components/user-profile-card.component';
import { ProfilePasswordCardComponent } from './components/profile-password-card.component';
import { ProfileSecurityCardComponent } from './components/profile-security-card.component';
import { ProfileChannelsCardComponent } from './components/profile-channels-card.component';
import { ProfileSessionsCardComponent } from './components/profile-sessions-card.component';
import { ProfileTokensCardComponent } from './components/profile-tokens-card.component';

export * from './profile.models';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    TranslatePipe,
    UserProfileCardComponent,
    ProfilePasswordCardComponent,
    ProfileSecurityCardComponent,
    ProfileChannelsCardComponent,
    ProfileSessionsCardComponent,
    ProfileTokensCardComponent,
  ],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
})
export class ProfileComponent {
  authService = inject(AuthService);

  public readonly permissionService = inject(PermissionService);
  private profile = inject(ProfileApi);
  private toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly channelsCard = viewChild<ProfileChannelsCardComponent>('channelsCard');

  readonly isCreatingToken = signal<boolean>(false);
  readonly isTerminatingSession = signal<boolean>(false);
  readonly isBindingChannel = signal<boolean>(false);
  readonly isConfirmingChannel = signal<boolean>(false);
  readonly copiedSecret = signal<boolean>(false);

  readonly isCreateTokenModalOpen = signal<boolean>(false);
  readonly isTokenSecretModalOpen = signal<boolean>(false);
  readonly isChangingPassword = signal<boolean>(false);

  readonly isPasswordSubmitted = signal(false);
  readonly isTokenSubmitted = signal(false);

  readonly newTokenName = signal('');
  readonly selectedTokenExpiration = signal('90');
  readonly createdTokenSecret = signal('');

  readonly passwordForm = signal<PasswordForm>({
    oldPassword: '',
    newPassword: '',
    confirmPassword: '',
  });

  readonly isLoadingSessions = computed(() => this.sessionsRead.isLoading());
  readonly isLoadingTokens = computed(() => this.tokensRead.isLoading());
  readonly isLoadingChannels = computed(() => this.channelsRead.isLoading());

  readonly canManageChannels = computed(() => this.permissionService.hasPermission('md.profile', 'manage_channels'));

  /* Reads only: the reload after ending a session, revoking a token or changing a channel
     asks for the list again and never repeats the action. */
  private readonly sessionsRead = rxResource({ stream: () => listOrNull(this.profile.sessions()) });
  private readonly tokensRead = rxResource({ stream: () => listOrNull(this.profile.tokens()) });
  private readonly channelsRead = rxResource({ stream: () => listOrNull(this.profile.channels()) });

  readonly sessions = lastLoaded<UserSession[]>(() => this.sessionsRead.value(), []);
  readonly tokens = lastLoaded<ApiToken[]>(() => this.tokensRead.value(), []);
  readonly channels = lastLoaded<UserChannel[]>(() => this.channelsRead.value(), []);

  tokenExpirationOptions: TokenExpirationOption[] = [
    { value: '30', labelKey: 'iam.profile.expiry_30_days' },
    { value: '90', labelKey: 'iam.profile.expiry_90_days' },
    { value: '365', labelKey: 'iam.profile.expiry_1_year' },
    { value: 'never', labelKey: 'iam.profile.no_expiry' },
  ];

  // Methods, not computed: the card edits the form object in place.
  passwordStrength(): PasswordStrength {
    return passwordStrengthOf(this.passwordForm().newPassword);
  }

  hasMinLength(): boolean {
    return fitsPasswordPolicy(this.passwordForm().newPassword);
  }

  hasLettersAndNumbers(): boolean {
    const pwd = this.passwordForm().newPassword || '';
    return /[a-zA-ZЀ-ӿ]/.test(pwd) && /\d/.test(pwd);
  }

  hasMixedCase(): boolean {
    const pwd = this.passwordForm().newPassword || '';
    return /[a-z\u0430-\u044f]/.test(pwd) && /[A-Z\u0410-\u042f]/.test(pwd);
  }

  passwordsMatch(): boolean {
    const p1 = this.passwordForm().newPassword;
    const p2 = this.passwordForm().confirmPassword;
    return !!p1 && !!p2 && p1 === p2;
  }

  loadChannels() {
    this.channelsRead.reload();
  }

  onBindChannel(event: { channel: string; address: string }) {
    this.isBindingChannel.set(true);
    this.profile.bindChannel(event.channel, event.address).subscribe({
      next: (res) => {
        this.isBindingChannel.set(false);
        this.toast.info(this.uiI18n.translate('iam.profile.verification_code_sent', { address: event.address }));
        this.channelsCard()?.openConfirmModal(res.verifyToken, event.address);
        this.loadChannels();
      },
      error: (err: unknown) => {
        this.isBindingChannel.set(false);
        this.toast.error(problemText(err) || this.uiI18n.translate('iam.profile.channel_bind_failed'));
      },
    });
  }

  onConfirmChannel(event: { verifyToken: string; code: string }) {
    this.isConfirmingChannel.set(true);
    this.profile.confirmChannel(event.verifyToken, event.code).subscribe({
      next: () => {
        this.isConfirmingChannel.set(false);
        this.toast.success(this.uiI18n.translate('iam.profile.channel_bound'));
        this.channelsCard()?.closeConfirmModal();
        this.loadChannels();
      },
      error: (err: unknown) => {
        this.isConfirmingChannel.set(false);
        this.toast.error(problemText(err) || this.uiI18n.translate('iam.profile.channel_verify_failed'));
      },
    });
  }

  onUnbindChannel(channel: UserChannel) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    const channelsCard = this.channelsCard();
    const label = channelsCard ? channelsCard.channelLabel(channel.channel) : channel.channel;
    this.askThenRun({
      title: t('iam.profile.unbind'),
      message: `${t('iam.profile.unbind_confirm', { channel: label, address: channel.address })}\n${t('iam.profile.unbind_warning')}`,
      yesLabel: t('iam.profile.unbind'),
      request: () => this.profile.unbindChannel(channel.channel),
      done: () => {
        this.toast.success(t('iam.profile.channel_unbound'));
        this.loadChannels();
      },
      failure: t('iam.profile.channel_unbind_failed'),
    });
  }

  loadSessions() {
    this.sessionsRead.reload();
  }

  requestTerminateSession(session: UserSession) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.askThenRun({
      title: t('iam.profile.end_session_title'),
      message: `${t('iam.terminate_session_question', { ip: session.ip })}\n${t('iam.profile.end_other_sessions_hint')}`,
      yesLabel: t('iam.profile.terminate'),
      request: () => this.profile.endSession(session.id),
      done: () => {
        this.toast.success(t('iam.profile.session_ended'));
        this.loadSessions();
      },
      failure: t('iam.profile.end_session_failed'),
      busy: (on) => this.isTerminatingSession.set(on),
    });
  }

  requestTerminateOtherSessions() {
    const t = (key: string) => this.uiI18n.translate(key);
    this.askThenRun({
      title: t('iam.profile.end_session_title'),
      message: `${t('iam.profile.end_other_sessions_confirm')}\n${t('iam.profile.end_other_sessions_hint')}`,
      yesLabel: t('iam.profile.terminate'),
      request: () => this.profile.endOtherSessions(),
      done: () => {
        this.toast.success(t('iam.profile.other_sessions_ended'));
        this.loadSessions();
      },
      failure: t('iam.profile.end_sessions_failed'),
      busy: (on) => this.isTerminatingSession.set(on),
    });
  }

  submitChangePassword(event: Event) {
    event.preventDefault();
    this.isPasswordSubmitted.set(true);

    if (!this.passwordForm().oldPassword || !this.passwordForm().newPassword || !this.passwordForm().confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.profile.password_fields_required'));
      return;
    }

    if (!fitsPasswordPolicy(this.passwordForm().newPassword)) {
      this.toast.warning(this.uiI18n.translate('password.policy.length_error', PASSWORD_POLICY));
      return;
    }

    if (this.passwordForm().newPassword !== this.passwordForm().confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.profile.password_mismatch'));
      return;
    }

    this.isChangingPassword.set(true);
    this.profile.changePassword(this.passwordForm().oldPassword, this.passwordForm().newPassword).subscribe({
      next: () => {
        this.isChangingPassword.set(false);
        this.passwordForm.set({ oldPassword: '', newPassword: '', confirmPassword: '' });
        this.isPasswordSubmitted.set(false);
        this.authService.onPasswordChanged();
      },
      error: () => {
        this.isChangingPassword.set(false);
      },
    });
  }

  loadTokens() {
    this.tokensRead.reload();
  }

  openCreateTokenModal() {
    this.newTokenName.set('');
    this.selectedTokenExpiration.set('90');
    this.isTokenSubmitted.set(false);
    this.isCreateTokenModalOpen.set(true);
  }

  createTokenSubmit() {
    this.isTokenSubmitted.set(true);
    if (!this.newTokenName().trim()) {
      this.toast.warning(this.uiI18n.translate('iam.profile.token_name_required'));
      return;
    }

    const expiresAt = tokenExpiresAt(this.selectedTokenExpiration(), new Date());
    this.isCreatingToken.set(true);
    this.profile.createToken(this.newTokenName().trim(), expiresAt).subscribe({
      next: (res) => {
        this.isCreatingToken.set(false);
        this.isCreateTokenModalOpen.set(false);
        this.isTokenSubmitted.set(false);
        this.createdTokenSecret.set(res.rawSecretToken);
        this.copiedSecret.set(false);
        this.isTokenSecretModalOpen.set(true);
        this.loadTokens();
      },
      error: (err: unknown) => {
        this.isCreatingToken.set(false);
        this.toast.error(problemText(err) || this.uiI18n.translate('iam.profile.token_create_failed'));
      },
    });
  }

  requestRevokeToken(token: ApiToken) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.askThenRun({
      title: t('iam.profile.revoke_token_title'),
      message: `${t('iam.revoke_token_question', { name: token.name })}\n${t('iam.profile.revoke_token_warning')}`,
      yesLabel: t('iam.profile.revoke'),
      request: () => this.profile.revokeToken(token.id),
      done: () => {
        this.toast.success(t('iam.profile.token_revoked'));
        this.loadTokens();
      },
      failure: t('iam.profile.token_revoke_failed'),
    });
  }

  copySecret() {
    if (!this.createdTokenSecret()) return;
    navigator.clipboard.writeText(this.createdTokenSecret());
    this.copiedSecret.set(true);
    this.toast.success(this.uiI18n.translate('iam.profile.token_copied'));
    setTimeout(() => this.copiedSecret.set(false), 2000);
  }

  /**
   * Asks before a destructive profile action and runs it from the dialog:
   * the dialog stays open while the request runs and shows the server's
   * reason (or `failure`) if it fails, so the person can retry or keep things.
   */
  private askThenRun(ask: {
    title: string;
    message: string;
    yesLabel: string;
    request: () => Observable<unknown>;
    done: () => void;
    failure: string;
    busy?: (on: boolean) => void;
  }): void {
    this.modal
      .confirm({
        title: ask.title,
        message: ask.message,
        yesLabel: ask.yesLabel,
        noLabel: this.uiI18n.translate('common.cancel'),
        destructive: true,
        action: () => {
          ask.busy?.(true);
          return ask.request().pipe(
            tap(() => ask.done()),
            finalize(() => ask.busy?.(false)),
          );
        },
        actionError: (error) => problemText(error) || ask.failure,
      })
      .subscribe();
  }
}

/** The list (empty for no answer), or null when the request failed, so the list on screen stays. */
function listOrNull<T>(request: Observable<T[] | null>): Observable<T[] | null> {
  return request.pipe(
    map((list) => list ?? []),
    catchError(() => of(null)),
  );
}
