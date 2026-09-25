import { Injectable, signal, computed } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap, catchError, filter, finalize, map, of } from 'rxjs';
import { ApiService } from './api.service';
import { PermissionService } from './permission.service';
import { ToastService } from './toast.service';
import { User, LoginResponse, MeResponse } from '../models/auth.models';
import { I18nService } from './i18n.service';
import { TabSyncService } from './tab-sync.service';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private sessionGeneration = 0;
  /** Where to go after the next sign-in: the page the session was lost on. */
  private returnUrl: string | null = null;
  readonly currentUser = signal<User | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly isLoggingOut = signal(false);
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  constructor(
    private api: ApiService,
    private permissionService: PermissionService,
    private toast: ToastService,
    private router: Router,
    private i18n: I18nService,
    private tabs: TabSyncService
  ) {
    this.tabs.messages.subscribe(message => {
      if (message.kind === 'signed-out') this.signedOutElsewhere();
      if (message.kind === 'signed-in') this.signedInElsewhere(message.userId);
    });
  }

  checkSession(): Observable<MeResponse | null> {
    const generation = this.sessionGeneration;
    this.isLoading.set(true);
    return this.api.get<MeResponse>('/auth/me').pipe(
      map(res => generation === this.sessionGeneration ? res : null),
      tap(res => {
        if (!res) return;
        this.currentUser.set(res.user);
        this.i18n.useAuthenticatedPreference(res.user.language);
        this.permissionService.setPermissions(res.permissions, res.permissionsVersion);
        this.isLoading.set(false);
      }),
      catchError(() => {
        if (generation !== this.sessionGeneration) return of(null);
        this.currentUser.set(null);
        this.permissionService.clear();
        this.isLoading.set(false);
        return of(null);
      })
    );
  }

  login(login: string, password: string, deviceInfo?: string): Observable<LoginResponse> {
    return this.api.post<LoginResponse>('/auth/login', { login, password, deviceInfo }, { notifyError: false }).pipe(
      tap(res => {
        if (res.step === 'success' && res.user) {
          this.currentUser.set(res.user);
          this.i18n.useAuthenticatedPreference(res.user.language);
          this.tabs.publish({ kind: 'signed-in', userId: res.user.id });
          if (!res.user.forcePasswordChange) {
            this.refreshMe().subscribe();
            this.toast.success(this.i18n.translate('auth.welcome_name', { name: res.user.name }));
            this.router.navigateByUrl(this.takeReturnUrl());
          }
        }
      })
    );
  }

  verifyOtp(otpToken: string, code: string, deviceInfo?: string): Observable<LoginResponse> {
    return this.api.post<LoginResponse>('/auth/otp', { otpToken, code, deviceInfo }, { notifyError: false }).pipe(
      tap(res => {
        if (res.step === 'success' && res.user) {
          this.currentUser.set(res.user);
          this.i18n.useAuthenticatedPreference(res.user.language);
          this.tabs.publish({ kind: 'signed-in', userId: res.user.id });
          if (!res.user.forcePasswordChange) {
            this.refreshMe().subscribe();
            this.toast.success(this.i18n.translate('auth.login_confirmed'));
            this.router.navigateByUrl(this.takeReturnUrl());
          }
        }
      })
    );
  }

  refreshMe(): Observable<MeResponse> {
    const generation = this.sessionGeneration;
    return this.api.get<MeResponse>('/auth/me').pipe(
      filter(() => generation === this.sessionGeneration),
      tap(res => {
        this.currentUser.set(res.user);
        this.i18n.useAuthenticatedPreference(res.user.language);
        this.permissionService.setPermissions(res.permissions, res.permissionsVersion);
      })
    );
  }

  onPasswordChanged(): void {
    this.endSessionHere();
    this.tabs.publish({ kind: 'signed-out' });
    this.toast.success(this.i18n.translate('auth.password_changed_sign_in_again'));
    this.router.navigate(['/login'], { replaceUrl: true });
  }

  /** Ends the session; `idle` — the idle lock closed it, and the sign-in page says so. */
  logout(reason?: 'idle'): void {
    if (this.isLoggingOut()) return;
    const here = this.router.url;
    this.isLoading.set(false);
    this.isLoggingOut.set(true);
    this.api.post('/auth/logout').pipe(
      finalize(() => this.isLoggingOut.set(false))
    ).subscribe({
      next: () => {
        // Invalidate reads from before and during logout only after success;
        // a failed logout must still allow pending permission initialization.
        this.signedOut(reason === 'idle' ? here : null);
        if (reason === 'idle') this.toast.info(this.i18n.translate('auth.idle.signed_out'));
      },
      error: (problem: { status?: number }) => {
        // 401: the server session was already gone, so signing out is done.
        if (problem?.status === 401) this.signedOut(reason === 'idle' ? here : null);
        // Otherwise ApiService reports the failure. Do not claim that the server's
        // HttpOnly session ended when it could still be valid; allow retry.
      }
    });
  }

  /**
   * The API answered 401 while this tab thought it was signed in (roadmap item 29): the
   * session expired or was revoked. The tab forgets it, the other tabs follow, and after
   * signing in again the person comes back to the same page.
   */
  sessionExpired(): void {
    if (!this.currentUser()) return;
    this.signedOut(this.router.url);
    this.toast.info(this.i18n.translate('auth.session_expired'));
  }

  /** Remembers a page to open after sign-in; only paths inside the app are kept. */
  rememberReturnUrl(url: string | null): void {
    this.returnUrl = url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/login') ? url : null;
  }

  private takeReturnUrl(): string {
    const url = this.returnUrl ?? '/tasks';
    this.returnUrl = null;
    return url;
  }

  private signedOut(returnUrl: string | null): void {
    this.endSessionHere();
    this.rememberReturnUrl(returnUrl);
    this.tabs.publish({ kind: 'signed-out' });
    this.router.navigate(['/login'], { replaceUrl: true });
  }

  /** Forgets the session in this tab: the user, the rights and the messages about them. */
  private endSessionHere(): void {
    this.sessionGeneration++;
    this.currentUser.set(null);
    this.permissionService.clear();
    this.isLoading.set(false);
    for (const notification of this.toast.toasts()) this.toast.dismiss(notification.id);
  }

  /** Another tab signed out: the cookie is gone for this tab too, so it follows at once. */
  private signedOutElsewhere(): void {
    if (!this.currentUser()) return;
    const here = this.router.url;
    this.endSessionHere();
    this.rememberReturnUrl(here);
    this.toast.info(this.i18n.translate('auth.signed_out_elsewhere'));
    this.router.navigate(['/login'], { replaceUrl: true });
  }

  /** Another tab signed in: a tab on the sign-in page (or of another person) takes the new session. */
  private signedInElsewhere(userId: number): void {
    if (this.currentUser()?.id === userId) return;
    this.checkSession().subscribe(session => {
      if (session && this.router.url.startsWith('/login')) this.router.navigateByUrl(this.takeReturnUrl());
    });
  }
}
