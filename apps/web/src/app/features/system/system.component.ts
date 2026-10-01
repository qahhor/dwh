import { ChangeDetectionStrategy, Component, OnInit, signal, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { SystemApi, SystemInfo } from './system.api';

export type { SystemInfo };
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

type OverallStatus = 'healthy' | 'attention' | 'unavailable';

@Component({
  selector: 'app-system',
  imports: [UiPageHeaderComponent, TranslatePipe, SMTButtonComponent, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './system.component.html',
  styleUrl: './system.component.css',
})
export class SystemComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);

  private readonly system = inject(SystemApi);

  readonly systemInfo = signal<SystemInfo | null>(null);
  readonly isLoading = signal(true);
  readonly loadError = signal(false);
  readonly refreshAnnouncement = signal('');

  ngOnInit(): void {
    this.loadSystemInfo();
  }

  loadSystemInfo(): void {
    const hadSnapshot = this.systemInfo() !== null;
    this.isLoading.set(true);
    this.loadError.set(false);
    this.refreshAnnouncement.set('');
    this.system.info().subscribe({
      next: (info) => {
        this.systemInfo.set(info);
        this.isLoading.set(false);
        if (hadSnapshot) {
          this.refreshAnnouncement.set(this.uiI18n.translate('system.refresh_succeeded'));
        }
      },
      error: () => {
        this.loadError.set(true);
        this.isLoading.set(false);
      },
    });
  }

  componentEntries(info: SystemInfo): Array<{ name: string; status: string }> {
    const preferredOrder = ['database', 'storage', 'typesense'];
    return Object.entries(info.components)
      .map(([name, component]) => ({ name, status: component.status || 'UNKNOWN' }))
      .sort((left, right) => {
        const leftIndex = preferredOrder.indexOf(left.name);
        const rightIndex = preferredOrder.indexOf(right.name);
        return (
          (leftIndex < 0 ? preferredOrder.length : leftIndex) - (rightIndex < 0 ? preferredOrder.length : rightIndex) ||
          left.name.localeCompare(right.name)
        );
      });
  }

  overallStatus(info: SystemInfo): OverallStatus {
    const statuses = Object.fromEntries(
      Object.entries(info.components).map(([name, component]) => [name, (component.status || 'UNKNOWN').toUpperCase()]),
    );
    if (['database', 'storage'].some((name) => statuses[name] === 'DOWN')) {
      return 'unavailable';
    }
    const missingRequiredComponent = ['database', 'storage'].some((name) => !statuses[name]);
    const componentNeedsAttention = Object.values(statuses).some((status) => !['UP', 'DISABLED'].includes(status));
    const backupIsCurrent = info.backup.status === 'SUCCESS' && info.backup.freshness === 'CURRENT';
    if (missingRequiredComponent || componentNeedsAttention || !backupIsCurrent) {
      return 'attention';
    }
    return 'healthy';
  }

  overallStatusLabel(info: SystemInfo): string {
    return (
      {
        healthy: this.uiI18n.translate('system.overall_healthy'),
        attention: this.uiI18n.translate('system.overall_attention'),
        unavailable: this.uiI18n.translate('system.overall_unavailable'),
      } as Record<OverallStatus, string>
    )[this.overallStatus(info)];
  }

  overallStatusDescription(info: SystemInfo): string {
    return (
      {
        healthy: this.uiI18n.translate('system.overall_healthy_description'),
        attention: this.uiI18n.translate('system.overall_attention_description'),
        unavailable: this.uiI18n.translate('system.overall_unavailable_description'),
      } as Record<OverallStatus, string>
    )[this.overallStatus(info)];
  }

  overallStatusIcon(info: SystemInfo): string {
    return ({ healthy: 'check_circle', attention: 'warning', unavailable: 'error' } as Record<OverallStatus, string>)[
      this.overallStatus(info)
    ];
  }

  componentLabel(name: string): string {
    return (
      (
        {
          database: 'PostgreSQL',
          storage: this.uiI18n.translate('files.common.file_storage'),
          typesense: 'Typesense',
        } as Record<string, string>
      )[name] ?? name
    );
  }

  storageProviderLabel(provider: string): string {
    return (
      (
        {
          local_disk: this.uiI18n.translate('system.storage_local_disk'),
          s3: this.uiI18n.translate('system.storage_s3'),
        } as Record<string, string>
      )[provider] ?? provider
    );
  }

  statusLabel(status: string): string {
    return (
      (
        {
          UP: this.uiI18n.translate('system.health.state_up'),
          DEGRADED: this.uiI18n.translate('system.health.state_degraded'),
          DOWN: this.uiI18n.translate('system.health.state_down'),
          DISABLED: this.uiI18n.translate('system.health.state_disabled'),
          UNKNOWN: this.uiI18n.translate('system.health.state_unknown'),
        } as Record<string, string>
      )[status] ?? status
    );
  }

  statusClass(status: string): string {
    return ['UP', 'DEGRADED', 'DOWN', 'DISABLED'].includes(status) ? status.toLowerCase() : 'unknown';
  }

  backupStatusLabel(backup: SystemInfo['backup']): string {
    if (backup.status === 'SUCCESS') {
      return (
        (
          {
            CURRENT: this.uiI18n.translate('system.backup_current'),
            STALE: this.uiI18n.translate('system.backup_stale'),
            NOT_CONFIGURED: this.uiI18n.translate('system.backup_policy_not_configured'),
            UNKNOWN: this.uiI18n.translate('system.backup_freshness_unknown'),
          } as Record<string, string>
        )[backup.freshness] ?? this.uiI18n.translate('system.backup_freshness_unknown')
      );
    }
    return (
      (
        {
          NEVER: this.uiI18n.translate('system.health.backup_never'),
          FAILED: this.uiI18n.translate('system.health.backup_failed'),
        } as Record<string, string>
      )[backup.status] ?? this.uiI18n.translate('system.health.backup_unknown')
    );
  }

  backupIcon(backup: SystemInfo['backup']): string {
    if (backup.status === 'SUCCESS') {
      return (
        ({ CURRENT: 'check_circle', STALE: 'error', NOT_CONFIGURED: 'warning' } as Record<string, string>)[
          backup.freshness
        ] ?? 'help'
      );
    }
    return ({ NEVER: 'schedule', FAILED: 'error' } as Record<string, string>)[backup.status] ?? 'help';
  }

  backupSeverity(backup: SystemInfo['backup']): 'healthy' | 'attention' | 'critical' {
    if (backup.status === 'FAILED' || (backup.status === 'SUCCESS' && backup.freshness === 'STALE')) return 'critical';
    if (backup.status === 'SUCCESS' && backup.freshness === 'CURRENT') return 'healthy';
    return 'attention';
  }

  formatDuration(seconds: number | null | undefined): string {
    if (seconds == null || !Number.isFinite(seconds) || seconds < 0) {
      return '';
    }
    if (seconds < 60) {
      return this.uiI18n.translate('system.duration_less_than_minute');
    }
    const totalMinutes = Math.floor(seconds / 60);
    const days = Math.floor(totalMinutes / (24 * 60));
    const hours = Math.floor((totalMinutes % (24 * 60)) / 60);
    const minutes = totalMinutes % 60;
    const parts: string[] = [];
    if (days > 0) parts.push(this.uiI18n.translate('system.duration_days_short', { count: days }));
    if (hours > 0) parts.push(this.uiI18n.translate('system.duration_hours_short', { count: hours }));
    if (minutes > 0 && days === 0)
      parts.push(this.uiI18n.translate('system.duration_minutes_short', { count: minutes }));
    return parts.join(' ');
  }
}
