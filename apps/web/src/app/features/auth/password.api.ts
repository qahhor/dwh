import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';

/**
 * Password endpoints of the sign-in pages: the change a first sign-in requires, and the reset by a one-time link.
 * Every page shows its own message, so none of these raises the general error toast.
 */
@Injectable({ providedIn: 'root' })
export class PasswordApi {
  private readonly api = inject(ApiService);

  change(oldPassword: string, newPassword: string): Observable<unknown> {
    return this.api.post('/auth/password', { oldPassword, newPassword }, { notifyError: false });
  }

  /** Sends the reset link to the address if an account has it; the answer is the same either way. */
  requestReset(email: string): Observable<unknown> {
    return this.api.post('/auth/password-reset/request', { email }, { notifyError: false });
  }

  confirmReset(token: string, newPassword: string): Observable<unknown> {
    return this.api.post('/auth/password-reset/confirm', { token, newPassword }, { notifyError: false });
  }
}
