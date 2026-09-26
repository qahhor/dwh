import { Component, EventEmitter, Input, Output, Signal, TemplateRef, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { UiBadgeComponent } from '../../../../shared/ui/ui-badge.component';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiLocalTableComponent } from '../../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../../shared/ui-kit/components/table/table.types';
import { UserSession } from '../profile.models';

@Component({
  selector: 'app-profile-sessions-card',
  standalone: true,
  imports: [
    UiLocalTableComponent,
    CommonModule,
    TranslatePipe,
    SMTButtonComponent,
    UiBadgeComponent
  ],
  template: `
    <div class="card section-card full-width">
      <div class="section-header">
        <div class="section-title-box">
          <span class="material-symbols-outlined section-icon" aria-hidden="true">devices</span>
          <h4 class="section-title">{{ 'iam.aktivnye_sessii' | t }}</h4>
          <span class="badge-count">{{ sessions.length }}</span>
        </div>
        <div class="sessions-header-actions">
          <button smt-button type="button"
            *ngIf="sessions.length > 1"
            smtVariant="danger"
            smtSize="sm"
            smtIcon="logout"
            [smtLoading]="isTerminatingSession"
            [title]="'iam.zavershit_vse_ostalnye_sessii_krome_tekuschey' | t"
            (click)="terminateOtherSessions.emit()"
          >
            {{ 'iam.zavershit_drugie_sessii' | t }}
          </button>
          <button smt-button type="button"
            smtVariant="secondary"
            smtSize="sm"
            smtIcon="refresh"
            [smtLoading]="isLoadingSessions"
            (click)="loadSessions.emit()"
          >
            {{ 'common.refresh' | t }}
          </button>
        </div>
      </div>

      <div class="table-wrapper" role="region" [attr.aria-label]="'iam.tablica_aktivnyh_sessiy' | t" tabindex="0">
        <div class="data-table">
          <ui-local-table [rows]="rows()" [config]="config()" [sortValues]="sortValues" [loading]="isLoadingSessions" [emptyTemplate]="emptySessions" />
        </div>
      </div>
    </div>

    <ng-template #ipCell let-s>
      <div class="session-ip-cell tabular-nums font-mono">
        <span>{{ s.ip }}</span>
        @if (s.current) {
          <ui-badge variant="active" [dot]="true" size="sm">{{ 'iam.tekuschaya_sessiya' | t }}</ui-badge>
        }
      </div>
    </ng-template>
    <ng-template #deviceCell let-s>{{ s.deviceInfo || s.userAgent || ('iam.unknown_device' | t) }}</ng-template>
    <ng-template #createdCell let-s><span class="tabular-nums text-muted">{{ s.createdAt | date:'dd.MM.yyyy HH:mm' }}</span></ng-template>
    <ng-template #seenCell let-s><span class="tabular-nums font-medium">{{ s.lastSeenAt | date:'dd.MM.yyyy HH:mm:ss' }}</span></ng-template>
    <ng-template #actionCell let-s>
      <div class="text-right">
        @if (s.current) {
          <span class="current-session-label text-muted">{{ 'iam.tekuschaya' | t }}</span>
        } @else {
          <button smt-button type="button" smtVariant="danger" smtSize="sm" [attr.aria-label]="'iam.terminate_session_ip' | t:{ip: s.ip}" (click)="terminateSession.emit(s)">
            {{ 'iam.zavershit' | t }}
          </button>
        }
      </div>
    </ng-template>
    <ng-template #emptySessions><p class="empty-cell">{{ 'iam.net_aktivnyh_sessiy' | t }}</p></ng-template>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
      grid-column: 1 / -1;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 18px 22px;
    }

    .section-card {
      min-width: 0;
    }

    .full-width {
      width: 100%;
      box-sizing: border-box;
    }

    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 14px;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--border-color);
    }

    .section-title-box {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--text-main);
    }

    .section-icon {
      font-size: 20px;
      color: var(--primary);
    }

    .section-title {
      font-size: 15px;
      font-weight: 600;
      margin: 0;
    }

    .badge-count {
      background-color: var(--bg-hover);
      color: var(--primary);
      font-size: 11px;
      font-weight: 600;
      padding: 1px 6px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
    }

    .sessions-header-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .table-wrapper {
      overflow-x: auto;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
    }
    .table-wrapper:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: -2px;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      text-align: left;
    }

    .data-table th {
      background-color: var(--bg-hover);
      color: var(--text-muted);
      font-weight: 600;
      font-size: 12px;
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      white-space: nowrap;
    }

    .data-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }

    .data-table tr:last-child td {
      border-bottom: none;
    }

    .highlight-row {
      background-color: rgba(99, 102, 241, 0.04);
    }

    .session-ip-cell {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .current-session-label {
      font-size: 12px;
      font-style: italic;
    }

    .tabular-nums {
      font-variant-numeric: tabular-nums;
    }

    .font-mono {
      font-family: monospace;
    }

    .font-medium {
      font-weight: 500;
    }

    .text-muted {
      color: var(--text-muted);
    }

    .text-right {
      text-align: right;
    }

    .empty-cell {
      text-align: center;
      color: var(--text-muted);
      padding: 24px !important;
    }

    .skeleton-row td {
      padding: 12px 14px;
    }

    .skeleton-pill {
      height: 14px;
      background-color: var(--bg-hover);
      border-radius: 4px;
      animation: pulse 1.5s infinite;
    }

    .w-20 { width: 80px; }
    .w-28 { width: 112px; }
    .w-32 { width: 128px; }
    .w-36 { width: 144px; }
    .w-48 { width: 192px; }
    .ml-auto { margin-left: auto; }

    @keyframes pulse {
      0%, 100% { opacity: 0.6; }
      50% { opacity: 0.3; }
    }

    .confirmation-body {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .confirmation-body p {
      margin: 0;
      font-size: 14px;
    }
    .confirmation-hint {
      color: var(--text-muted);
      font-size: 12px;
    }

    @media (max-width: 640px) {
      .card { padding: 14px; }
      .section-header {
        align-items: flex-start;
        flex-direction: column;
        gap: 10px;
      }
      .sessions-header-actions {
        width: 100%;
        flex-wrap: wrap;
      }
    }
  `]
})
export class ProfileSessionsCardComponent {
  private readonly i18n = inject(I18nService);

