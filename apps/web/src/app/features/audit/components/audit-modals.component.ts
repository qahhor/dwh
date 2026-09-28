import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  TemplateRef,
  viewChild,
  input,
  output,
} from '@angular/core';
import { NgClass, JsonPipe, DatePipe } from '@angular/common';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { AuditRecord, SecurityEventRecord } from '../audit.models';

/** One changed field: its name and its value before and after, as shown. */
interface DiffRow {
  field: string;
  before: string;
  after: string;
}

@Component({
  selector: 'app-audit-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiLocalTableComponent,
    DatePipe,
    JsonPipe,
    NgClass,
  ],
  template: `
    <!-- Cells of the diff table; outside the dialog, so they exist before it opens. -->
    <ng-template #diffFieldCell let-row
      ><span class="font-mono field-name">{{ row.field }}</span></ng-template
    >
    <ng-template #diffBeforeCell let-row>
      <pre class="diff-val diff-val--before">{{ row.before }}</pre>
    </ng-template>
    <ng-template #diffAfterCell let-row>
      <pre class="diff-val diff-val--after">{{ row.after }}</pre>
    </ng-template>

    <!-- MODAL: AUDIT DIFF VIEWER -->
    <smt-dialog
      [open]="selectedAudit() !== null"
      [smtTitle]="'audit.detali_izmeneniya_zapisi_visual_diff' | t"
      smtSize="lg"
      (closed)="closeAuditModal.emit()"
    >
      <ng-template smtDialogContent>
        @if (selectedAudit(); as audit) {
          <div body class="diff-modal-body">
            <div class="diff-meta-grid">
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.tablica.6f39b76' | t }}</span>
                <span class="meta-val font-mono">{{ audit.tableName }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">PK:</span>
                <span class="meta-val font-mono">{{ audit.rowPk }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.deystvie.7b79e9f' | t }}</span>
                <span class="event-badge" [ngClass]="getEventBadgeClass(audit.event)">{{
                  getEventName(audit.event)
                }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.avtor' | t }}</span>
                <span class="meta-val">{{
                  audit.changedByName ? audit.changedByName + ' (@' + audit.changedByLogin + ')' : ('common.system' | t)
                }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.data' | t }}</span>
                <span class="meta-val">{{ audit.changedAt | date: 'dd.MM.yyyy HH:mm:ss' }}</span>
              </div>
            </div>

            <!-- Diff Table -->
            <div class="diff-section-title">{{ 'audit.sravnenie_poley_diff' | t }}</div>
            @if (getDiffKeys(audit).length > 0) {
              <div
                class="diff-table-box"
                role="region"
                [attr.aria-label]="'audit.sravnenie_izmenennyh_poley' | t"
                tabindex="0"
              >
                <ui-local-table [rows]="diffRows(audit)" [config]="diffConfig()" />
              </div>
            } @else {
              <div class="no-diff-msg">{{ 'audit.net_podrobnyh_dannyh_diff_dlya_etoy_operacii' | t }}</div>
            }
          </div>
        }
        <div footer class="modal-footer-actions">
          <button smt-button type="button" smtVariant="secondary" (click)="closeAuditModal.emit()">
            {{ 'audit.zakryt' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>

    <!-- MODAL: SECURITY EVENT DETAILS -->
    <smt-dialog
      [open]="selectedSecEvent() !== null"
      [smtTitle]="'audit.sobytie_bezopasnosti' | t"
      smtSize="md"
      (closed)="closeSecModal.emit()"
    >
      <ng-template smtDialogContent>
        @if (selectedSecEvent(); as ev) {
          <div body class="sec-modal-body">
            <div class="diff-meta-grid">
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.tip_sobytiya' | t }}</span>
                <span class="sec-event-badge" [ngClass]="getSecurityEventBadgeClass(ev.eventType)">
                  {{ ev.eventType }}
                </span>
              </div>
              <div class="meta-item">
                <span class="meta-label">IP:</span>
                <span class="meta-val font-mono">{{ ev.ip }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.polzovatel.a7d134d' | t }}</span>
                <span class="meta-val">{{
                  ev.userName ? ev.userName + ' (@' + ev.userLogin + ')' : ev.details['login'] || '—'
                }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">{{ 'audit.data' | t }}</span>
                <span class="meta-val">{{ ev.createdAt | date: 'dd.MM.yyyy HH:mm:ss' }}</span>
              </div>
              @if (ev.userAgent) {
                <div class="meta-item full-width">
                  <span class="meta-label">User-Agent:</span>
                  <span class="meta-val text-xs font-mono">{{ ev.userAgent }}</span>
                </div>
              }
            </div>

            <div class="diff-section-title">{{ 'audit.parametry_sobytiya_json' | t }}</div>
            <pre class="json-details-viewer">{{ ev.details | json }}</pre>
          </div>
        }
        <div footer class="modal-footer-actions">
          <button smt-button type="button" smtVariant="secondary" (click)="closeSecModal.emit()">
            {{ 'audit.zakryt' | t }}
          </button>
        </div>
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './audit-modals.component.css',
})
export class AuditModalsComponent {
  private readonly i18n = inject(I18nService);

  readonly selectedAudit = input<AuditRecord | null>(null);
  readonly selectedSecEvent = input<SecurityEventRecord | null>(null);

  readonly closeAuditModal = output<void>();
  readonly closeSecModal = output<void>();

  private readonly fieldCell = viewChild.required<TemplateRef<unknown>>('diffFieldCell');

  private readonly beforeCell = viewChild.required<TemplateRef<unknown>>('diffBeforeCell');

  private readonly afterCell = viewChild.required<TemplateRef<unknown>>('diffAfterCell');

  /** The kit table over the changed fields: field, value before, value after. */
  readonly diffConfig = computed<TableConfig<DiffRow>>(() => {
    this.i18n.currentLang();
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    return {
      trackBy: (_index, row) => row.field,
      ariaLabel: this.i18n.translate('audit.sravnenie_znacheniy_do_i_posle_izmeneniya'),
      layout: 'fit',
      columns: {
        field: { header: header('audit.pole'), content: { type: 'templateRef', value: this.fieldCell }, width: '25%' },
        before: {
          header: header('audit.predyduschee_znachenie'),
          content: { type: 'templateRef', value: this.beforeCell },
        },
        after: { header: header('audit.novoe_znachenie'), content: { type: 'templateRef', value: this.afterCell } },
      },
      columnsOrder: ['field', 'before', 'after'],
    };
  });

  private readonly diffMemo = optionsMemo<DiffRow[]>();

  getEventName(event: string): string {
    switch (event) {
      case 'I':
        return 'INSERT';
      case 'U':
        return 'UPDATE';
      case 'D':
        return 'DELETE';
      default:
        return event;
    }
  }

  getEventBadgeClass(event: string): string {
    switch (event) {
      case 'I':
        return 'insert';
      case 'U':
        return 'update';
      case 'D':
        return 'delete';
      default:
        return '';
    }
  }

  getSecurityEventBadgeClass(type: string): string {
    if (type.includes('SUCCESS')) return 'success';
    if (type.includes('LOCKED') || type.includes('FAILED') || type.includes('RATE_LIMITED')) return 'danger';
    if (type.includes('CHANGED') || type.includes('RESET')) return 'warning';
    return 'info';
  }

  getDiffKeys(record: AuditRecord): string[] {
    const oldKeys = Object.keys(record.oldRow || {});
    const newKeys = Object.keys(record.newRow || {});
    return Array.from(new Set([...oldKeys, ...newKeys, ...(record.changedColumns || [])]));
  }

  diffRows(record: AuditRecord): DiffRow[] {
    return this.diffMemo([record], () =>
      this.getDiffKeys(record).map((field) => ({
        field,
        before: this.formatValue(record.oldRow?.[field]),
        after: this.formatValue(record.newRow?.[field]),
      })),
    );
  }

  formatValue(val: unknown): string {
    if (val === undefined || val === null) return '—';
    if (typeof val === 'object') return JSON.stringify(val, null, 2);
    return String(val);
  }
}
