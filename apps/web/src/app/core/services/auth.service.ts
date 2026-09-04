import { Injectable, signal, computed } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap, catchError, of } from 'rxjs';
import { ApiService } from './api.service';
import { PermissionService } from './permission.service';
import { ToastService } from './toast.service';
import { User, LoginResponse, MeResponse } from '../models/auth.models';
import { I18nService } from './i18n.service';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  readonly currentUser = signal<User | null>(null);
  readonly isLoading = signal<boolean>(true);
  readonly isAuthenticated = computed(() => this.currentUser() !== null);

  constructor(
    private api: ApiService,
    private permissionService: PermissionService,
    private toast: ToastService,
    private router: Router,
    private i18n: I18nService
  ) {}

  checkSession(): Observable<MeResponse | null> {
    this.isLoading.set(true);
    return this.api.get<MeResponse>('/auth/me').pipe(
      tap(res => {
        this.currentUser.set(res.user);
        this.i18n.useAuthenticatedPreference(res.user.language);
        this.permissionService.setPermissions(res.permissions, res.permissionsVersion);
        this.isLoading.set(false);
      }),
      catchError(() => {
        this.currentUser.set(null);
        this.permissionService.clear();
        this.isLoading.set(false);
        return of(null);
      })
    );
  }

  login(login: string, password: string, deviceInfo?: string): Observable<LoginResponse> {
    return this.api.post<LoginResponse>('/auth/login', { login, password, deviceInfo }).pipe(
      tap(res => {
        if (res.step === 'success' && res.user) {
          this.currentUser.set(res.user);
          this.i18n.useAuthenticatedPreference(res.user.language);
          if (!res.user.forcePasswordChange) {
            this.refreshMe().subscribe();
            this.toast.success(`Добро пожаловать, ${res.user.name}!`);
            this.router.navigate(['/tasks']);
          }
        }
      })
    );
  }

  verifyOtp(otpToken: string, code: string, deviceInfo?: string): Observable<LoginResponse> {
    return this.api.post<LoginResponse>('/auth/otp', { otpToken, code, deviceInfo }).pipe(
      tap(res => {
        if (res.step === 'success' && res.user) {
          this.currentUser.set(res.user);
          this.i18n.useAuthenticatedPreference(res.user.language);
          if (!res.user.forcePasswordChange) {
            this.refreshMe().subscribe();
            this.toast.success(`Вход успешно подтвержден!`);
            this.router.navigate(['/tasks']);
          }
        }
      })
    );
  }

  refreshMe(): Observable<MeResponse> {
    return this.api.get<MeResponse>('/auth/me').pipe(
      tap(res => {
        this.currentUser.set(res.user);
        this.i18n.useAuthenticatedPreference(res.user.language);
        this.permissionService.setPermissions(res.permissions, res.permissionsVersion);
      })
    );
  }

  logout(): void {
    this.api.post('/auth/logout').subscribe({
      next: () => {
        this.currentUser.set(null);
        this.permissionService.clear();
        this.router.navigate(['/login']);
      },
      error: () => {
        this.currentUser.set(null);
        this.permissionService.clear();
        this.router.navigate(['/login']);
      }
    });
  }
}
