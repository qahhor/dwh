import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { InstalledModule } from './modules.models';

/** The installed modules and switching one on or off. */
@Injectable({ providedIn: 'root' })
export class ModulesApi {
  private readonly api = inject(ApiService);

  /** The server answers `status`; the screen reads `isActive`, so a switched-on module shows as active. */
  list(): Observable<InstalledModule[]> {
    return this.api.get<InstalledModule[]>('/modules').pipe(map((modules) => (modules ?? []).map(withActive)));
  }

  /** The request states the result, so a repeated switch changes nothing twice. */
  setEnabled(code: string, enabled: boolean): Observable<InstalledModule> {
    return this.api.put<InstalledModule>(`/modules/${code}/enabled`, { enabled }).pipe(map(withActive));
  }
}

function withActive(module: InstalledModule): InstalledModule {
  return { ...module, isActive: module.status === 'ACTIVE' };
}
