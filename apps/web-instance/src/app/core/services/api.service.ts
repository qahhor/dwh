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

    if (error.error && typeof error.error === 'object' && error.error.code) {
      problem = error.error as ProblemDetail;
    } else {
      problem = {
        title: 'Ошибка соединения',
        status: error.status || 500,
        code: 'NETWORK_ERROR',
        detail: error.message || 'Не удалось выполнить запрос к серверу'
      };
    }

    // Don't toast 401 on initial /auth/me check
    if (error.status !== 401 || !error.url?.includes('/auth/me')) {
      this.toast.error(problem.detail || problem.title, problem.code);
    }

    return throwError(() => problem);
  }
}
