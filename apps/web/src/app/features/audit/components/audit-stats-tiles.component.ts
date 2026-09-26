import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { AuditStats } from '../audit.models';

@Component({
  selector: 'app-audit-stats-tiles',
  standalone: true,
  imports: [
    CommonModule,
    TranslatePipe,
    SMTButtonComponent
  ],
  template: `
    <!-- Stats Cards -->
    <div class="tiles" *ngIf="stats as s">
      <div class="tile">
        <div class="tile-header">
          <span class="tile-label">{{ 'audit.vsego_zapisey_audita' | t }}</span>
          <span class="material-symbols-outlined" style="color: var(--primary);" aria-hidden="true">history</span>
        </div>
        <div class="tile-value">{{ s.totalAuditLogs }}</div>
        <div class="tile-meta" style="color: var(--text-muted); font-size: 11px;">{{ 'audit.neizmenyaemyy_zhurnal' | t }}</div>
      </div>

      <div class="tile">
        <div class="tile-header">
          <span class="tile-label">{{ 'audit.sobytiy_bezopasnosti' | t }}</span>
          <span class="material-symbols-outlined" style="color: var(--info);" aria-hidden="true">security</span>
        </div>
        <div class="tile-value">{{ s.totalSecurityEvents }}</div>
        <div class="tile-meta" style="color: var(--text-muted); font-size: 11px;">{{ 'audit.vse_tipy_sobytiy' | t }}</div>
      </div>

      <div class="tile">
        <div class="tile-header">
          <span class="tile-label">{{ 'audit.sobytiy_za_24_chasa' | t }}</span>
          <span class="material-symbols-outlined" style="color: var(--warning);" aria-hidden="true">schedule</span>
        </div>
        <div class="tile-value">{{ s.securityEventsLast24h }}</div>
        <div class="tile-meta" style="color: var(--text-muted); font-size: 11px;">{{ 'audit.sutochnaya_aktivnost' | t }}</div>
      </div>

      <div class="tile" [class.tile-alarm]="s.failedLoginsLast24h > 0">
        <div class="tile-header">
          <span class="tile-label">{{ 'audit.neudachnyh_vhodov_blokirovok' | t }}</span>
          <span class="material-symbols-outlined" [style.color]="s.failedLoginsLast24h > 0 ? 'var(--danger)' : 'var(--success)'" aria-hidden="true">
            {{ s.failedLoginsLast24h > 0 ? 'gpp_bad' : 'verified_user' }}
          </span>
        </div>
        <div class="tile-value" [style.color]="s.failedLoginsLast24h > 0 ? 'var(--danger)' : 'var(--text-main)'">
          {{ s.failedLoginsLast24h }}
        </div>
        <div class="tile-meta" [style.color]="s.failedLoginsLast24h > 0 ? 'var(--danger)' : 'var(--success)'" style="font-size: 11px; font-weight: 600;">
          {{ (s.failedLoginsLast24h > 0 ? 'audit.trebuet_vnimaniya' : 'audit.anomaliy_ne_obnaruzheno') | t }}
        </div>
      </div>
    </div>

    <div id="audit-stats-error" class="inline-feedback" role="alert" *ngIf="statsError">
      <span class="material-symbols-outlined" aria-hidden="true">error</span>
      <span>{{ 'audit.load_stats_error' | t }}</span>
      <button smt-button type="button" smtVariant="secondary" smtSize="sm" smtIcon="refresh" (click)="retryStats.emit()">
        {{ 'audit.retry' | t }}
      </button>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .tiles {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 16px;
    }

    @media (max-width: 1024px) {
      .tiles {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    @media (max-width: 640px) {
      .tiles {
        grid-template-columns: 1fr;
      }
    }

    .tile {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 16px 20px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      transition: all 0.2s ease;
      box-shadow: var(--shadow-sm);
    }

    .tile:hover {
      border-color: var(--border-hover);
      box-shadow: var(--shadow-md);
    }

    .tile-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .tile-label {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-light);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .tile-value {
      font-size: 28px;
      font-weight: 800;
      color: var(--text-main);
      line-height: 1;
    }

    .tile-alarm {
      border-color: var(--danger);
      background: linear-gradient(135deg, var(--bg-surface) 0%, var(--danger-bg) 100%);
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
  `]
})
export class AuditStatsTilesComponent {
  @Input() stats: AuditStats | null = null;
  @Input() statsError = false;

  @Output() retryStats = new EventEmitter<void>();
}
