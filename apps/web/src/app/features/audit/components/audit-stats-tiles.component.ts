import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe } from '@core/services/i18n.service';
import { AuditStats } from '../audit.models';
import { UiKpiCardComponent } from '@shared/ui/ui-kpi-card.component';

@Component({
  selector: 'app-audit-stats-tiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [UiKpiCardComponent, TranslatePipe, SMTButtonComponent],
  template: `
    <!-- Stats Cards -->
    @if (stats(); as s) {
      <div class="tiles">
        <ui-kpi-card
          [label]="'audit.stats.total_audit_records' | t"
          [value]="s.totalAuditLogs"
          icon="history"
          tone="primary"
        >
          {{ 'audit.stats.immutable_log' | t }}
        </ui-kpi-card>
        <ui-kpi-card
          [label]="'audit.stats.security_events' | t"
          [value]="s.totalSecurityEvents"
          icon="security"
          tone="info"
        >
          {{ 'audit.stats.all_event_types' | t }}
        </ui-kpi-card>
        <ui-kpi-card
          [label]="'audit.stats.events_last_24_hours' | t"
          [value]="s.securityEventsLast24h"
          icon="schedule"
          tone="warning"
        >
          {{ 'audit.stats.daily_activity' | t }}
        </ui-kpi-card>
        <ui-kpi-card
          [label]="'audit.stats.failed_logins_and_locks' | t"
          [value]="s.failedLoginsLast24h"
          [icon]="s.failedLoginsLast24h > 0 ? 'gpp_bad' : 'verified_user'"
          [tone]="s.failedLoginsLast24h > 0 ? 'danger' : 'success'"
          [alert]="s.failedLoginsLast24h > 0"
        >
          {{ (s.failedLoginsLast24h > 0 ? 'audit.stats.needs_attention' : 'audit.stats.no_anomalies_found') | t }}
        </ui-kpi-card>
      </div>
    }

    @if (statsError()) {
      <div id="audit-stats-error" class="inline-feedback" role="alert">
        <span class="material-symbols-outlined" aria-hidden="true">error</span>
        <span>{{ 'audit.load_stats_error' | t }}</span>
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          smtIcon="refresh"
          (click)="retryStats.emit()"
        >
          {{ 'audit.retry' | t }}
        </button>
      </div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .tiles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 14px;
      }
      .inline-feedback {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 10px 14px;
        border-radius: 8px;
        font-size: 13px;
        background: var(--danger-bg);
        color: var(--danger-text);
        border: 1px solid var(--danger);
        margin-top: 12px;
      }
    `,
  ],
})
export class AuditStatsTilesComponent {
  readonly stats = input<AuditStats | null>(null);
  readonly statsError = input(false);

  readonly retryStats = output<void>();
}
