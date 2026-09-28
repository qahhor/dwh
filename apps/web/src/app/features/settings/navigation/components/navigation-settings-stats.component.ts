import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';

@Component({
  selector: 'app-navigation-settings-stats',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [UiKpiCardComponent, TranslatePipe],
  template: `
    <div class="stats-grid">
      <ui-kpi-card [label]="'nav.settings.stat_total' | t" [value]="totalCount()" icon="menu" tone="primary" />
      <ui-kpi-card
        [label]="'nav.settings.stat_active' | t"
        [value]="activeCount()"
        icon="check_circle"
        tone="success"
      />
      <ui-kpi-card [label]="'nav.settings.stat_embedded' | t" [value]="embeddedCount()" icon="web" tone="info" />
      <ui-kpi-card [label]="'nav.settings.stat_external' | t" [value]="externalCount()" icon="open_in_new" />
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
export class NavigationSettingsStatsComponent {
  readonly totalCount = input(0);
  readonly activeCount = input(0);
  readonly embeddedCount = input(0);
  readonly externalCount = input(0);
}
