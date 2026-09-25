import { Component, OnInit, signal, computed, inject, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../../core/services/auth.service';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { Observable, finalize, tap } from 'rxjs';
import { SMTModalService } from '../../../shared/ui-kit/components/modal';
import { problemText } from '../../../shared/ui/problem-text';

import {
  User,
  UserSession,
  UserChannel,
  BindChannelResponse,
  ApiToken,
  CreatedTokenResponse,
  PasswordForm,
  PasswordStrength,
  TokenExpirationOption
} from './profile.models';

import { UserProfileCardComponent } from './components/user-profile-card.component';
import { ProfilePasswordCardComponent } from './components/profile-password-card.component';
import { ProfileSecurityCardComponent } from './components/profile-security-card.component';
import { ProfileChannelsCardComponent } from './components/profile-channels-card.component';
import { ProfileSessionsCardComponent } from './components/profile-sessions-card.component';
import { ProfileTokensCardComponent } from './components/profile-tokens-card.component';

export * from './profile.models';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    CommonModule,
    TranslatePipe,
    UserProfileCardComponent,
    ProfilePasswordCardComponent,
    ProfileSecurityCardComponent,
    ProfileChannelsCardComponent,
    ProfileSessionsCardComponent,
    ProfileTokensCardComponent
  ],
  template: `
    <div class="profile-container">
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'nav.profile' | t }}</h1>
          <span class="count-badge">{{ 'iam.bezopasnost_i_nastroyki' | t }}</span>
        </div>
      </div>

      <!-- User Info Card -->
      <app-user-profile-card [user]="authService.currentUser()"></app-user-profile-card>

      <!-- Main Grid Sections -->
      <div class="sections-grid">
        <!-- Change Password Card -->
        <app-profile-password-card
          [passwordForm]="passwordForm"
          [isPasswordSubmitted]="isPasswordSubmitted"
          [isChangingPassword]="isChangingPassword()"
          [passwordStrength]="passwordStrength()"
          [hasMinLength]="hasMinLength()"
          [hasLettersAndNumbers]="hasLettersAndNumbers()"
          [hasMixedCase]="hasMixedCase()"
          [passwordsMatch]="passwordsMatch()"
          (submitPassword)="submitChangePassword($event)"
        ></app-profile-password-card>

        <!-- Security & 2FA Info Card -->
        <app-profile-security-card [user]="authService.currentUser()"></app-profile-security-card>

        <!-- Communication Channels Card -->
        <app-profile-channels-card
          #channelsCard
          [channels]="channels()"
          [isLoadingChannels]="isLoadingChannels()"
          [isBindingChannel]="isBindingChannel()"
          [isConfirmingChannel]="isConfirmingChannel()"
          [canManageChannels]="canManageChannels()"
          (bindChannel)="onBindChannel($event)"
          (confirmChannel)="onConfirmChannel($event)"
          (unbindChannel)="onUnbindChannel($event)"
        ></app-profile-channels-card>

        <!-- Active Sessions Card -->
        <app-profile-sessions-card
          [sessions]="sessions()"
          [isLoadingSessions]="isLoadingSessions()"
          [isTerminatingSession]="isTerminatingSession()"
          (loadSessions)="loadSessions()"
          (terminateSession)="requestTerminateSession($event)"
          (terminateOtherSessions)="requestTerminateOtherSessions()"
        ></app-profile-sessions-card>

        <!-- API Tokens Card -->
        <app-profile-tokens-card
          [tokens]="tokens()"
          [isLoadingTokens]="isLoadingTokens()"
          [isCreatingToken]="isCreatingToken()"
          [isCreateTokenModalOpen]="isCreateTokenModalOpen()"
          [isTokenSecretModalOpen]="isTokenSecretModalOpen()"
          [isTokenSubmitted]="isTokenSubmitted"
          [newTokenName]="newTokenName"
          [selectedTokenExpiration]="selectedTokenExpiration"
          [createdTokenSecret]="createdTokenSecret"
          [copiedSecret]="copiedSecret()"
          [tokenExpirationOptions]="tokenExpirationOptions"
          (openCreateTokenModal)="openCreateTokenModal()"
          (closeCreateTokenModal)="isCreateTokenModalOpen.set(false)"
          (createTokenSubmit)="createTokenSubmit()"
          (nameChange)="newTokenName = $event"
          (expirationChange)="selectedTokenExpiration = $event"
          (closeSecretModal)="isTokenSecretModalOpen.set(false)"
          (copySecret)="copySecret()"
          (requestRevoke)="requestRevokeToken($event)"
        ></app-profile-tokens-card>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .profile-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
      min-width: 0;
    }

    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 4px;
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .view-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      margin: 0;
    }

    .count-badge {
      background-color: var(--bg-hover);
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 500;
      padding: 3px 10px;
      border-radius: 12px;
      border: 1px solid var(--border-color);
    }

    .sections-grid {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 16px;
    }

    @media (max-width: 1024px) {
      .sections-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `]
})
export class ProfileComponent implements OnInit {
  public readonly permissionService = inject(PermissionService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

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

  readonly canManageChannels = computed(() => this.permissionService.hasPermission('iam.profile', 'manage_channels'));

  @ViewChild('channelsCard') channelsCard?: ProfileChannelsCardComponent;

  isPasswordSubmitted = false;
  isTokenSubmitted = false;

  newTokenName = '';
  selectedTokenExpiration = '90';
  createdTokenSecret = '';

  tokenExpirationOptions: TokenExpirationOption[] = [
    { value: '30', labelKey: 'iam.srok_30_dney' },
    { value: '90', labelKey: 'iam.srok_90_dney' },
    { value: '365', labelKey: 'iam.srok_1_god' },
    { value: 'never', labelKey: 'iam.bessrochno' }
  ];

  passwordForm: PasswordForm = {
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  };

  constructor(
    public authService: AuthService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  passwordStrength(): PasswordStrength {
    const pwd = this.passwordForm.newPassword;
    if (!pwd) return { score: 0, label: '', percent: 0, colorClass: '' };
    let score = 0;
    if (pwd.length >= 10) score++;
    if (/[a-z\u0430-\u044f]/.test(pwd) && /[A-Z\u0410-\u042f]/.test(pwd)) score++;
    if (/\d/.test(pwd)) score++;
    if (/[^a-zA-Z\u0400-\u04FF0-9]/.test(pwd)) score++;

    let label = 'iam.parol_slabyy';
    let colorClass = 'strength-weak';
    let percent = 25;

    if (score === 2) {
      label = 'iam.parol_sredniy';
      colorClass = 'strength-medium';
      percent = 50;
    } else if (score === 3) {
      label = 'iam.parol_horoshiy';
      colorClass = 'strength-good';
      percent = 75;
    } else if (score >= 4) {
      label = 'iam.parol_otlichnyy';
      colorClass = 'strength-strong';
      percent = 100;
    }

    return { score, label, percent, colorClass };
  }

  hasMinLength(): boolean {
    return (this.passwordForm.newPassword?.length || 0) >= 10;
  }

  hasLettersAndNumbers(): boolean {
    const pwd = this.passwordForm.newPassword || '';
    return /[a-zA-Z\u0400-\u04FF]/.test(pwd) && /\d/.test(pwd);
  }

  hasMixedCase(): boolean {
    const pwd = this.passwordForm.newPassword || '';
    return /[a-z\u0430-\u044f]/.test(pwd) && /[A-Z\u0410-\u042f]/.test(pwd);
  }

  passwordsMatch(): boolean {
    const p1 = this.passwordForm.newPassword;
    const p2 = this.passwordForm.confirmPassword;
    return !!p1 && !!p2 && p1 === p2;
  }

  ngOnInit() {
    this.loadSessions();
    this.loadTokens();
    this.loadChannels();
  }

  loadChannels() {
    this.isLoadingChannels.set(true);
    this.api.get<UserChannel[]>('/iam/profile/channels').subscribe({
      next: res => {
        this.channels.set(res || []);
        this.isLoadingChannels.set(false);
      },
      error: () => {
        this.isLoadingChannels.set(false);
      }
    });
  }

  onBindChannel(event: { channel: string; address: string }) {
    this.isBindingChannel.set(true);
    this.api.post<BindChannelResponse>('/iam/profile/channels', event).subscribe({
      next: res => {
        this.isBindingChannel.set(false);
        this.toast.info(this.uiI18n.translate('iam.kod_podtverzhdeniya_otpravlen', { address: event.address }));
        this.channelsCard?.openConfirmModal(res.verifyToken, event.address);
        this.loadChannels();
      },
      error: (err: any) => {
        this.isBindingChannel.set(false);
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_privyazki_kanala'));
      }
    });
  }

  onConfirmChannel(event: { verifyToken: string; code: string }) {
    this.isConfirmingChannel.set(true);
    this.api.post<void>('/iam/profile/channels/confirm', event).subscribe({
      next: () => {
        this.isConfirmingChannel.set(false);
        this.toast.success(this.uiI18n.translate('iam.kanal_uspeshno_privyazan'));
        this.channelsCard?.closeConfirmModal();
        this.loadChannels();
      },
      error: (err: any) => {
        this.isConfirmingChannel.set(false);
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_podtverzhdeniya_kanala'));
      }
    });
  }

  onUnbindChannel(channel: UserChannel) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    const label = this.channelsCard ? this.channelsCard.channelLabel(channel.channel) : channel.channel;
    this.askThenRun({
      title: t('iam.otvyazat_kanal'),
      message: `${t('iam.vy_uvereny_chto_hotite_otvyazat_kanal', { channel: label, address: channel.address })}\n${t('iam.otvyazat_kanal_preduprezhdenie')}`,
      yesLabel: t('iam.otvyazat_kanal'),
      request: () => this.api.delete(`/iam/profile/channels/${channel.channel}`, { notifyError: false }),
      done: () => {
        this.toast.success(t('iam.kanal_uspeshno_otvyazan'));
        this.loadChannels();
      },
      failure: t('iam.oshibka_otvyazki_kanala')
    });
  }

  loadSessions() {
    this.isLoadingSessions.set(true);
    this.api.get<UserSession[]>('/iam/profile/sessions').subscribe({
      next: res => {
        this.sessions.set(res || []);
        this.isLoadingSessions.set(false);
      },
      error: () => {
        this.isLoadingSessions.set(false);
      }
    });
  }

  requestTerminateSession(session: UserSession) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.askThenRun({
      title: t('iam.zavershenie_sessii'),
      message: `${t('iam.terminate_session_question', { ip: session.ip })}\n${t('iam.na_zavershennyh_ustroystvah_potrebuetsya_vypolni')}`,
      yesLabel: t('iam.zavershit'),
      request: () => this.api.delete(`/iam/profile/sessions/${session.id}`, { notifyError: false }),
      done: () => {
        this.toast.success(t('iam.sessiya_uspeshno_zavershena'));
        this.loadSessions();
      },
      failure: t('iam.oshibka_pri_zavershenii_sessii'),
      busy: on => this.isTerminatingSession.set(on)
    });
  }

