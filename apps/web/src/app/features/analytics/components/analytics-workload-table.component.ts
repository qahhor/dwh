import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  signal,
  viewChild,
  input,
} from '@angular/core';

import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UserWorkload } from '../analytics.models';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';

@Component({
  selector: 'app-analytics-workload-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTAvatarComponent, SMTInputComponent, TranslatePipe, SMTBadgeComponent, UiLocalTableComponent],
  template: `
    <div class="table-card" style="margin-top: 20px;">
      <div class="card-header-row" style="padding: 14px 20px; border-bottom: 1px solid var(--border-color);">
        <div>
          <h2 class="card-title">{{ 'analytics.utilizaciya_i_zagruzka_komandy' | t }}</h2>
          <p class="card-subtitle">{{ 'analytics.raspredelenie_aktivnyh_i_vypolnennyh_zadach_po_i' | t }}</p>
        </div>

        <!-- Quick User Filter -->
        @if (workload().length > 0) {
          <smt-input
            class="user-search-box"
            type="search"
            smtIcon="search"
            clearable
            smtSize="sm"
            [placeholder]="'analytics.poisk_sotrudnika' | t"
            [smtAriaLabel]="'analytics.poisk_sotrudnika' | t"
            [value]="searchUserQuery()"
            (valueChange)="searchUserQuery.set($any($event) ?? '')"
          />
        }
      </div>

      <div
        class="table-scroll"
        role="region"
        tabindex="0"
        [attr.aria-label]="'analytics.utilizaciya_i_zagruzka_komandy' | t"
      >
        <ui-local-table
          [rows]="filteredWorkload()"
          [config]="config()"
          [sortValues]="sortValues"
          [emptyTemplate]="emptyWorkload"
        />
      </div>
    </div>

    <ng-template #userCell let-u>
      <div class="user-cell">
        <smt-avatar [name]="u.userName" smtSize="sm" />
        <span class="user-name-text">{{ u.userName }}</span>
      </div>
    </ng-template>
    <ng-template #loginCell let-u
      ><span class="mono badge badge-neutral">{{ u.userLogin }}</span></ng-template
    >
    <ng-template #assignedCell let-u
      ><span class="num-strong">{{ u.assignedTasks }}</span></ng-template
    >
    <ng-template #completedCell let-u
      ><span class="num-strong text-success">{{ u.completedTasks }}</span></ng-template
    >
    <ng-template #efficiencyCell let-u>
      <div class="efficiency-cell">
        <smt-badge smtSize="SM" [smtVariant]="efficiencyOf(u) >= 0.7 ? 'success' : 'gray'">
          {{ getEfficiencyPercent(u) }}%
        </smt-badge>
        @if (u.assignedTasks > 0) {
          <div class="eff-mini-bar-bg" aria-hidden="true">
            <div
              class="eff-mini-bar-fill"
              [style.width.%]="getEfficiencyPercent(u)"
              [style.background-color]="efficiencyOf(u) >= 0.7 ? 'var(--success)' : 'var(--primary)'"
            ></div>
          </div>
        }
      </div>
    </ng-template>
    <ng-template #emptyWorkload>
      @if (!loading() && !error()) {
        <p class="empty">{{ 'analytics.dannye_po_zagruzke_sotrudnikov_otsutstvuyut' | t }}</p>
      }
    </ng-template>
  `,
  styleUrl: './analytics-workload-table.component.css',
})
export class AnalyticsWorkloadTableComponent {
  private readonly i18n = inject(I18nService);

  readonly loading = input(false);
  readonly error = input('');

  readonly workload = input<UserWorkload[]>([]);

  private readonly userCell = viewChild.required<TemplateRef<unknown>>('userCell');
  private readonly loginCell = viewChild.required<TemplateRef<unknown>>('loginCell');
  private readonly assignedCell = viewChild.required<TemplateRef<unknown>>('assignedCell');
  private readonly completedCell = viewChild.required<TemplateRef<unknown>>('completedCell');
  private readonly efficiencyCell = viewChild.required<TemplateRef<unknown>>('efficiencyCell');

  readonly searchUserQuery = signal('');

  /**
   * The people matching the search, busiest first (by name on a tie). This is
   * the order the table falls back to when a header click switches sorting off.
   */
  readonly filteredWorkload = computed(() => {
    const query = this.searchUserQuery().trim().toLowerCase();
    let list = this._workload();
    if (query) {
      list = list.filter((u) => u.userName.toLowerCase().includes(query) || u.userLogin.toLowerCase().includes(query));
    }
    return [...list].sort((a, b) => b.assignedTasks - a.assignedTasks || a.userName.localeCompare(b.userName));
  });

  readonly config = computed<TableConfig<UserWorkload>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, u) => u.userId,
      ariaLabel: this.i18n.translate('analytics.utilizaciya_i_zagruzka_komandy'),
      layout: 'fit',
      columns: {
        name: { header: header('analytics.sotrudnik'), content: cell(this.userCell), width: '240px' },
        login: { header: header('analytics.login'), content: cell(this.loginCell) },
        assigned: { header: header('analytics.naznacheno_zadach'), content: cell(this.assignedCell), align: 'right' },
        completed: { header: header('analytics.zaversheno'), content: cell(this.completedCell), align: 'right' },
        efficiency: { header: header('analytics.effektivnost'), content: cell(this.efficiencyCell) },
      },
      columnsOrder: ['name', 'login', 'assigned', 'completed', 'efficiency'],
    };
  });
  private readonly _workload = computed<UserWorkload[]>(() => this.workload() || []);

  /** The whole team is loaded, so a header click sorts every person, not a page. */
  readonly sortValues = {
    name: (u: UserWorkload) => u.userName,
    login: (u: UserWorkload) => u.userLogin,
    assigned: (u: UserWorkload) => u.assignedTasks,
    completed: (u: UserWorkload) => u.completedTasks,
    efficiency: (u: UserWorkload) => this.efficiencyOf(u),
  };

  /** Share of assigned tasks that are done; nobody assigned counts as none done. */
  efficiencyOf(u: UserWorkload): number {
    return u.assignedTasks > 0 ? u.completedTasks / u.assignedTasks : 0;
  }

  getEfficiencyPercent(u: UserWorkload): number {
    return Math.min(100, Math.round(this.efficiencyOf(u) * 100));
  }
}
