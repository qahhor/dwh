import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { BulkResult } from '../bulk/bulk';

/** Actions every entity declared on the server offers (ADR-0019). */
@Injectable({ providedIn: 'root' })
export class EntitiesApi {
  private readonly api = inject(ApiService);

  /** Deletes the chosen records of an entity; the result names the ones that failed and why. */
  bulkDelete(code: string, ids: number[]): Observable<BulkResult> {
    return this.api.post<BulkResult>(
      `/entities/${encodeURIComponent(code)}/bulk`,
      { action: 'delete', ids },
      { notifyError: false },
    );
  }
}
