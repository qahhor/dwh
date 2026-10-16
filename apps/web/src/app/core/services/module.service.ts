import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { ApiService } from './api.service';

export interface InstalledModule {
  code: string;
  /** The registry's own name: shown only for a module without a manifest (no `titleKey`). */
  name: string;
  description?: string | null;
  /** The catalog key of the module's name in every language, from its manifest (ADR-0033, 6.2). */
  titleKey?: string | null;
  /** The catalog key of the module's description, when the module has one. */
  descriptionKey?: string | null;
  /** The module's version from its manifest (ADR-0033, 6.4); null for a module registered without code. */
  version: string | null;
  /** The least platform API version the module runs on, from its manifest. */
  minPlatform?: string | null;
  /** The modules it needs and their least versions, from its manifest. */
  dependencies?: { code: string; version: string }[];
  icon?: string | null;
  route?: string | null;
  isSystem: boolean;
  status: 'ACTIVE' | 'DISABLED';
  sortOrder?: number;
  attributes?: Record<string, unknown>;
  createdAt?: string;
  modifiedAt?: string;
  isActive?: boolean;
}

type Translate = (key: string, params?: Record<string, string | number>) => string;

/** The module's name in the current language: its catalog key when it has a manifest, else the registry's name. */
export function moduleTitle(module: InstalledModule, translate: Translate): string {
  return module.titleKey ? translate(module.titleKey) : module.name;
}

/** The module's description in the current language, or the registry's own one. */
export function moduleDescription(module: InstalledModule, translate: Translate): string {
  return module.descriptionKey ? translate(module.descriptionKey) : (module.description ?? '');
}

const SYSTEM_MODULES = new Set(['iam', 'files', 'audit', 'search']);

/** Modules with a built-in, permission-gated nav item: they must not be duplicated in the custom "Modules" section. */
const BUILT_IN_NAV_MODULES = new Set(['tasks', 'notes', 'upl', 'example']);

@Injectable({ providedIn: 'root' })
export class ModuleService {
  private readonly api = inject(ApiService);

  readonly modules = signal<InstalledModule[]>([]);
  readonly activeModuleCodes = signal<Set<string>>(new Set(SYSTEM_MODULES));
  readonly isLoading = signal(false);
  readonly isLoaded = signal(false);

  loadActiveModules(): Observable<InstalledModule[]> {
    this.isLoading.set(true);
    return this.api.get<InstalledModule[]>('/modules/active').pipe(
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
    return this.api.get<InstalledModule[]>('/modules').pipe(
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
    return this.api.put<InstalledModule>(`/modules/${encodeURIComponent(normalizedCode)}/enabled`, { enabled }).pipe(
      map((updated) => this.normalizeSingle(updated)),
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

  private normalizeModules(data: InstalledModule[]): InstalledModule[] {
    return data.map((m) => this.normalizeSingle(m));
  }

  private normalizeSingle(module: InstalledModule): InstalledModule {
    return { ...module, code: module.code.toLowerCase().trim(), isActive: module.status === 'ACTIVE' };
  }
}
