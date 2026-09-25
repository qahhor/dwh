import { Component, Input, Signal, TemplateRef, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiLocalTableComponent } from '../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { UserWorkload } from '../analytics.models';

@Component({
  selector: 'app-analytics-workload-table',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiBadgeComponent,
    UiLocalTableComponent
  ],
  template: `
    <div class="table-card" style="margin-top: 20px;">
      <div class="card-header-row" style="padding: 14px 20px; border-bottom: 1px solid var(--border-color);">
        <div>
          <h2 class="card-title">{{ 'analytics.utilizaciya_i_zagruzka_komandy' | t }}</h2>
          <p class="card-subtitle">{{ 'analytics.raspredelenie_aktivnyh_i_vypolnennyh_zadach_po_i' | t }}</p>
        </div>

        <!-- Quick User Filter -->
        <div class="user-search-box" *ngIf="workload.length > 0">
          <span class="material-symbols-outlined search-ico" aria-hidden="true">search</span>
          <input
            type="text"
            class="search-mini-input"
            [placeholder]="'analytics.poisk_sotrudnika' | t"
            [attr.aria-label]="'analytics.poisk_sotrudnika' | t"
            [ngModel]="searchUserQuery()"
            (ngModelChange)="searchUserQuery.set($event)"
          />
          <button
            *ngIf="searchUserQuery()"
            type="button"
            class="clear-mini-btn"
            (click)="searchUserQuery.set('')"
            [attr.aria-label]="'common.clear' | t"
          >
            <span class="material-symbols-outlined" style="font-size: 14px;" aria-hidden="true">close</span>
          </button>
        </div>
      </div>

      <div class="table-scroll" role="region" tabindex="0" [attr.aria-label]="'analytics.utilizaciya_i_zagruzka_komandy' | t">
        <ui-local-table [rows]="filteredWorkload()" [config]="config()" [sortValues]="sortValues" [emptyTemplate]="emptyWorkload" />
      </div>
    </div>

    <ng-template #userCell let-u>
      <div class="user-cell">
        <div class="user-avatar-sm" aria-hidden="true" [style.background-color]="getAvatarBgColor(u.userName)">
          {{ getUserInitial(u.userName) }}
        </div>
        <span class="user-name-text">{{ u.userName }}</span>
      </div>
    </ng-template>
    <ng-template #loginCell let-u><span class="mono badge badge-neutral">{{ u.userLogin }}</span></ng-template>
    <ng-template #assignedCell let-u><span class="num-strong">{{ u.assignedTasks }}</span></ng-template>
    <ng-template #completedCell let-u><span class="num-strong text-success">{{ u.completedTasks }}</span></ng-template>
    <ng-template #efficiencyCell let-u>
      <div class="efficiency-cell">
        <ui-badge [variant]="efficiencyOf(u) >= 0.7 ? 'success' : 'neutral'">
          {{ getEfficiencyPercent(u) }}%
        </ui-badge>
        @if (u.assignedTasks > 0) {
          <div class="eff-mini-bar-bg" aria-hidden="true">
            <div class="eff-mini-bar-fill"
              [style.width.%]="getEfficiencyPercent(u)"
              [style.background-color]="efficiencyOf(u) >= 0.7 ? 'var(--success)' : 'var(--primary)'">
            </div>
          </div>
        }
      </div>
    </ng-template>
    <ng-template #emptyWorkload>
      @if (!loading && !error) {
        <p class="empty">{{ 'analytics.dannye_po_zagruzke_sotrudnikov_otsutstvuyut' | t }}</p>
      }
    </ng-template>
  `,
  styles: [`
    .table-card {
      min-width: 0;
      max-width: 100%;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm);
      overflow: hidden;
    }

    .card-header-row {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
    }

    .card-title {
      font-size: 15px;
      font-weight: 700;
      color: var(--text-main);
      overflow-wrap: anywhere;
    }

    .card-subtitle {
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 2px;
      overflow-wrap: anywhere;
    }

    .user-search-box {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px 8px;
      height: 28px;
      transition: border-color 0.15s ease;
    }
    .user-search-box:focus-within {
      border-color: var(--primary);
      box-shadow: 0 0 0 1px var(--primary);
    }
    .search-ico {
      font-size: 16px;
      color: var(--text-muted);
    }
    .search-mini-input {
      border: none;
      outline: none;
      background: transparent;
      font-size: 12px;
      color: var(--text-main);
      width: 130px;
    }
    .clear-mini-btn {
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      padding: 0;
    }
    .clear-mini-btn:hover {
      color: var(--text-main);
    }

    .table-scroll {
      width: 100%;
      overflow-x: auto;
    }
    .table-scroll:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: -2px;
    }

    .num-strong { font-weight: 600; }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .user-avatar-sm {
      width: 26px;
      height: 26px;
      border-radius: 50%;
      background-color: var(--primary-subtle);
      color: var(--primary-text);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 700;
    }

    .user-name-text {
      font-weight: 600;
      color: var(--text-main);
    }

    .mono {
      font-family: monospace;
    }

    .badge {
      font-size: 11px;
      font-weight: 500;
      padding: 2px 6px;
      border-radius: 4px;
    }
    .badge-neutral {
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }

    .text-success { color: var(--success); }

    .efficiency-cell {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .eff-mini-bar-bg {
      width: 50px;
      height: 5px;
      border-radius: 9999px;
      background-color: var(--bg-hover);
      overflow: hidden;
    }
    .eff-mini-bar-fill {
      height: 100%;
      border-radius: 9999px;
      transition: width 0.2s ease;
    }

    .empty {
      padding: 30px;
      text-align: center;
      color: var(--text-muted);
    }
  `]
})
export class AnalyticsWorkloadTableComponent {
  private _workload = signal<UserWorkload[]>([]);