  private readonly ipCell = viewChild.required<TemplateRef<unknown>>('ipCell');
  private readonly deviceCell = viewChild.required<TemplateRef<unknown>>('deviceCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('createdCell');
  private readonly seenCell = viewChild.required<TemplateRef<unknown>>('seenCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('actionCell');

  readonly rows = signal<UserSession[]>([]);

  readonly config = computed<TableConfig<UserSession>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, s) => s.id ?? s.ip,
      ariaLabel: this.i18n.translate('iam.aktivnye_sessii'),
      layout: 'fit',
      rowClass: s => (s.current ? 'highlight-row' : null),
      columns: {
        ip: { header: header('iam.ip_adres'), content: cell(this.ipCell) },
        device: { header: header('iam.ustroystvo_brauzer'), content: cell(this.deviceCell) },
        created: { header: header('iam.sozdana'), content: cell(this.createdCell), width: '160px' },
        seen: { header: header('iam.poslednyaya_aktivnost'), content: cell(this.seenCell), width: '180px' },
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '140px', align: 'right' }
      },
      columnsOrder: ['ip', 'device', 'created', 'seen', 'action']
    };
  });

  @Input() isLoadingSessions = false;
  @Input() isTerminatingSession = false;

  @Output() loadSessions = new EventEmitter<void>();
  @Output() terminateSession = new EventEmitter<UserSession>();
  @Output() terminateOtherSessions = new EventEmitter<void>();

  readonly sortValues = {
    ip: (s: UserSession) => s.ip,
    device: (s: UserSession) => s.deviceInfo || s.userAgent || '',
    created: (s: UserSession) => new Date(s.createdAt),
    seen: (s: UserSession) => (s.lastSeenAt ? new Date(s.lastSeenAt) : null)
  };

  @Input() set sessions(sessions: UserSession[]) {
    this.rows.set(sessions ?? []);
  }
  get sessions(): UserSession[] {
    return this.rows();
  }
}
