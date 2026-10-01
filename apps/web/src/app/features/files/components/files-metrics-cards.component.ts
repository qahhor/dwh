import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { StorageStats } from '../files.models';
import { TranslatePipe } from '@core/services/i18n.service';

@Component({
  selector: 'app-files-metrics-cards',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  template: `
    @if (stats(); as s) {
      <div class="storage-metrics-grid">
        <!-- Company Quota Card -->
        <div class="metric-card company-card">
          <div class="metric-header">
            <div class="metric-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">corporate_fare</span>
              <span class="card-title">{{ 'files.list.company_disk_space' | t }}</span>
            </div>
            <span
              class="percent-badge"
              [class.danger]="getCompanyPercent(s) >= 90"
              [class.warning]="getCompanyPercent(s) >= 75 && getCompanyPercent(s) < 90"
            >
              {{ getCompanyPercent(s) }}%
            </span>
          </div>

          <div class="metric-body">
            <div class="metric-values">
              <span class="used-val">{{ formatBytes(s.companyUsedBytes) }}</span>
              <span class="sep-val">{{ 'files.common.of' | t }}</span>
              <span class="quota-val">{{ formatBytes(s.companyQuotaBytes) }}</span>
            </div>

            <div
              class="progress-bar-track"
              role="progressbar"
              [attr.aria-label]="'files.list.company_storage_usage' | t"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="getCompanyPercent(s)"
            >
              <div
                class="progress-bar-fill"
                [style.width.%]="getCompanyPercent(s)"
                [class.danger-fill]="getCompanyPercent(s) >= 90"
                [class.warning-fill]="getCompanyPercent(s) >= 75 && getCompanyPercent(s) < 90"
              ></div>
            </div>

            <div class="metric-footer">
              <span>{{ 'files.available_space' | t: { size: formatBytes(s.companyAvailableBytes) } }}</span>
              <span>{{ 'files.total_files_count' | t: { count: s.totalFilesCount } }}</span>
            </div>
          </div>
        </div>

        <!-- User Personal Quota Card -->
        <div class="metric-card user-card">
          <div class="metric-header">
            <div class="metric-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">person</span>
              <span class="card-title">{{ 'files.list.my_personal_quota' | t }}</span>
            </div>
            <span
              class="percent-badge user-badge"
              [class.danger]="getUserPercent(s) >= 90"
              [class.warning]="getUserPercent(s) >= 75 && getUserPercent(s) < 90"
            >
              {{ getUserPercent(s) }}%
            </span>
          </div>

          <div class="metric-body">
            <div class="metric-values">
              <span class="used-val">{{ formatBytes(s.userUsedBytes) }}</span>
              <span class="sep-val">{{ 'files.common.of' | t }}</span>
              <span class="quota-val">{{ formatBytes(s.userQuotaBytes) }}</span>
            </div>

            <div
              class="progress-bar-track"
              role="progressbar"
              [attr.aria-label]="'files.list.personal_quota_usage' | t"
              aria-valuemin="0"
              aria-valuemax="100"
              [attr.aria-valuenow]="getUserPercent(s)"
            >
              <div
                class="progress-bar-fill user-fill"
                [style.width.%]="getUserPercent(s)"
                [class.danger-fill]="getUserPercent(s) >= 90"
                [class.warning-fill]="getUserPercent(s) >= 75 && getUserPercent(s) < 90"
              ></div>
            </div>

            <div class="metric-footer">
              <span>{{ 'files.available_space' | t: { size: formatBytes(s.userAvailableBytes) } }}</span>
              <span>{{ 'files.my_files_count' | t: { count: s.userFilesCount } }}</span>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styleUrl: './files-metrics-cards.component.css',
})
export class FilesMetricsCardsComponent {
  readonly stats = input<StorageStats | null>(null);

  getCompanyPercent(s: StorageStats): number {
    if (!s.companyQuotaBytes || s.companyQuotaBytes === 0) return 0;
    return Math.min(100, Math.round((s.companyUsedBytes / s.companyQuotaBytes) * 100));
  }

  getUserPercent(s: StorageStats): number {
    if (!s.userQuotaBytes || s.userQuotaBytes === 0) return 0;
    return Math.min(100, Math.round((s.userUsedBytes / s.userQuotaBytes) * 100));
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
