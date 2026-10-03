import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, map, of } from 'rxjs';

import { ModulesApi } from './modules.api';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

import { InstalledModule, ModuleFilterTab } from './modules.models';
import { ModulesStatsComponent } from './components/modules-stats.component';
import { ModulesToolbarComponent } from './components/modules-toolbar.component';
import { ModulesTableComponent } from './components/modules-table.component';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

export type { InstalledModule, ModuleFilterTab };

@Component({
  selector: 'app-modules',
  imports: [
    UiPageHeaderComponent,
    TranslatePipe,
    SMTButtonComponent,
    ModulesStatsComponent,
    ModulesToolbarComponent,
    ModulesTableComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="modules-page" aria-labelledby="modules-title">
      <!-- Header -->
      <ui-page-header
        [title]="'modules.title' | t"
        [eyebrow]="'modules.subtitle' | t"
        [subtitle]="'modules.description' | t"
        titleId="modules-title"
      >
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtIcon="refresh"
          [smtLoading]="isLoading()"
          [attr.aria-label]="'common.refresh' | t"
          (click)="loadModules()"
        >
          {{ 'common.refresh' | t }}
        </button>
      </ui-page-header>

      <!-- Stats overview cards -->
      <app-modules-stats
        [totalCount]="modules().length"
        [activeCount]="activeCount()"
        [systemCount]="systemCount()"
        [customCount]="customCount()"
      />

      <!-- Controls: search and tabs -->
      <app-modules-toolbar
        [searchQuery]="searchQuery()"
        [filterTab]="filterTab()"
        [totalCount]="modules().length"
        [activeCount]="activeCount()"
        [systemCount]="systemCount()"
        [customCount]="customCount()"
        (searchChange)="searchQuery.set($event)"
        (filterTabChange)="filterTab.set($event)"
      />

      <!-- Modules Table -->
      <app-modules-table
        [modules]="filteredModules()"
        [isLoading]="isLoading()"
        [canManage]="canManage()"
        [togglingCode]="togglingCode()"
        (moduleToggle)="toggleModule($event.module, $event.enabled)"
      />

      <!-- Developer Info / CLI Help Banner -->
      <div class="developer-info-card">
        <div class="dev-card-header">
          <span class="material-symbols-outlined dev-icon" aria-hidden="true">terminal</span>
          <div>
            <h3 class="dev-card-title">{{ 'modules.cli_title' | t }}</h3>
            <p class="dev-card-desc">{{ 'modules.cli_desc' | t }}</p>
          </div>
        </div>
        <div class="dev-card-body">
          <p class="cli-command-label">{{ 'modules.cli_example_label' | t }}</p>
          <pre
            class="cli-command-box"
          ><code>node tools/cms-cli/bin/cms.mjs module new crm --title "CRM" --title-en "CRM and deals"
node tools/cms-cli/bin/cms.mjs entity new crm deals --title "Deals" --title-en "Deals"</code></pre>
        </div>
      </div>
    </section>
  `,
  styleUrl: './modules.component.css',
})
export class ModulesComponent {
  private readonly modulesApi = inject(ModulesApi);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  /** A toggle changes it at once; a failed reload keeps the list on screen. */
  readonly modules = linkedSignal<InstalledModule[] | undefined, InstalledModule[]>({
    source: () => this.modulesResource.value(),
    computation: (loaded, previous) => loaded ?? previous?.value ?? [],
  });
  readonly togglingCode = signal<string | null>(null);

  readonly searchQuery = signal('');
  readonly filterTab = signal<ModuleFilterTab>('all');

  readonly isLoading = computed(() => this.modulesResource.isLoading());

  readonly activeCount = computed(() => this.modules().filter((m) => m.isActive).length);

  readonly systemCount = computed(() => this.modules().filter((m) => m.isSystem).length);

  readonly customCount = computed(() => this.modules().filter((m) => !m.isSystem).length);

  readonly filteredModules = computed(() => {
    let list = this.modules();
    const tab = this.filterTab();
    if (tab === 'active') {
      list = list.filter((m) => m.isActive);
    } else if (tab === 'system') {
      list = list.filter((m) => m.isSystem);
    } else if (tab === 'custom') {
      list = list.filter((m) => !m.isSystem);
    }

    const q = this.searchQuery().trim().toLowerCase();
    if (q) {
      list = list.filter((m) => {
        const code = (m.code || m.moduleCode || '').toLowerCase();
        return (
          m.name.toLowerCase().includes(q) ||
          code.includes(q) ||
          (m.description && m.description.toLowerCase().includes(q))
        );
      });
    }

    return list;
  });

  private readonly modulesResource = rxResource({
    stream: () =>
      this.modulesApi.list().pipe(
        map((list) => list || []),
        catchError(() => {
          this.toast.error(this.i18n.translate('modules.load_error'));
          return of(undefined);
        }),
      ),
  });

  canManage(): boolean {
    return this.permissions.canManage('md.modules');
  }

  loadModules(): void {
    this.modulesResource.reload();
  }

  /** Shows the change at once and takes it back if the server refuses it. */
  toggleModule(mod: InstalledModule, targetActive: boolean): void {
    if (mod.isSystem) {
      this.toast.error(this.i18n.translate('modules.system_cannot_disable'));
      return;
    }

    const code = mod.code || mod.moduleCode || '';
    if (!code) return;

    const setActive = (active: boolean) =>
      this.modules.update((list) =>
        list.map((m) => (m.code === code || m.moduleCode === code ? { ...m, isActive: active } : m)),
      );
    setActive(targetActive);
    this.togglingCode.set(code);
    this.modulesApi.setEnabled(code, targetActive).subscribe({
      next: (updated) => {
        this.togglingCode.set(null);
        this.modules.update((list) =>
          list.map((m) =>
            m.code === code || m.moduleCode === code
              ? { ...m, ...updated, isActive: updated.isActive ?? updated.status === 'ACTIVE' }
              : m,
          ),
        );
        this.toast.success(
          this.i18n.translate(targetActive ? 'modules.msg.activated' : 'modules.msg.deactivated', { name: mod.name }),
        );
      },
      error: () => {
        this.togglingCode.set(null);
        setActive(!targetActive);
        this.toast.error(this.i18n.translate('modules.toggle_error'));
      },
    });
  }

  trackByModuleCode(_index: number, item: InstalledModule): string {
    return item.code || item.moduleCode || '';
  }
}
