import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { ApiToken, BindChannelResponse, CreatedTokenResponse, UserChannel, UserSession } from './profile.models';

/**
 * The signed-in person's own profile: delivery channels, sessions, password and API tokens.
 * Deleting anything is confirmed in a dialog that shows the failure, so those calls raise no toast.
 */
/** The screen shows the server's reason itself, so the request raises no general error toast. */
const QUIET = { notifyError: false };

@Injectable({ providedIn: 'root' })
export class ProfileApi {
  private readonly api = inject(ApiService);

  channels(): Observable<UserChannel[]> {
    return this.api.get<UserChannel[]>('/iam/profile/channels');
  }

  /** Sends a code to the address; the answer carries the token the code is confirmed with. */
  bindChannel(channel: string, address: string): Observable<BindChannelResponse> {
    return this.api.post<BindChannelResponse>('/iam/profile/channels', { channel, address }, QUIET);
  }

  confirmChannel(verifyToken: string, code: string): Observable<void> {
    return this.api.post<void>('/iam/profile/channels/confirm', { verifyToken, code }, QUIET);
  }

  unbindChannel(channel: string): Observable<unknown> {
    return this.api.delete(`/iam/profile/channels/${channel}`, { notifyError: false });
  }

  sessions(): Observable<UserSession[]> {
    return this.api.get<UserSession[]>('/iam/profile/sessions');
  }

  endSession(id: number): Observable<unknown> {
    return this.api.delete(`/iam/profile/sessions/${id}`, { notifyError: false });
  }

  endOtherSessions(): Observable<unknown> {
    return this.api.delete('/iam/profile/sessions/others', { notifyError: false });
  }

  changePassword(oldPassword: string, newPassword: string): Observable<unknown> {
    return this.api.post('/iam/users/me/password', { oldPassword, newPassword });
  }

  tokens(): Observable<ApiToken[]> {
    return this.api.get<ApiToken[]>('/iam/profile/tokens');
  }

  /** The answer carries the token's secret, shown once. */
  createToken(name: string, expiresAt: string | null): Observable<CreatedTokenResponse> {
    return this.api.post<CreatedTokenResponse>('/iam/profile/tokens', { name, expiresAt }, QUIET);
  }

  revokeToken(id: number): Observable<unknown> {
    return this.api.delete(`/iam/profile/tokens/${id}`, { notifyError: false });
  }
}
