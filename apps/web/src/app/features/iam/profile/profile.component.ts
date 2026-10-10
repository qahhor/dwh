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
import { lastLoaded } from '@features/iam/last-loaded';

import { UserSession, UserChannel, ApiToken } from './profile.models';

import { UserProfileCardComponent } from './components/user-profile-card.component';
import { ProfilePasswordCardComponent } from './components/profile-password-card.component';
import { ProfileSecurityCardComponent } from './components/profile-security-card.component';
import { ProfileChannelsCardComponent } from './components/profile-channels-card.component';
import { ProfileSessionsCardComponent } from './components/profile-sessions-card.component';
import { ProfileTokensCardComponent } from './components/profile-tokens-card.component';

export * from './profile.models';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

/**
 * The signed-in person's profile. The password, channel and token forms live in their cards; the page reads the
 * lists and confirms the destructive actions (ending a session, unbinding a channel, revoking a token).
 */
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

  readonly isTerminatingSession = signal<boolean>(false);

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

  loadChannels() {
    this.channelsRead.reload();
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

  loadTokens() {
    this.tokensRead.reload();
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
