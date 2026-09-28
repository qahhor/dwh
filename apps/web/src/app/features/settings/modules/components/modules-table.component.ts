import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { InstalledModule } from '../modules.models';

/**
 * Installed modules on the kit table. Every module is loaded, so a header
 * click sorts the whole list; a module is switched on or off in its row.
 */
@Component({
  selector: 'app-modules-table',
  imports: [TranslatePipe, UiLocalTableComponent, SMTSwitchComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="table-container">
      <ui-local-table
        [rows]="modules()"
        [config]="config()"
        [sortValues]="sortValues"
        [loading]="isLoading() && modules().length === 0"
        [emptyTemplate]="emptyState"
      />
    </div>

    <ng-template #moduleCell let-mod>
      <div class="module-info">
        <span class="module-name">{{ mod.name }}</span>
        @if (mod.description) {
          <span class="module-desc">{{ mod.description }}</span>
        }
      </div>
    </ng-template>
    <ng-template #codeCell let-mod
      ><code class="code-badge">{{ mod.code }}</code></ng-template
    >
    <ng-template #versionCell let-mod
      ><span class="version-badge">v{{ mod.version }}</span></ng-template
    >
    <ng-template #typeCell let-mod>
      <span class="badge" [class.badge-primary]="mod.isSystem" [class.badge-neutral]="!mod.isSystem">
        {{ (mod.isSystem ? 'modules.type.system' : 'modules.type.custom') | t }}
      </span>
    </ng-template>
    <ng-template #statusCell let-mod>
      <span class="badge" [class.badge-active]="mod.isActive" [class.badge-inactive]="!mod.isActive">
        {{ (mod.isActive ? 'modules.status.active' : 'modules.status.disabled') | t }}
      </span>
    </ng-template>
    <ng-template #actionsCell let-mod>
      <div class="action-cell">
        <smt-switch
          smtSize="sm"
          data-testid="module-toggle"
          [checked]="mod.isActive"
          [disabled]="mod.isSystem || togglingCode() === mod.code"
          [title]="mod.isSystem ? ('modules.system_cannot_disable' | t) : ''"
          [smtAriaLabel]="(mod.isActive ? 'modules.action.disable' : 'modules.action.enable') | t: { name: mod.name }"
          (smtUserChange)="moduleToggle.emit({ module: mod, enabled: $event })"
        />
        @if (mod.isSystem) {
          <span class="system-locked-hint" [title]="'modules.system_cannot_disable' | t">
            <span class="material-symbols-outlined lock-icon" aria-hidden="true">lock</span>
          </span>
        }
      </div>
    </ng-template>
    <ng-template #emptyState>
      <div class="empty-state">
        <span class="material-symbols-outlined empty-icon" aria-hidden="true">extension_off</span>
        <p class="empty-title">{{ 'modules.empty_title' | t }}</p>
        <p class="empty-desc">{{ 'modules.empty_desc' | t }}</p>
      </div>
    </ng-template>
  `,
  styleUrl: './modules-table.component.css',
})
export class ModulesTableComponent {
  private readonly i18n = inject(I18nService);

  readonly modules = input.required<InstalledModule[]>();
  readonly isLoading = input.required<boolean>();
  readonly canManage = input.required<boolean>();

  readonly togglingCode = input<string | null>(null);

  readonly moduleToggle = output<{ module: InstalledModule; enabled: boolean }>();

  private readonly moduleCell = viewChild.required<TemplateRef<unknown>>('moduleCell');
  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly versionCell = viewChild.required<TemplateRef<unknown>>('versionCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly config = computed<TableConfig<InstalledModule>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const columns: TableConfig<InstalledModule>['columns'] = {
      module: { header: header('modules.col.module'), content: cell(this.moduleCell) },
      code: { header: header('modules.col.code'), content: cell(this.codeCell), width: '160px' },
      version: { header: header('modules.col.version'), content: cell(this.versionCell), width: '110px' },
      type: { header: header('modules.col.type'), content: cell(this.typeCell), width: '130px' },
      status: { header: header('modules.col.status'), content: cell(this.statusCell), width: '130px' },
    };
    const order = ['module', 'code', 'version', 'type', 'status'];
    if (this.canManage()) {
      columns['actions'] = {
        header: header('modules.col.actions'),
        content: cell(this.actionsCell),
        width: '120px',
        align: 'right',
      };
      order.push('actions');
    }
    return {
      trackBy: (_index, mod) => mod.code,
      ariaLabel: this.i18n.translate('modules.title'),
      layout: 'fit',
      columns,
      columnsOrder: order,
    };
  });

  readonly sortValues = {
    module: (mod: InstalledModule) => mod.name,
    code: (mod: InstalledModule) => mod.code,
    version: (mod: InstalledModule) => mod.version,
    type: (mod: InstalledModule) => (mod.isSystem ? 0 : 1),
    status: (mod: InstalledModule) => (mod.isActive ? 0 : 1),
  };
}
