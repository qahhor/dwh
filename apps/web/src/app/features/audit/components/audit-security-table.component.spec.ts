import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { SECURITY_EVENTS_META } from '@testing/registry-meta';
import { SecurityEventRecord } from '../audit.models';
import { AuditSecurityTableComponent } from './audit-security-table.component';

const event = (overrides: Partial<SecurityEventRecord>): SecurityEventRecord => ({
  id: 1,
  eventType: 'LOGIN_SUCCESS',
  ip: '10.0.0.1',
  details: {},
  createdAt: '2026-08-30T00:00:00Z',
  ...overrides,
});

const EVENTS = [
  event({
    id: 1,
    eventType: 'LOGIN_SUCCESS',
    userName: 'Анна',
    userLogin: 'anna',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/140.0 Safari/537.36',
  }),
  event({ id: 2, eventType: 'LOGIN_FAILED', details: { login: 'intruder' }, userAgent: 'PostmanRuntime/7.40' }),
  event({ id: 3, eventType: 'PASSWORD_CHANGED', userAgent: 'curl/8.4.0' }),
  event({ id: 4, eventType: 'API_TOKEN_CREATED' }),
];

function render(options: { events?: SecurityEventRecord[]; from?: string; to?: string } = {}) {
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: { get: vi.fn(() => of([])), post: vi.fn() } },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(AuditSecurityTableComponent);
  const pager = new KeysetPager<SecurityEventRecord>(() => of({ items: options.events ?? EVENTS, nextCursor: null }));
  pager.first();
  fixture.componentRef.setInput('pager', pager);
  fixture.componentRef.setInput('meta', SECURITY_EVENTS_META);
  fixture.componentRef.setInput('securityFromFilter', options.from ?? '');
  fixture.componentRef.setInput('securityToFilter', options.to ?? '');
  const component = fixture.componentInstance;
  const asked: string[] = [];
  component.applyFilters.subscribe(() => asked.push('apply'));
  component.resetFilters.subscribe(() => asked.push('reset'));
  component.secEventTypeFilterChange.subscribe((value) => asked.push(`type:${value}`));
  component.secIpFilterChange.subscribe((value) => asked.push(`ip:${value}`));
  component.securityFromFilterChange.subscribe((value) => asked.push(`from:${value}`));
  component.securityToFilterChange.subscribe((value) => asked.push(`to:${value}`));
  const selected = vi.fn();
  component.selectEvent.subscribe(selected);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => [...host.querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];
  const cell = (row: HTMLElement, key: string) =>
    row.querySelector(`[data-smt-col-key="${key}"]`)?.textContent?.replace(/\s+/g, ' ').trim();
  return { fixture, host, rows, cell, asked, selected };
}

describe('AuditSecurityTableComponent', () => {
  it('shows each event in a named table, with its type marked by severity', () => {
    const { host, rows } = render();

    expect(host.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('События безопасности');
    expect(rows()).toHaveLength(4);
    expect(rows().map((row) => row.querySelector('.sec-event-badge')?.className)).toEqual([
      'sec-event-badge success',
      'sec-event-badge danger',
      'sec-event-badge warning',
      'sec-event-badge info',
    ]);
    expect(rows().map((row) => row.querySelector('.sec-event-badge .material-symbols-outlined')?.textContent)).toEqual([
      'check_circle',
      'error',
      'key',
      'token',
    ]);
  });

  it('names the person, or the login tried, or a guest', () => {
    const { rows, cell } = render();

    expect(rows()[0].querySelector('.user-name')?.textContent).toBe('Анна');
    expect(rows()[0].querySelector('.user-sub')?.textContent).toBe('@anna');
    expect(cell(rows()[1], 'userName')).toBe('intruder');
    expect(cell(rows()[2], 'userName')).toBe('Гость');
  });

  it('shows a readable client name and keeps scripts as they are', () => {
    const { rows, cell } = render();

    expect(rows().map((row) => cell(row, 'userAgent'))).toEqual([
      'Google Chrome',
      'Postman API Client',
      'curl/8.4.0',
      '—',
    ]);
  });

  it('opens an event from its own named button', () => {
    const { host, selected } = render();

    (host.querySelector('button[aria-label="Просмотреть событие безопасности #2"]') as HTMLButtonElement).click();

    expect(selected).toHaveBeenCalledWith(EVENTS[1]);
  });

  it('applies the event type at once, the IP on Enter, and clears the period with a refetch', () => {
    const { fixture, host, asked } = render({ from: '2026-09-01', to: '2026-09-07' });

    (host.querySelector('#security-event-filter') as HTMLButtonElement).click();
    fixture.detectChanges();
    ([...document.querySelectorAll('.smt-select__option')] as HTMLElement[])
      .find((item) => item.textContent?.includes('Ошибка входа (LOGIN_FAILED)'))!
      .click();
    fixture.detectChanges();
    expect(asked).toEqual(['type:LOGIN_FAILED', 'apply']);

    const ip = host.querySelector('#security-ip-search') as HTMLInputElement;
    ip.value = '10.0';
    ip.dispatchEvent(new Event('input'));
    ip.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    expect(asked.slice(2)).toEqual(['ip:10.0', 'apply']);

    (
      host.querySelector('[data-testid="security-period-filter"] .smt-date-picker__toggle') as HTMLButtonElement
    ).click();
    expect(asked.slice(4)).toEqual(['from:', 'to:', 'apply']);
  });

  it('says no security event was found when the page is empty', () => {
    const { host, rows } = render({ events: [] });

    expect(rows()).toHaveLength(0);
    expect(host.querySelector('.empty-state-box h3')?.textContent).toBe('Событий безопасности не найдено');
  });
});
