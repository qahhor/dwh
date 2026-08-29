import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ProblemDetail } from '../models/common.models';
import { ToastService } from './toast.service';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private readonly baseUrl = '/api/v1';

  constructor(
    private http: HttpClient,
    private toast: ToastService
  ) {}

  get<T>(path: string, params?: Record<string, any>): Observable<T> {
    let httpParams = new HttpParams();
    if (params) {
      Object.keys(params).forEach(key => {
        if (params[key] !== undefined && params[key] !== null && params[key] !== '') {
          httpParams = httpParams.set(key, params[key].toString());
        }
      });
    }

    return this.http.get<T>(`${this.baseUrl}${path}`, {
      params: httpParams,
      withCredentials: true
    }).pipe(
      catchError(err => this.handleError(err))
    );
  }

  post<T>(path: string, body?: any): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body || {}, {
      withCredentials: true
    }).pipe(
      catchError(err => this.handleError(err))
    );
  }

  patch<T>(path: string, body?: any): Observable<T> {
    return this.http.patch<T>(`${this.baseUrl}${path}`, body || {}, {
      withCredentials: true
    }).pipe(
      catchError(err => this.handleError(err))
    );
  }

  put<T>(path: string, body?: any): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body || {}, {
      withCredentials: true
    }).pipe(
      catchError(err => this.handleError(err))
    );
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`, {
      withCredentials: true
    }).pipe(
      catchError(err => this.handleError(err))
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let problem: ProblemDetail;

    if (error.error && typeof error.error === 'object') {
      const p = error.error as any;
      let detail = p.detail || p.message;
      if (Array.isArray(p.invalid_params) && p.invalid_params.length > 0) {
        const fieldMsgs = p.invalid_params.map((ip: any) => `${ip.name}: ${ip.reason || ip.code}`).join('; ');
        detail = detail ? `${detail} (${fieldMsgs})` : fieldMsgs;
      }
      problem = {
        title: p.title || 'Ошибка',
        status: error.status || 400,
        code: p.code || 'API_ERROR',
        detail: detail || p.title || 'Произошла ошибка при выполнении операции',
        invalid_params: p.invalid_params
      };
    } else {
      problem = {
        title: 'Ошибка соединения',
        status: error.status || 500,
        code: 'NETWORK_ERROR',
        detail: error.status === 0 ? 'Сервер недоступен или отсутствует соединение с сетью' : (error.message || 'Не удалось выполнить запрос')
      };
    }

    // Don't toast 401 on initial /auth/me verification or normal 404 search
    const isAuthCheck = error.status === 401 && error.url?.includes('/auth/me');
    if (!isAuthCheck) {
      this.toast.error(problem.detail || problem.title);
    }

    return throwError(() => problem);
  }

}
