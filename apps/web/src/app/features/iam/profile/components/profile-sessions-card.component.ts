import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  viewChild,
  input,
  output,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UserSession } from '../profile.models';

@Component({
  selector: 'app-profile-sessions-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [UiLocalTableComponent, TranslatePipe, SMTButtonComponent, SMTBadgeComponent, DatePipe],
  template: `
    <div class="card section-card full-width">
      <div class="section-header">
        <div class="section-title-box">
          <span class="material-symbols-outlined section-icon" aria-hidden="true">devices</span>
          <h4 class="section-title">{{ 'iam.aktivnye_sessii' | t }}</h4>
          <span class="badge-count">{{ sessions().length }}</span>
        </div>
        <div class="sessions-header-actions">
          @if (sessions().length > 1) {
            <button
              smt-button
              type="button"
              smtVariant="danger"
              smtSize="sm"
              smtIcon="logout"
              [smtLoading]="isTerminatingSession()"
              [title]="'iam.zavershit_vse_ostalnye_sessii_krome_tekuschey' | t"
              (click)="terminateOtherSessions.emit()"
            >
              {{ 'iam.zavershit_drugie_sessii' | t }}
            </button>
          }
          <button
            smt-button
            type="button"
            smtVariant="secondary"
            smtSize="sm"
            smtIcon="refresh"
            [smtLoading]="isLoadingSessions()"
            (click)="loadSessions.emit()"
          >
            {{ 'common.refresh' | t }}
          </button>
        </div>
      </div>

      <div class="table-wrapper" role="region" [attr.aria-label]="'iam.tablica_aktivnyh_sessiy' | t" tabindex="0">
        <div class="data-table">
          <ui-local-table
            [rows]="rows()"
            [config]="config()"
            [sortValues]="sortValues"
            [loading]="isLoadingSessions()"
            [emptyTemplate]="emptySessions"
          />
        </div>
      </div>
    </div>

    <ng-template #ipCell let-s>
      <div class="session-ip-cell tabular-nums font-mono">
        <span>{{ s.ip }}</span>
        @if (s.current) {
          <smt-badge smtSize="SM" smtVariant="success" smtHasDot>{{ 'iam.tekuschaya_sessiya' | t }}</smt-badge>
        }
      </div>
    </ng-template>
    <ng-template #deviceCell let-s>{{ s.deviceInfo || s.userAgent || ('iam.unknown_device' | t) }}</ng-template>
    <ng-template #createdCell let-s
      ><span class="tabular-nums text-muted">{{ s.createdAt | date: 'dd.MM.yyyy HH:mm' }}</span></ng-template
    >
    <ng-template #seenCell let-s
      ><span class="tabular-nums font-medium">{{ s.lastSeenAt | date: 'dd.MM.yyyy HH:mm:ss' }}</span></ng-template
    >
    <ng-template #actionCell let-s>
      <div class="text-right">
        @if (s.current) {
          <span class="current-session-label text-muted">{{ 'iam.tekuschaya' | t }}</span>
        } @else {
          <button
            smt-button
            type="button"
            smtVariant="danger"
            smtSize="sm"
            [attr.aria-label]="'iam.terminate_session_ip' | t: { ip: s.ip }"
            (click)="terminateSession.emit(s)"
          >
            {{ 'iam.zavershit' | t }}
          </button>
        }
      </div>
    </ng-template>
    <ng-template #emptySessions
      ><p class="empty-cell">{{ 'iam.net_aktivnyh_sessiy' | t }}</p></ng-template
    >
  `,
  styleUrl: './profile-sessions-card.component.css',
})
export class ProfileSessionsCardComponent {
  private readonly i18n = inject(I18nService);

  readonly isLoadingSessions = input(false);
  readonly isTerminatingSession = input(false);

  readonly sessions = input<UserSession[]>([]);

  readonly loadSessions = output<void>();
  readonly terminateSession = output<UserSession>();
  readonly terminateOtherSessions = output<void>();

  private readonly ipCell = viewChild.required<TemplateRef<unknown>>('ipCell');
  private readonly deviceCell = viewChild.required<TemplateRef<unknown>>('deviceCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('createdCell');
  private readonly seenCell = viewChild.required<TemplateRef<unknown>>('seenCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('actionCell');

  readonly rows = computed<UserSession[]>(() => this.sessions() ?? []);

  readonly config = computed<TableConfig<UserSession>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, s) => s.id ?? s.ip,
      ariaLabel: this.i18n.translate('iam.aktivnye_sessii'),
      layout: 'fit',
      rowClass: (s) => (s.current ? 'highlight-row' : null),
      columns: {
        ip: { header: header('iam.ip_adres'), content: cell(this.ipCell) },
        device: { header: header('iam.ustroystvo_brauzer'), content: cell(this.deviceCell) },
        created: { header: header('iam.sozdana'), content: cell(this.createdCell), width: '160px' },
        seen: { header: header('iam.poslednyaya_aktivnost'), content: cell(this.seenCell), width: '180px' },
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '140px', align: 'right' },
      },
      columnsOrder: ['ip', 'device', 'created', 'seen', 'action'],
    };
  });

  readonly sortValues = {
    ip: (s: UserSession) => s.ip,
    device: (s: UserSession) => s.deviceInfo || s.userAgent || '',
    created: (s: UserSession) => new Date(s.createdAt),
    seen: (s: UserSession) => (s.lastSeenAt ? new Date(s.lastSeenAt) : null),
  };
}
