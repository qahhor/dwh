import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiReportPlotComponent, type ReportPlotLabel } from './ui-report-plot.component';

const point = (row: number, value: number | null, connect = false): ReportPlotLabel => ({
  row,
  value,
  connect,
  position: row,
  label: 'Category ' + row,
  text: value === null ? '—' : String(value),
});
function render(points: ReportPlotLabel[], kind: 'line' | 'horizontalBar' = 'line') {
  const fixture = TestBed.createComponent(UiReportPlotComponent);
  fixture.componentRef.setInput('points', points);
  fixture.componentRef.setInput('caption', 'Metric');
  fixture.componentRef.setInput('kind', kind);
  fixture.detectChanges();
  return { fixture, plot: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
}
describe('single-measure report plots', () => {
  it('keeps a zero range finite and displays an isolated point', () => {
    const { host, plot } = render([point(0, 0)]);
    expect(plot.x(0)).toBe(300);
    expect(host.querySelector('circle')?.getAttribute('cy')).toBe('204');
    expect(host.innerHTML).not.toMatch(/NaN|Infinity/);
  });
  it('places negative bars left of zero and positive bars right of it', () => {
    const { host } = render([point(0, -3), point(1, 5)], 'horizontalBar');
    const bars = host.querySelectorAll('rect');
    const zero = Number(host.querySelector('line')?.getAttribute('x1'));
    expect(Number(bars[0].getAttribute('x'))).toBeLessThan(zero);
    expect(Number(bars[1].getAttribute('x'))).toBe(zero);
    expect(Number(bars[0].getAttribute('width'))).toBeCloseTo(88.5);
    expect(Number(bars[1].getAttribute('width'))).toBeCloseTo(147.5);
  });
  it('uses distinct accessible names when multiple widgets are mounted', () => {
    const a = render([point(0, 1)]);
    const b = render([point(0, 2)]);
    expect(a.plot.captionId).not.toBe(b.plot.captionId);
    expect(a.host.querySelector('svg')?.getAttribute('aria-labelledby')).toBe(a.plot.captionId);
  });
  it('does not draw a NULL point or connect a gap', () => {
    const { host, plot } = render([point(0, 1), point(1, null), point(2, 3)]);
    expect(host.querySelectorAll('circle')).toHaveLength(2);
    expect(plot.segments()).toHaveLength(2);
  });
});
