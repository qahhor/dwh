import { Injectable, untracked, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ProblemDetail } from '../models/common.models';
import { ToastService } from './toast.service';
import { I18nService } from './i18n.service';
import { isSessionLoss } from '../http/session-bound';

export interface ApiRequestOptions {
  notifyError?: boolean;
}

/** Query parameters of a GET: anything that has a text form. */
export type QueryParams = Record<string, string | number | boolean | null | undefined>;

/** An error body as the server sends it (RFC 9457 problem detail, possibly with older fields). */
interface RawProblem {
  title?: string;
  detail?: string;
  message?: string;
  code?: string;
  /** Catalog key of the text and its parameters (plan 10/10, item 3.1); `detail` is the server's rendering of it. */
  messageKey?: string;
  params?: Record<string, string | number>;
  errors?: ProblemDetail['errors'];
  invalid_params?: ProblemDetail['invalid_params'];
}

@Injectable({
  providedIn: 'root',
})
export class ApiService {
  private http = inject(HttpClient);
  private toast = inject(ToastService);
  private i18n = inject(I18nService);

  private readonly baseUrl = '/api/v1';

  /** Parameters that are undefined, null or empty are left out; the others are sent as text. */
  get<T>(path: string, params?: QueryParams, options: ApiRequestOptions = {}): Observable<T> {
    let httpParams = new HttpParams();
    for (const [key, value] of Object.entries(params ?? {})) {
      if (value !== undefined && value !== null && value !== '') {
        httpParams = httpParams.set(key, String(value));
      }
    }

    return this.http
      .get<T>(`${this.baseUrl}${path}`, {
        params: httpParams,
        headers: this.getHeaders(),
        withCredentials: true,
      })
      .pipe(catchError((err) => this.handleError(err, options)));
  }

  post<T>(path: string, body?: unknown, options: ApiRequestOptions = {}): Observable<T> {
    return this.http
      .post<T>(`${this.baseUrl}${path}`, body || {}, {
        headers: this.getHeaders(),
        withCredentials: true,
      })
      .pipe(catchError((err) => this.handleError(err, options)));
  }

  patch<T>(path: string, body?: unknown, options: ApiRequestOptions = {}): Observable<T> {
    return this.http
      .patch<T>(`${this.baseUrl}${path}`, body || {}, {
        headers: this.getHeaders(),
        withCredentials: true,
      })
      .pipe(catchError((err) => this.handleError(err, options)));
  }

  put<T>(path: string, body?: unknown, options: ApiRequestOptions = {}): Observable<T> {
    return this.http
      .put<T>(`${this.baseUrl}${path}`, body || {}, {
        headers: this.getHeaders(),
        withCredentials: true,
      })
      .pipe(catchError((err) => this.handleError(err, options)));
  }

  delete<T>(path: string, options: ApiRequestOptions = {}): Observable<T> {
    return this.http
      .delete<T>(`${this.baseUrl}${path}`, {
        headers: this.getHeaders(),
        withCredentials: true,
      })
      .pipe(catchError((err) => this.handleError(err, options)));
  }

  private getXsrfToken(): string | null {
    if (typeof document === 'undefined') return null;
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
  }

  private getHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const lang = untracked(() => (this.i18n?.currentLang ? this.i18n.currentLang() : null));
    if (lang) {
      headers = headers.set('Accept-Language', lang);
    }
    const xsrf = this.getXsrfToken();
    if (xsrf) {
      headers = headers.set('X-XSRF-TOKEN', xsrf);
    }
    return headers;
  }

  private handleError(error: HttpErrorResponse, options: ApiRequestOptions = {}): Observable<never> {
    let problem: ProblemDetail;

    if (error.error && typeof error.error === 'object') {
      const p = error.error as RawProblem;
      let detail = p.detail || p.message;
      // The server names the text by key: the client renders it from its own catalog (the language may have
      // changed since the request). Older answers without a key get the code's generic text.
      const errorKey = p.messageKey || `error.${String(p.code || 'API_ERROR').toLowerCase()}`;
      const localizedDetail = this.i18n.translate(errorKey, p.messageKey ? p.params : undefined);
      if (localizedDetail !== errorKey && (p.messageKey || !detail)) {
        detail = localizedDetail;
      }
      if (Array.isArray(p.invalid_params) && p.invalid_params.length > 0) {
        const fieldMsgs = p.invalid_params.map((ip) => `${ip.name}: ${ip.reason || ip.code}`).join('; ');
        detail = detail ? `${detail} (${fieldMsgs})` : fieldMsgs;
      }
      problem = {
        title: p.title || this.i18n.translate('common.error'),
        status: error.status || 400,
        code: p.code || 'API_ERROR',
        detail: detail || p.title || this.i18n.translate('common.operation_failed'),
        messageKey: p.messageKey,
        params: p.params,
        errors: Array.isArray(p.errors) ? p.errors : undefined,
        invalid_params: p.invalid_params,
      };
    } else {
      problem = {
        title: this.i18n.translate('common.connection_error'),
        status: error.status || 500,
        code: 'NETWORK_ERROR',
        detail:
          error.status === 0
            ? this.i18n.translate('common.server_unavailable')
            : error.message || this.i18n.translate('common.request_failed'),
      };
    }

    const retryAfter = error.headers?.get('Retry-After');
    if (retryAfter != null && /^\d+$/.test(retryAfter) && Number.isSafeInteger(Number(retryAfter))) {
      problem.retryAfterSeconds = Number(retryAfter);
    }

    // Don't toast 401 on initial /auth/me verification or normal 404 search; a lost session
    // is explained once by sessionExpiredInterceptor, not by every request that failed.
    const isAuthCheck = error.status === 401 && error.url?.includes('/auth/me');
    const isSessionLost = isSessionLoss(error);
    if (!isAuthCheck && !isSessionLost && options.notifyError !== false) {
      this.toast.error(problem.detail || problem.title);
    }

    return throwError(() => problem);
  }
}
