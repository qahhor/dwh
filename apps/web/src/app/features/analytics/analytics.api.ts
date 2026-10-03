import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AnalyticsSummary, ProjectDistribution, TrendDataPoint, UserWorkload } from './analytics.models';

/**
 * The dashboard's reads. The raw HTTP error reaches the screen, which shows the server's detail or its own text
 * when an answer has none (ApiService would replace that with a generic text and a toast).
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsApi {
  private readonly http = inject(HttpClient);

  summary(): Observable<AnalyticsSummary> {
    return this.http.get<AnalyticsSummary>('/api/v1/analytics/summary');
  }

  trends(range: string): Observable<TrendDataPoint[]> {
    return this.http.get<TrendDataPoint[]>('/api/v1/analytics/trends', { params: { range } });
  }

  projects(): Observable<ProjectDistribution[]> {
    return this.http.get<ProjectDistribution[]>('/api/v1/analytics/projects');
  }

  workload(): Observable<UserWorkload[]> {
    return this.http.get<UserWorkload[]>('/api/v1/analytics/workload');
  }
}
