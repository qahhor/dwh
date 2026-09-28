import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { ApiService } from './api.service';

export interface InstalledModule {
  code: string;
  name: string;
  description?: string | null;
  version: string;
  icon?: string | null;
  route?: string | null;
  isSystem: boolean;
  status: 'ACTIVE' | 'DISABLED';
  sortOrder?: number;
  attributes?: Record<string, unknown>;
  createdAt?: string;
  modifiedAt?: string;
  isActive?: boolean;
  moduleCode?: string;
}

/** A module as the server may send it: older answers name the code `moduleCode` and carry `isActive`, not `status`. */
type RawModule = Partial<Omit<InstalledModule, 'status'>> & { status?: string };

const SYSTEM_MODULES = new Set(['iam', 'tasks', 'files', 'audit', 'search']);

/** Modules with a built-in, permission-gated nav item: they must not be duplicated in the custom "Modules" section. */
const BUILT_IN_NAV_MODULES = new Set(['notes', 'upl']);

@Injectable({ providedIn: 'root' })
export class ModuleService {
  private readonly api = inject(ApiService);

  readonly modules = signal<InstalledModule[]>([]);
  readonly activeModuleCodes = signal<Set<string>>(new Set(SYSTEM_MODULES));
  readonly isLoading = signal(false);
  readonly isLoaded = signal(false);

  loadActiveModules(): Observable<InstalledModule[]> {
    this.isLoading.set(true);
    return this.api.get<RawModule[]>('/modules/active').pipe(
      map((data) => this.normalizeModules(data || [])),
      tap({
        next: (list) => {
          const activeSet = new Set<string>(SYSTEM_MODULES);
          for (const m of list) {
            if (m.isActive) {
              activeSet.add(m.code.toLowerCase());
            }
          }
          this.activeModuleCodes.set(activeSet);
          this.modules.set(list);
          this.isLoaded.set(true);
          this.isLoading.set(false);
        },
        error: () => {
          this.isLoading.set(false);
        },
      }),
    );
  }

  loadAllModules(): Observable<InstalledModule[]> {
    this.isLoading.set(true);
    return this.api.get<RawModule[]>('/modules').pipe(
      map((data) => this.normalizeModules(data || [])),
      tap({
        next: (list) => {
          const activeSet = new Set<string>(SYSTEM_MODULES);
          for (const m of list) {
            if (m.isActive) {
              activeSet.add(m.code.toLowerCase());
            }
          }
          this.activeModuleCodes.set(activeSet);
          this.modules.set(list);
          this.isLoaded.set(true);
          this.isLoading.set(false);
        },
        error: () => {
          this.isLoading.set(false);
        },
      }),
    );
  }

  isModuleActive(code: string): boolean {
    const normalized = (code || '').toLowerCase().trim();
    if (SYSTEM_MODULES.has(normalized)) {
      return true;
    }
    if (!this.isLoaded()) {
      return true; // Avoid flickering before initial load completes
    }
    return this.activeModuleCodes().has(normalized);
  }

  getActiveCustomModules(): InstalledModule[] {
    return this.modules().filter(
      (m) => !m.isSystem && m.isActive && m.route && !BUILT_IN_NAV_MODULES.has(m.code.toLowerCase()),
    );
  }

  toggleModule(code: string, enabled: boolean): Observable<InstalledModule> {
    const normalizedCode = code.toLowerCase().trim();
    return this.api.post<RawModule | null>(`/modules/${encodeURIComponent(normalizedCode)}/toggle`, { enabled }).pipe(
      map((updated) => this.normalizeSingle(updated, normalizedCode, enabled)),
      tap((normalized) => {
        this.modules.update((list) => {
          const existing = list.some((m) => m.code === normalizedCode);
          if (existing) {
            return list.map((m) => (m.code === normalizedCode ? normalized : m));
          }
          return [...list, normalized];
        });

        this.activeModuleCodes.update((current) => {
          const updatedSet = new Set(current);
          if (normalized.isActive) {
            updatedSet.add(normalizedCode);
          } else {
            updatedSet.delete(normalizedCode);
          }
          return updatedSet;
        });
      }),
    );
  }

  private normalizeModules(data: RawModule[]): InstalledModule[] {
    return data.map((m) => this.normalizeSingle(m, '', true));
  }

  private normalizeSingle(m: RawModule | null, fallbackCode: string, fallbackActive: boolean): InstalledModule {
    const code = (m?.code || m?.moduleCode || fallbackCode).toLowerCase().trim();
    const isActive = m?.status ? m.status === 'ACTIVE' : (m?.isActive ?? fallbackActive);
    return {
      name: '',
      version: '',
      isSystem: false,
      ...m,
      code,
      moduleCode: code,
      isActive,
      status: isActive ? 'ACTIVE' : 'DISABLED',
    };
  }
}
