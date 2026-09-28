import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { AnalyticsSummary } from '../analytics.models';
import { AnalyticsMetricsTilesComponent } from './analytics-metrics-tiles.component';

const SUMMARY: AnalyticsSummary = {
  totalTasks: 12,
  activeTasks: 7,
  completedTasks: 5,
  overdueTasks: 2,
  completionRatePercent: 42,
  createdLast7d: 4,
  completedLast7d: 3,
  activeProjectsCount: 6,
  activeUsersCount: 9,
};

function render(summary: AnalyticsSummary | null) {
  const fixture = TestBed.createComponent(AnalyticsMetricsTilesComponent);
  fixture.componentRef.setInput('summary', summary);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tiles = () => [...host.querySelectorAll('.kpi')] as HTMLElement[];
  const value = (index: number) => tiles()[index].querySelector('.kpi__value')?.textContent?.trim();
  const note = (index: number) => tiles()[index].querySelector('.kpi__meta')?.textContent?.replace(/\s+/g, ' ').trim();
  return { fixture, tiles, value, note };
}

describe('AnalyticsMetricsTilesComponent', () => {
  it('shows the four figures of the summary, each tile named for screen readers', () => {
    const { tiles, value } = render(SUMMARY);

    expect(tiles()).toHaveLength(4);
    expect([value(0), value(1), value(2), value(3)]).toEqual(['12', '42%', '2', '6']);
    expect(tiles()[0].getAttribute('aria-label')).toMatch(/^Всего задач: 12/);
    expect(tiles()[2].getAttribute('aria-label')).toMatch(/^Просрочено дедлайнов: 2/);
  });

  it('explains each figure with its parts: active and done, the last seven days, the people', () => {
    const { note } = render(SUMMARY);

    expect(note(0)).toBe('7 активных · 5 завершено');
    expect(note(1)).toBe('+3 за 7 дней · 4 создано');
    expect(note(3)).toBe('9 активных пользователей');
  });

  it('raises the alert on overdue deadlines and asks for attention', () => {
    const { tiles, note } = render(SUMMARY);

    expect(tiles()[2].classList).toContain('kpi--alert');
    expect(tiles()[2].querySelector('.kpi__icon')?.textContent?.trim()).toBe('warning');
    expect(note(2)).toBe('Требуют внимания');
  });

  it('says every task is on schedule when nothing is overdue', () => {
    const { tiles, note } = render({ ...SUMMARY, overdueTasks: 0 });

    expect(tiles()[2].classList).not.toContain('kpi--alert');
    expect(tiles()[2].querySelector('.kpi__icon')?.textContent?.trim()).toBe('verified');
    expect(note(2)).toBe('Все задачи в графике');
  });

  it('shows zeros while the summary has not arrived', () => {
    const { value, note } = render(null);

    expect([value(0), value(1), value(2), value(3)]).toEqual(['0', '0%', '0', '0']);
    expect(note(0)).toBe('0 активных · 0 завершено');
  });
});
