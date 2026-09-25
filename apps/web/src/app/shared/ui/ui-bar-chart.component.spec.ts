import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiBarChartComponent, niceMax } from './ui-bar-chart.component';
import { UiKpiCardComponent } from './ui-kpi-card.component';

describe('ui-bar-chart', () => {
  it('stacks each point\'s series on a round scale and names every bar', async () => {
    await TestBed.configureTestingModule({ imports: [UiBarChartComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiBarChartComponent);
    fixture.componentRef.setInput('series', [
      { key: 'ok', label: 'Готово', color: 'var(--success)' },
      { key: 'bad', label: 'Ошибки', color: 'var(--danger)' }
    ]);
    fixture.componentRef.setInput('points', [
      { label: '01.09', values: { ok: 30, bad: 10 } },
      { label: '02.09', values: { ok: 0, bad: 0 } }
    ]);
    fixture.componentRef.setInput('caption', 'Загрузки по дням');
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    const svg = host.querySelector('svg') as SVGElement;
    expect(svg.getAttribute('role')).toBe('img');
    expect(document.getElementById(svg.getAttribute('aria-labelledby')!)?.textContent).toBe('Загрузки по дням');
    const bars = host.querySelectorAll('[data-testid="bar-chart-bar"]');
    expect(bars[0].querySelector('title')?.textContent).toBe('01.09: Готово 30, Ошибки 10');
    // 40 rounds up to 50: the stack fills 80% of the 180 px height.
    const heights = [...bars[0].querySelectorAll('rect')].map(rect => Number(rect.getAttribute('height')));
    expect(heights[0] + heights[1]).toBeCloseTo(144);
    expect([...bars[1].querySelectorAll('rect')].every(rect => Number(rect.getAttribute('height')) === 0)).toBe(true);
    expect(host.querySelectorAll('[data-testid="bar-chart-table"] tbody tr')).toHaveLength(2);
  });

  it('rounds the scale to 1, 2 or 5 times a power of ten', () => {
    expect(niceMax(0)).toBe(0);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(13)).toBe(20);
    expect(niceMax(420)).toBe(500);
  });
});

describe('ui-kpi-card', () => {
  async function card(value: number, previous: number | null, goodWhen: 'up' | 'down' | 'neutral' = 'up') {
    await TestBed.configureTestingModule({ imports: [UiKpiCardComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiKpiCardComponent);
    fixture.componentRef.setInput('label', 'Отклонено');
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('previous', previous);
    fixture.componentRef.setInput('goodWhen', goodWhen);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('colours a fall as good when fewer is better and reads as one sentence', async () => {
    const host = await card(3, 6, 'down');
    expect(host.querySelector('.kpi')?.getAttribute('aria-label')).toBe('Отклонено: 3, снижение на 50% к прошлому периоду');
    expect(host.querySelector('[data-testid="kpi-change"]')?.classList).toContain('kpi__change--good');
  });

  it('counts instead of a percentage after a period with none, and says when nothing changed', async () => {
    expect((await card(5, 0)).querySelector('.kpi')?.getAttribute('aria-label')).toBe('Отклонено: 5, рост на 5 к прошлому периоду');
    TestBed.resetTestingModule();
    expect((await card(5, 5)).querySelector('.kpi')?.getAttribute('aria-label')).toBe('Отклонено: 5, как в прошлом периоде');
    TestBed.resetTestingModule();
    expect((await card(5, null)).querySelector('[data-testid="kpi-change"]')).toBeNull();
  });
});
