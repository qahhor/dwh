import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { InstalledModule } from './modules.models';

/** The installed modules and switching one on or off. */
@Injectable({ providedIn: 'root' })
export class ModulesApi {
  private readonly api = inject(ApiService);

  list(): Observable<InstalledModule[]> {
    return this.api.get<InstalledModule[]>('/modules');
  }

  /** The request states the result, so a repeated switch changes nothing twice. */
  setEnabled(code: string, enabled: boolean): Observable<InstalledModule> {
    return this.api.put<InstalledModule>(`/modules/${code}/enabled`, { enabled });
  }
}
