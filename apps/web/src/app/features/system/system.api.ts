import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';

export interface SystemInfo {
  appVersion: string;
  schemaVersion: string;
  organization: {
    code: string;
    name: string;
    resourceProfile: string;
  };
  storageProvider: string;
  components: Record<string, { status: string }>;
  backup: {
    status: string;
    completedAt?: string | null;
    failureCode?: string | null;
    freshness: string;
    ageSeconds?: number | null;
    maxAgeSeconds?: number | null;
  };
  checkedAt: string;
}

/** The system status endpoint. The screen shows its own error, so no general error toast. */
@Injectable({ providedIn: 'root' })
export class SystemApi {
  private readonly api = inject(ApiService);

  info(): Observable<SystemInfo> {
    return this.api.get<SystemInfo>('/system/info', undefined, { notifyError: false });
  }
}
