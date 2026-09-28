import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TrendDataPoint } from '../analytics.models';
import { AnalyticsTrendChartComponent } from './analytics-trend-chart.component';

const day = (date: string, createdCount: number, completedCount: number): TrendDataPoint => ({
  date,
  createdCount,
  completedCount,
});

const WEEK = [day('2026-09-01', 4, 2), day('2026-09-02', 6, 3), day('2026-09-03', 9, 5), day('2026-09-04', 1, 8)];

function render(options: { trends?: TrendDataPoint[]; loading?: boolean; error?: string; range?: string } = {}) {
  const fixture = TestBed.createComponent(AnalyticsTrendChartComponent);
  fixture.componentRef.setInput('trends', options.trends ?? WEEK);
  fixture.componentRef.setInput('loading', options.loading ?? false);
  fixture.componentRef.setInput('error', options.error ?? '');
  fixture.componentRef.setInput('displayedRange', options.range ?? '7d');
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const text = (selector: string) =>
    [...host.querySelectorAll(selector)].map((node) => node.textContent?.replace(/\s+/g, ' ').trim());
  return { fixture, host, text };
}

describe('AnalyticsTrendChartComponent', () => {
  it('is a named region a keyboard can reach, with the period in the subtitle', () => {
    const { host } = render({ range: '30d' });

    const chart = host.querySelector('.svg-chart-container') as HTMLElement;
    expect(chart.getAttribute('role')).toBe('region');
    expect(chart.getAttribute('tabindex')).toBe('0');
    expect(chart.getAttribute('aria-label')).toBe('Динамика потока задач');
    expect(host.querySelector('.card-subtitle')?.textContent).toContain('Созданные и завершённые задачи по дням (30d)');
  });

  it('scales the axis to the largest day and names every point with its date and count', () => {
    const { text } = render();

    expect(text('.y-axis-tick')).toEqual(['9', '6', '3', '0']);
    expect(text('.chart-point-group text')).toEqual(['09-01', '09-02', '09-03', '09-04']);
    expect(text('circle title').slice(0, 2)).toEqual(['2026-09-01: Создано: 4', '2026-09-01: Завершено: 2']);
  });

  it('shows the day under the pointer in a tooltip and hides it when the pointer leaves', () => {
    const { fixture, host, text } = render();

    host.querySelectorAll('.chart-point-group')[2].dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(text('.tooltip-date')).toEqual(['2026-09-03']);
    expect(text('.tooltip-val')).toEqual(['Создано: 9', 'Завершено: 5']);

    host.querySelector('.svg-chart-container')!.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();
    expect(host.querySelector('.chart-tooltip-floating')).toBeNull();
  });

  it('labels only some dates on a long period so they do not overlap', () => {
    const month = Array.from({ length: 30 }, (_, index) =>
      day(`2026-09-${String(index + 1).padStart(2, '0')}`, index, 0),
    );
    const { text } = render({ trends: month, range: '30d' });

    // Every fifth day and the last one.
    expect(text('.chart-point-group text')).toEqual(['09-01', '09-06', '09-11', '09-16', '09-21', '09-26', '09-30']);
  });

  it('says there is no data for the period, but not while loading or after an error', () => {
    const { fixture, host } = render({ trends: [] });
    expect(host.querySelector('.svg-chart-container')).toBeNull();
    expect(host.querySelector('.empty-chart')?.textContent).toContain('Нет данных за выбранный период');

    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    expect(host.querySelector('.empty-chart')).toBeNull();
    expect(host.querySelector('[role="status"]')?.textContent?.trim()).toBeTruthy();
    expect(host.querySelector('.chart-card')?.getAttribute('aria-busy')).toBe('true');

    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('error', 'Ошибка');
    fixture.detectChanges();
    expect(host.querySelector('.empty-chart')).toBeNull();
  });
});