  requestTerminateOtherSessions() {
    const t = (key: string) => this.uiI18n.translate(key);
    this.askThenRun({
      title: t('iam.zavershenie_sessii'),
      message: `${t('iam.zavershit_vse_ostalnye_aktivnye_sessii_krome_tek')}\n${t('iam.na_zavershennyh_ustroystvah_potrebuetsya_vypolni')}`,
      yesLabel: t('iam.zavershit'),
      request: () => this.api.delete('/iam/profile/sessions/others', { notifyError: false }),
      done: () => {
        this.toast.success(t('iam.vse_ostalnye_sessii_uspeshno_zaversheny'));
        this.loadSessions();
      },
      failure: t('iam.oshibka_pri_zavershenii_sessiy'),
      busy: on => this.isTerminatingSession.set(on)
    });
  }

  submitChangePassword(event: Event) {
    event.preventDefault();
    this.isPasswordSubmitted = true;

    if (!this.passwordForm.oldPassword || !this.passwordForm.newPassword || !this.passwordForm.confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.zapolnite_vse_polya_smeny_parolya'));
      return;
    }

    if (this.passwordForm.newPassword.length < 10) {
      this.toast.warning(this.uiI18n.translate('iam.novyy_parol_dolzhen_soderzhat_minimum_10_simvolo'));
      return;
    }

    if (this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.novyy_parol_i_podtverzhdenie_ne_sovpadayut'));
      return;
    }

    this.isChangingPassword.set(true);
    this.api.post('/iam/users/me/password', {
      oldPassword: this.passwordForm.oldPassword,
      newPassword: this.passwordForm.newPassword
    }).subscribe({
      next: () => {
        this.isChangingPassword.set(false);
        this.passwordForm = { oldPassword: '', newPassword: '', confirmPassword: '' };
        this.isPasswordSubmitted = false;
        this.authService.onPasswordChanged();
      },
      error: () => {
        this.isChangingPassword.set(false);
      }
    });
  }