  @Input() set workload(value: UserWorkload[]) {
    this._workload.set(value || []);
  }
  get workload(): UserWorkload[] {
    return this._workload();
  }

  @Input() loading = false;
  @Input() error = '';

  searchUserQuery = signal('');

  private readonly i18n = inject(I18nService);
  private readonly userCell = viewChild.required<TemplateRef<unknown>>('userCell');
  private readonly loginCell = viewChild.required<TemplateRef<unknown>>('loginCell');
  private readonly assignedCell = viewChild.required<TemplateRef<unknown>>('assignedCell');
  private readonly completedCell = viewChild.required<TemplateRef<unknown>>('completedCell');
  private readonly efficiencyCell = viewChild.required<TemplateRef<unknown>>('efficiencyCell');

  /**
   * The people matching the search, busiest first (by name on a tie). This is
   * the order the table falls back to when a header click switches sorting off.
   */
  filteredWorkload = computed(() => {
    const query = this.searchUserQuery().trim().toLowerCase();
    let list = this._workload();
    if (query) {
      list = list.filter(u =>
        u.userName.toLowerCase().includes(query) ||
        u.userLogin.toLowerCase().includes(query)
      );
    }
    return [...list].sort((a, b) => b.assignedTasks - a.assignedTasks || a.userName.localeCompare(b.userName));
  });

  /** The whole team is loaded, so a header click sorts every person, not a page. */
  readonly sortValues = {
    name: (u: UserWorkload) => u.userName,
    login: (u: UserWorkload) => u.userLogin,
    assigned: (u: UserWorkload) => u.assignedTasks,
    completed: (u: UserWorkload) => u.completedTasks,
    efficiency: (u: UserWorkload) => this.efficiencyOf(u)
  };

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
        efficiency: { header: header('analytics.effektivnost'), content: cell(this.efficiencyCell) }
      },
      columnsOrder: ['name', 'login', 'assigned', 'completed', 'efficiency']
    };
  });

  /** Share of assigned tasks that are done; nobody assigned counts as none done. */
  efficiencyOf(u: UserWorkload): number {
    return u.assignedTasks > 0 ? u.completedTasks / u.assignedTasks : 0;
  }

  getEfficiencyPercent(u: UserWorkload): number {
    return Math.min(100, Math.round(this.efficiencyOf(u) * 100));
  }

  getAvatarBgColor(name: string): string {
    const colors = [
      '#4338ca', '#0369a1', '#047857', '#b45309',
      '#6d28d9', '#be185d', '#0f766e', '#c2410c'
    ];
    let hash = 0;
    for (let i = 0; i < (name || '').length; i++) {
      hash = name.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  }

  getUserInitial(name: string): string {
    return (name || '').trim().charAt(0).toUpperCase() || '?';
  }
}
