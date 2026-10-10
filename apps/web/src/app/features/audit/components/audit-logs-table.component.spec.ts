import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { AUDIT_LOGS_META } from '@testing/registry-meta';
import { AuditRecord } from '../audit.models';
import { AuditLogsTableComponent } from './audit-logs-table.component';

const record = (overrides: Partial<AuditRecord>): AuditRecord => ({
  id: 11,
  tableName: 'ms_tasks',
  rowPk: '42',
  event: 'U',
  isApi: false,
  changedAt: '2026-08-30T00:00:00Z',
  changedColumns: ['title'],
  ...overrides,
});

const RECORDS = [
  record({ id: 11, event: 'I', changedByName: 'Анна', changedByLogin: 'anna' }),
  record({ id: 12, event: 'D', isApi: true }),
];

function render(options: { records?: AuditRecord[]; from?: string; to?: string } = {}) {
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: { get: vi.fn(() => of([])), post: vi.fn() } },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(AuditLogsTableComponent);
  const pager = new KeysetPager<AuditRecord>(() => of({ items: options.records ?? RECORDS, nextCursor: null }));
  pager.first();
  fixture.componentRef.setInput('pager', pager);
  fixture.componentRef.setInput('meta', AUDIT_LOGS_META);
  fixture.componentRef.setInput('auditFromFilter', options.from ?? '');
  fixture.componentRef.setInput('auditToFilter', options.to ?? '');
  const component = fixture.componentInstance;
  const asked: string[] = [];
  component.applyFilters.subscribe(() => asked.push('apply'));
  component.resetFilters.subscribe(() => asked.push('reset'));
  component.tableFilterChange.subscribe((value) => asked.push(`table:${value}`));
  component.rowPkFilterChange.subscribe((value) => asked.push(`row:${value}`));
  component.auditFromFilterChange.subscribe((value) => asked.push(`from:${value}`));
  component.auditToFilterChange.subscribe((value) => asked.push(`to:${value}`));
  const selected = vi.fn();
  component.selectRecord.subscribe(selected);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => [...host.querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];
  return { fixture, host, rows, asked, selected };
}

describe('AuditLogsTableComponent', () => {
  it('shows each change as a row in a named table, with its action, author and channel', () => {
    const { host, rows } = render();

    expect(host.querySelector('.table-container')?.getAttribute('role')).toBe('region');
    expect(host.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Журнал изменений данных');
    expect(rows()).toHaveLength(2);
    expect(rows().map((row) => row.querySelector('.event-badge')?.textContent?.trim())).toEqual(['INSERT', 'DELETE']);
    expect(rows()[0].querySelector('.user-cell')?.textContent).toContain('@anna');
    expect(rows()[1].textContent).toContain('Система');
    expect(rows().map((row) => row.querySelector('.channel-pill')?.textContent?.trim())).toEqual([
      'web Web UI',
      'terminal REST API',
    ]);
  });

  it('opens a change from its own named button', () => {
    const { host, selected } = render();

    (host.querySelector('button[aria-label="Просмотреть изменение #12"]') as HTMLButtonElement).click();

    expect(selected).toHaveBeenCalledWith(RECORDS[1]);
  });

  it('applies the table picked in the filter at once', () => {
    const { fixture, host, asked } = render();

    (host.querySelector('#audit-table-filter') as HTMLButtonElement).click();
    fixture.detectChanges();
    const option = ([...document.querySelectorAll('.smt-select__option')] as HTMLElement[]).find((item) =>
      item.textContent?.includes('ms_projects'),
    );
    option!.click();
    fixture.detectChanges();

    expect(asked).toEqual(['table:ms_projects', 'apply']);
  });

  it('passes the typed record key on and applies it on Enter', () => {
    const { fixture, host, asked } = render();
    const field = host.querySelector('#audit-row-pk-filter') as HTMLInputElement;

    field.value = '42';
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();

    expect(asked).toEqual(['row:42', 'apply']);
  });

  it('clears both period bounds and refetches at once', () => {
    const { host, asked } = render({ from: '2026-09-01', to: '2026-09-07' });

    (host.querySelector('[data-testid="audit-period-filter"] .smt-date-picker__toggle') as HTMLButtonElement).click();

    expect(asked).toEqual(['from:', 'to:', 'apply']);
  });

  it('applies and resets the filters from its buttons', () => {
    const { host, asked } = render();

    (host.querySelector('#audit-apply-filters') as HTMLButtonElement).click();
    (host.querySelector('#audit-reset-filters') as HTMLButtonElement).click();

    expect(asked).toEqual(['apply', 'reset']);
  });

  it('names every filter, the UTC period picker and the reset action', () => {
    const { host } = render();

    // Each filter has a name: a visible smt-control label or, where the toolbar shows none, its own aria-label.
    for (const id of ['audit-row-pk-filter', 'audit-user-filter']) {
      expect(host.querySelector(`smt-control:has(#${id}) .smt-control__label`)).not.toBeNull();
    }
    expect(host.querySelector('#audit-table-filter')?.getAttribute('aria-label')).toBeTruthy();
    expect(host.querySelector('#audit-event-filter')?.getAttribute('aria-label')).toBeTruthy();
    expect(host.querySelector('#audit-table-filter')?.getAttribute('role')).toBe('combobox');
    expect(host.querySelector('#audit-event-filter')?.getAttribute('role')).toBe('combobox');
    expect(
      host
        .querySelector('[data-testid="audit-period-filter"] .smt-date-range-picker__trigger')
        ?.getAttribute('aria-label'),
    ).toMatch(/^Период \(UTC\): /);
    expect(host.querySelector('#audit-reset-filters')).not.toBeNull();
  });

  it('says no audit record was found when the page is empty', () => {
    const { host, rows } = render({ records: [] });

    expect(rows()).toHaveLength(0);
    expect(host.querySelector('.empty-state-box h3')?.textContent).toBe('Записей аудита не найдено');
  });
});
