import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { AnalyticsSummary } from '../analytics.models';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';

@Component({
  selector: 'app-analytics-metrics-tiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [UiKpiCardComponent, TranslatePipe],
  template: `
    <div class="tiles">
      <ui-kpi-card
        [label]="'analytics.dashboard.total_tasks' | t"
        [value]="summary()?.totalTasks || 0"
        icon="task_alt"
        tone="primary"
      >
        {{ 'analytics.active_count' | t: { count: summary()?.activeTasks || 0 } }} ·
        {{ 'analytics.completed_count' | t: { count: summary()?.completedTasks || 0 } }}
      </ui-kpi-card>
      <ui-kpi-card
        [label]="'analytics.dashboard.closing_efficiency' | t"
        [value]="(summary()?.completionRatePercent || 0) + '%'"
        icon="trending_up"
        tone="success"
      >
        {{ 'analytics.completed_last_7d' | t: { count: summary()?.completedLast7d || 0 } }} ·
        {{ 'analytics.created_count' | t: { count: summary()?.createdLast7d || 0 } }}
      </ui-kpi-card>
      <ui-kpi-card
        [label]="'analytics.dashboard.overdue_deadlines' | t"
        [value]="summary()?.overdueTasks || 0"
        [icon]="(summary()?.overdueTasks || 0) > 0 ? 'warning' : 'verified'"
        [tone]="(summary()?.overdueTasks || 0) > 0 ? 'danger' : 'success'"
        [alert]="(summary()?.overdueTasks || 0) > 0"
      >
        {{
          ((summary()?.overdueTasks || 0) > 0
            ? 'analytics.dashboard.need_attention'
            : 'analytics.dashboard.all_tasks_on_schedule'
          ) | t
        }}
      </ui-kpi-card>
      <ui-kpi-card
        [label]="'analytics.dashboard.projects_and_resources' | t"
        [value]="summary()?.activeProjectsCount || 0"
        icon="folder_special"
        tone="warning"
      >
        {{ 'analytics.active_users_count' | t: { count: summary()?.activeUsersCount || 0 } }}
      </ui-kpi-card>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .tiles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 14px;
        margin-bottom: 20px;
      }
    `,
  ],
})
export class AnalyticsMetricsTilesComponent {
  readonly summary = input<AnalyticsSummary | null>(null);
}
