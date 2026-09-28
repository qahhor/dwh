import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';

@Component({
  selector: 'app-modules-stats',
  imports: [UiKpiCardComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stats-grid">
      <ui-kpi-card [label]="'modules.stat.total' | t" [value]="totalCount()" icon="extension" tone="primary" />
      <ui-kpi-card [label]="'modules.stat.active' | t" [value]="activeCount()" icon="check_circle" tone="success" />
      <ui-kpi-card [label]="'modules.stat.system' | t" [value]="systemCount()" icon="verified_user" tone="info" />
      <ui-kpi-card [label]="'modules.stat.custom' | t" [value]="customCount()" icon="widgets" />
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .stats-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: 14px;
      }
    `,
  ],
})
export class ModulesStatsComponent {
  readonly totalCount = input.required<number>();
  readonly activeCount = input.required<number>();
  readonly systemCount = input.required<number>();
  readonly customCount = input.required<number>();
}
