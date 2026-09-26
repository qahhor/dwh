import { Component, computed, EventEmitter, inject, Input, Output, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UiLocalTableComponent } from '../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group';
import { AuditRecord, SecurityEventRecord } from '../audit.models';

/** One changed field: its name and its value before and after, as shown. */
interface DiffRow {
  field: string;
  before: string;
  after: string;
}

@Component({
  selector: 'app-audit-modals',
  standalone: true,
  imports: [
    CommonModule,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent, SMTDialogContentDirective,
    UiLocalTableComponent
  ],
  template: `
    <!-- Cells of the diff table; outside the dialog, so they exist before it opens. -->
    <ng-template #diffFieldCell let-row><span class="font-mono field-name">{{ row.field }}</span></ng-template>
    <ng-template #diffBeforeCell let-row><pre class="diff-val diff-val--before">{{ row.before }}</pre></ng-template>
    <ng-template #diffAfterCell let-row><pre class="diff-val diff-val--after">{{ row.after }}</pre></ng-template>

    <!-- MODAL: AUDIT DIFF VIEWER -->
    <smt-dialog
      [open]="selectedAudit !== null"
      [smtTitle]="'audit.detali_izmeneniya_zapisi_visual_diff' | t"
      smtSize="lg"
      (closed)="closeAuditModal.emit()">
      <ng-template smtDialogContent>
      <div body *ngIf="selectedAudit as audit" class="diff-modal-body">
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
            <span class="event-badge" [ngClass]="getEventBadgeClass(audit.event)">{{ getEventName(audit.event) }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">{{ 'audit.avtor' | t }}</span>
            <span class="meta-val">{{ audit.changedByName ? audit.changedByName + ' (@' + audit.changedByLogin + ')' : ('common.system' | t) }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">{{ 'audit.data' | t }}</span>
            <span class="meta-val">{{ audit.changedAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
          </div>
        </div>

        <!-- Diff Table -->
        <div class="diff-section-title">{{ 'audit.sravnenie_poley_diff' | t }}</div>
        <div class="diff-table-box" role="region" [attr.aria-label]="'audit.sravnenie_izmenennyh_poley' | t" tabindex="0" *ngIf="getDiffKeys(audit).length > 0; else noDiff">
          <ui-local-table [rows]="diffRows(audit)" [config]="diffConfig()" />
        </div>
        <ng-template #noDiff>
          <div class="no-diff-msg">{{ 'audit.net_podrobnyh_dannyh_diff_dlya_etoy_operacii' | t }}</div>
        </ng-template>
      </div>
      <div footer class="modal-footer-actions">
        <button smt-button type="button" smtVariant="secondary" (click)="closeAuditModal.emit()">{{ 'audit.zakryt' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>

    <!-- MODAL: SECURITY EVENT DETAILS -->
    <smt-dialog
      [open]="selectedSecEvent !== null"
      [smtTitle]="'audit.sobytie_bezopasnosti' | t"
      smtSize="md"
      (closed)="closeSecModal.emit()">
      <ng-template smtDialogContent>
      <div body *ngIf="selectedSecEvent as ev" class="sec-modal-body">
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
            <span class="meta-val">{{ ev.userName ? ev.userName + ' (@' + ev.userLogin + ')' : (ev.details['login'] || '—') }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">{{ 'audit.data' | t }}</span>
            <span class="meta-val">{{ ev.createdAt | date:'dd.MM.yyyy HH:mm:ss' }}</span>
          </div>
          <div class="meta-item full-width" *ngIf="ev.userAgent">
            <span class="meta-label">User-Agent:</span>
            <span class="meta-val text-xs font-mono">{{ ev.userAgent }}</span>
          </div>
        </div>

        <div class="diff-section-title">{{ 'audit.parametry_sobytiya_json' | t }}</div>
        <pre class="json-details-viewer">{{ ev.details | json }}</pre>
      </div>
      <div footer class="modal-footer-actions">
        <button smt-button type="button" smtVariant="secondary" (click)="closeSecModal.emit()">{{ 'audit.zakryt' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>
  `,
  styles: [`
    :host {
      display: contents;
    }

    .diff-modal-body, .sec-modal-body {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .diff-meta-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 12px;
      background: var(--bg-hover);
      padding: 14px;
      border-radius: 8px;
    }

    .meta-item {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .meta-item.full-width {
      grid-column: 1 / -1;
    }

    .meta-label {
      font-size: 11px;
      color: var(--text-light);
      font-weight: 600;
      text-transform: uppercase;
    }

    .meta-val {
      font-size: 13px;
      color: var(--text-main);
      word-break: break-all;
    }

    .font-mono {
      font-family: monospace;
    }

    .text-xs {
      font-size: 11px;
    }

    .event-badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.5px;
      width: fit-content;
    }

    .event-badge.insert {
      background: var(--success-bg);
      color: var(--success-text);
    }

    .event-badge.update {
      background: var(--info-bg);
      color: var(--info-text);
    }

    .event-badge.delete {
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .sec-event-badge {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.3px;
      width: fit-content;
    }

    .sec-event-badge.success {
      background: var(--success-bg);
      color: var(--success-text);
    }

    .sec-event-badge.danger {
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .sec-event-badge.warning {
      background: var(--warning-bg);
      color: var(--warning-text);
    }

    .sec-event-badge.info {
      background: var(--info-bg);
      color: var(--info-text);
    }

    .diff-section-title {
      font-size: 13px;
      font-weight: 700;
      color: var(--text-main);
      margin-top: 4px;
    }

    .diff-table-box {
      border: 1px solid var(--border-color);
      border-radius: 8px;
      overflow: hidden;
      max-height: 400px;
      overflow-y: auto;
    }
    .diff-table-box:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: -2px;
    }

    .field-name {
      color: var(--primary-text);
      font-weight: 500;
    }

    .diff-val--before {
      padding: 4px 6px;
      border-radius: var(--radius-sm);
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .diff-val--after {
      padding: 4px 6px;
      border-radius: var(--radius-sm);
      background: var(--success-bg);
      color: var(--success-text);
    }

    .diff-val {
      margin: 0;
      font-family: inherit;
      white-space: pre-wrap;
      word-break: break-all;
    }

    .no-diff-msg {
      padding: 24px;
      text-align: center;
      color: var(--text-light);
      font-size: 13px;
    }

    .json-details-viewer {
      background: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 12px;
      font-size: 12px;
      color: var(--text-main);
      overflow-x: auto;
      max-height: 250px;
      margin: 0;
    }

    .modal-footer-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
    }
  `]
})
export class AuditModalsComponent {
  private readonly i18n = inject(I18nService);

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
        before: { header: header('audit.predyduschee_znachenie'), content: { type: 'templateRef', value: this.beforeCell } },
        after: { header: header('audit.novoe_znachenie'), content: { type: 'templateRef', value: this.afterCell } },
      },
      columnsOrder: ['field', 'before', 'after'],
    };
  });

  private readonly diffMemo = optionsMemo<DiffRow[]>();

  @Input() selectedAudit: AuditRecord | null = null;
  @Input() selectedSecEvent: SecurityEventRecord | null = null;

  @Output() closeAuditModal = new EventEmitter<void>();
  @Output() closeSecModal = new EventEmitter<void>();

  getEventName(event: string): string {
    switch (event) {
      case 'I': return 'INSERT';
      case 'U': return 'UPDATE';
      case 'D': return 'DELETE';
      default: return event;
    }
  }

  getEventBadgeClass(event: string): string {
    switch (event) {
      case 'I': return 'insert';
      case 'U': return 'update';
      case 'D': return 'delete';
      default: return '';
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
    return this.diffMemo([record], () => this.getDiffKeys(record).map(field => ({
      field,
      before: this.formatValue(record.oldRow?.[field]),
      after: this.formatValue(record.newRow?.[field]),
    })));
  }

  formatValue(val: any): string {
    if (val === undefined || val === null) return '—';
    if (typeof val === 'object') return JSON.stringify(val, null, 2);
    return String(val);
  }
}
