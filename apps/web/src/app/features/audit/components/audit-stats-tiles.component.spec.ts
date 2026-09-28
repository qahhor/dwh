import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AuditStats } from '../audit.models';
import { AuditStatsTilesComponent } from './audit-stats-tiles.component';

const STATS: AuditStats = {
  totalAuditLogs: 41,
  totalSecurityEvents: 17,
  securityEventsLast24h: 5,
  failedLoginsLast24h: 3,
};

function render(options: { stats?: AuditStats | null; error?: boolean } = {}) {
  const fixture = TestBed.createComponent(AuditStatsTilesComponent);
  fixture.componentRef.setInput('stats', options.stats === undefined ? STATS : options.stats);
  fixture.componentRef.setInput('statsError', options.error ?? false);
  const retried = vi.fn();
  fixture.componentInstance.retryStats.subscribe(retried);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tiles = () => [...host.querySelectorAll('.kpi')] as HTMLElement[];
  const value = (tile: HTMLElement) => tile.querySelector('.kpi__value')?.textContent?.trim();
  const note = (tile: HTMLElement) => tile.querySelector('.kpi__meta')?.textContent?.trim();
  return { fixture, host, tiles, value, note, retried };
}

describe('AuditStatsTilesComponent', () => {
  it('shows the four audit figures, each tile named for screen readers', () => {
    const { tiles, value } = render();

    expect(tiles().map(value)).toEqual(['41', '17', '5', '3']);
    expect(tiles()[0].getAttribute('aria-label')).toMatch(/^Всего записей аудита: 41/);
    expect(tiles()[3].getAttribute('aria-label')).toMatch(/^Неудачных входов \/ блокировок: 3/);
  });

  it('raises the alert on failed sign-ins in the last day', () => {
    const { tiles, note } = render();

    expect(tiles()[3].classList).toContain('kpi--alert');
    expect(tiles()[3].querySelector('.kpi__icon')?.textContent?.trim()).toBe('gpp_bad');
    expect(note(tiles()[3])).toBe('Требует внимания');
  });

  it('says no anomaly was found when nobody failed to sign in', () => {
    const { tiles, note } = render({ stats: { ...STATS, failedLoginsLast24h: 0 } });

    expect(tiles()[3].classList).not.toContain('kpi--alert');
    expect(tiles()[3].querySelector('.kpi__icon')?.textContent?.trim()).toBe('verified_user');
    expect(note(tiles()[3])).toBe('Аномалий не обнаружено');
  });

  it('shows no tiles before the figures arrive', () => {
    const { tiles, host } = render({ stats: null });

    expect(tiles()).toHaveLength(0);
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('reports a failed load as an alert and asks to retry from its button', () => {
    const { host, retried } = render({ stats: null, error: true });

    const alert = host.querySelector('#audit-stats-error') as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('Не удалось загрузить сводку аудита.');
    (alert.querySelector('button') as HTMLButtonElement).click();

    expect(retried).toHaveBeenCalledTimes(1);
  });
});