  loadTokens() {
    this.isLoadingTokens.set(true);
    this.api.get<ApiToken[]>('/iam/profile/tokens').subscribe({
      next: res => {
        this.tokens.set(res || []);
        this.isLoadingTokens.set(false);
      },
      error: () => {
        this.isLoadingTokens.set(false);
      }
    });
  }

  openCreateTokenModal() {
    this.newTokenName = '';
    this.selectedTokenExpiration = '90';
    this.isTokenSubmitted = false;
    this.isCreateTokenModalOpen.set(true);
  }

  createTokenSubmit() {
    this.isTokenSubmitted = true;
    if (!this.newTokenName.trim()) {
      this.toast.warning(this.uiI18n.translate('iam.vvedite_nazvanie_api_tokena.bfec35d'));
      return;
    }

    let expiresAt: string | null = null;
    const now = new Date();
    if (this.selectedTokenExpiration === '30') {
      expiresAt = new Date(now.getTime() + 30 * 86400000).toISOString();
    } else if (this.selectedTokenExpiration === '90') {
      expiresAt = new Date(now.getTime() + 90 * 86400000).toISOString();
    } else if (this.selectedTokenExpiration === '365') {
      expiresAt = new Date(now.getTime() + 365 * 86400000).toISOString();
    }

    this.isCreatingToken.set(true);
    this.api.post<CreatedTokenResponse>('/iam/profile/tokens', {
      name: this.newTokenName.trim(),
      expiresAt
    }).subscribe({
      next: res => {
        this.isCreatingToken.set(false);
        this.isCreateTokenModalOpen.set(false);
        this.isTokenSubmitted = false;
        this.createdTokenSecret = res.rawSecretToken;
        this.copiedSecret.set(false);
        this.isTokenSecretModalOpen.set(true);
        this.loadTokens();
      },
      error: (err: any) => {
        this.isCreatingToken.set(false);
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_pri_sozdanii_api_tokena'));
      }
    });
  }

