import { ChangeDetectionStrategy, Component, OnInit, signal, computed, inject, viewChild } from '@angular/core';

import { AuthService } from '@core/services/auth.service';
import { ProfileApi } from './profile.api';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { Observable, finalize, tap } from 'rxjs';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';

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
export class ProfileComponent implements OnInit {
  authService = inject(AuthService);
  private profile = inject(ProfileApi);
  private toast = inject(ToastService);

  public readonly permissionService = inject(PermissionService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly channelsCard = viewChild<ProfileChannelsCardComponent>('channelsCard');

  readonly sessions = signal<UserSession[]>([]);
  readonly tokens = signal<ApiToken[]>([]);
  readonly channels = signal<UserChannel[]>([]);

  readonly isLoadingSessions = signal<boolean>(false);
  readonly isLoadingTokens = signal<boolean>(false);
  readonly isLoadingChannels = signal<boolean>(false);
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

  readonly canManageChannels = computed(() => this.permissionService.hasPermission('iam.profile', 'manage_channels'));

  tokenExpirationOptions: TokenExpirationOption[] = [
    { value: '30', labelKey: 'iam.srok_30_dney' },
    { value: '90', labelKey: 'iam.srok_90_dney' },
    { value: '365', labelKey: 'iam.srok_1_god' },
    { value: 'never', labelKey: 'iam.bessrochno' },
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

  ngOnInit() {
    this.loadSessions();
    this.loadTokens();
    this.loadChannels();
  }

  loadChannels() {
    this.isLoadingChannels.set(true);
    this.profile.channels().subscribe({
      next: (res) => {
        this.channels.set(res || []);
        this.isLoadingChannels.set(false);
      },
      error: () => {
        this.isLoadingChannels.set(false);
      },
    });
  }

  onBindChannel(event: { channel: string; address: string }) {
    this.isBindingChannel.set(true);
    this.profile.bindChannel(event.channel, event.address).subscribe({
      next: (res) => {
        this.isBindingChannel.set(false);
        this.toast.info(this.uiI18n.translate('iam.kod_podtverzhdeniya_otpravlen', { address: event.address }));
        this.channelsCard()?.openConfirmModal(res.verifyToken, event.address);
        this.loadChannels();
      },
      error: (err: unknown) => {
        this.isBindingChannel.set(false);
        this.toast.error(problemText(err) || this.uiI18n.translate('iam.oshibka_privyazki_kanala'));
      },
    });
  }

  onConfirmChannel(event: { verifyToken: string; code: string }) {
    this.isConfirmingChannel.set(true);
    this.profile.confirmChannel(event.verifyToken, event.code).subscribe({
      next: () => {
        this.isConfirmingChannel.set(false);
        this.toast.success(this.uiI18n.translate('iam.kanal_uspeshno_privyazan'));
        this.channelsCard()?.closeConfirmModal();
        this.loadChannels();
      },
      error: (err: unknown) => {
        this.isConfirmingChannel.set(false);
        this.toast.error(problemText(err) || this.uiI18n.translate('iam.oshibka_podtverzhdeniya_kanala'));
      },
    });
  }

  onUnbindChannel(channel: UserChannel) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    const channelsCard = this.channelsCard();
    const label = channelsCard ? channelsCard.channelLabel(channel.channel) : channel.channel;
    this.askThenRun({
      title: t('iam.otvyazat_kanal'),
      message: `${t('iam.vy_uvereny_chto_hotite_otvyazat_kanal', { channel: label, address: channel.address })}\n${t('iam.otvyazat_kanal_preduprezhdenie')}`,
      yesLabel: t('iam.otvyazat_kanal'),
      request: () => this.profile.unbindChannel(channel.channel),
      done: () => {
        this.toast.success(t('iam.kanal_uspeshno_otvyazan'));
        this.loadChannels();
      },
      failure: t('iam.oshibka_otvyazki_kanala'),
    });
  }

  loadSessions() {
    this.isLoadingSessions.set(true);
    this.profile.sessions().subscribe({
      next: (res) => {
        this.sessions.set(res || []);
        this.isLoadingSessions.set(false);
      },
      error: () => {
        this.isLoadingSessions.set(false);
      },
    });
  }

  requestTerminateSession(session: UserSession) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.askThenRun({
      title: t('iam.zavershenie_sessii'),
      message: `${t('iam.terminate_session_question', { ip: session.ip })}\n${t('iam.na_zavershennyh_ustroystvah_potrebuetsya_vypolni')}`,
      yesLabel: t('iam.zavershit'),
      request: () => this.profile.endSession(session.id),
      done: () => {
        this.toast.success(t('iam.sessiya_uspeshno_zavershena'));
        this.loadSessions();
      },
      failure: t('iam.oshibka_pri_zavershenii_sessii'),
      busy: (on) => this.isTerminatingSession.set(on),
    });
  }

  requestTerminateOtherSessions() {
    const t = (key: string) => this.uiI18n.translate(key);
    this.askThenRun({
      title: t('iam.zavershenie_sessii'),
      message: `${t('iam.zavershit_vse_ostalnye_aktivnye_sessii_krome_tek')}\n${t('iam.na_zavershennyh_ustroystvah_potrebuetsya_vypolni')}`,
      yesLabel: t('iam.zavershit'),
      request: () => this.profile.endOtherSessions(),
      done: () => {
        this.toast.success(t('iam.vse_ostalnye_sessii_uspeshno_zaversheny'));
        this.loadSessions();
      },
      failure: t('iam.oshibka_pri_zavershenii_sessiy'),
      busy: (on) => this.isTerminatingSession.set(on),
    });
  }

  submitChangePassword(event: Event) {
    event.preventDefault();
    this.isPasswordSubmitted.set(true);

    if (!this.passwordForm().oldPassword || !this.passwordForm().newPassword || !this.passwordForm().confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.zapolnite_vse_polya_smeny_parolya'));
      return;
    }

    if (!fitsPasswordPolicy(this.passwordForm().newPassword)) {
      this.toast.warning(this.uiI18n.translate('password.policy.length_error', PASSWORD_POLICY));
      return;
    }

    if (this.passwordForm().newPassword !== this.passwordForm().confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.novyy_parol_i_podtverzhdenie_ne_sovpadayut'));
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
    this.isLoadingTokens.set(true);
    this.profile.tokens().subscribe({
      next: (res) => {
        this.tokens.set(res || []);
        this.isLoadingTokens.set(false);
      },
      error: () => {
        this.isLoadingTokens.set(false);
      },
    });
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
      this.toast.warning(this.uiI18n.translate('iam.vvedite_nazvanie_api_tokena.bfec35d'));
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
        this.toast.error(problemText(err) || this.uiI18n.translate('iam.oshibka_pri_sozdanii_api_tokena'));
      },
    });
  }

  requestRevokeToken(token: ApiToken) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.askThenRun({
      title: t('iam.otzyv_api_tokena'),
      message: `${t('iam.revoke_token_question', { name: token.name })}\n${t('iam.integracii_s_etim_tokenom_nemedlenno_poteryayut_')}`,
      yesLabel: t('iam.otozvat'),
      request: () => this.profile.revokeToken(token.id),
      done: () => {
        this.toast.success(t('iam.token_uspeshno_otozvan'));
        this.loadTokens();
      },
      failure: t('iam.oshibka_pri_otzyve_tokena'),
    });
  }

  copySecret() {
    if (!this.createdTokenSecret()) return;
    navigator.clipboard.writeText(this.createdTokenSecret());
    this.copiedSecret.set(true);
    this.toast.success(this.uiI18n.translate('iam.token_skopirovan_v_bufer_obmena'));
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