  requestRevokeToken(token: ApiToken) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.askThenRun({
      title: t('iam.otzyv_api_tokena'),
      message: `${t('iam.revoke_token_question', { name: token.name })}\n${t('iam.integracii_s_etim_tokenom_nemedlenno_poteryayut_')}`,
      yesLabel: t('iam.otozvat'),
      request: () => this.api.delete(`/iam/profile/tokens/${token.id}`, { notifyError: false }),
      done: () => {
        this.toast.success(t('iam.token_uspeshno_otozvan'));
        this.loadTokens();
      },
      failure: t('iam.oshibka_pri_otzyve_tokena')
    });
  }

  copySecret() {
    if (!this.createdTokenSecret) return;
    navigator.clipboard.writeText(this.createdTokenSecret);
    this.copiedSecret.set(true);
    this.toast.success(this.uiI18n.translate('iam.token_skopirovan_v_bufer_obmena'));
    setTimeout(() => this.copiedSecret.set(false), 2000);
  }

  /**
   * Asks before a destructive profile action and runs it from the dialog:
   * the dialog stays open while the request runs and shows the server's
   * reason (or `failure`) if it fails, so the person can retry or keep things.
   */
  private askThenRun(ask: { title: string; message: string; yesLabel: string; request: () => Observable<unknown>; done: () => void; failure: string; busy?: (on: boolean) => void }): void {
    this.modal.confirm({
      title: ask.title,
      message: ask.message,
      yesLabel: ask.yesLabel,
      noLabel: this.uiI18n.translate('common.cancel'),
      destructive: true,
      action: () => {
        ask.busy?.(true);
        return ask.request().pipe(tap(() => ask.done()), finalize(() => ask.busy?.(false)));
      },
      actionError: error => problemText(error) || ask.failure
    }).subscribe();
  }
}
