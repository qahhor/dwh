import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** One stack of the chart: a key into each point's values, its name and a colour token. */
export interface BarChartSeries {
  key: string;
  label: string;
  /** A CSS colour, normally a theme token such as `var(--success)`. */
  color: string;
}

/** One bar: its axis label and the value of every series. */
export interface BarChartPoint {
  label: string;
  values: Partial<Record<string, number>>;
}

let nextChartId = 0;

/**
 * Stacked bars in plain SVG (roadmap item 26; ADR-0015 action 34): no chart
 * library, so the start of the application does not grow and both themes work
 * through colour tokens. Every bar has a tooltip with its figures; the chart
 * is an image with a name for screen readers, and the same figures follow as a
 * table they can read — visually hidden, since the bars show them.
 */
@Component({
  selector: 'ui-bar-chart',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="bar-chart">
      <div class="bar-chart__legend" aria-hidden="true">
        @for (s of series(); track s.key) {
          <span class="bar-chart__key"><span class="bar-chart__swatch" [style.background]="s.color"></span>{{ s.label }}</span>
        }
      </div>
      <svg class="bar-chart__svg" [attr.viewBox]="'0 0 ' + width() + ' ' + height" preserveAspectRatio="none"
        role="img" [attr.aria-labelledby]="captionId">
        @for (tick of ticks(); track tick.value) {
          <line class="bar-chart__grid" x1="0" [attr.x2]="width()" [attr.y1]="tick.y" [attr.y2]="tick.y" />
        }
        @for (bar of bars(); track bar.index) {
          <g class="bar-chart__bar" data-testid="bar-chart-bar">
            <title>{{ bar.title }}</title>
            @for (part of bar.parts; track part.key) {
              <rect [attr.x]="bar.x" [attr.y]="part.y" [attr.width]="bar.w" [attr.height]="part.h" [attr.fill]="part.color" />
            }
          </g>
        }
      </svg>
      <div class="bar-chart__axis" aria-hidden="true">
        <span>{{ points()[0]?.label }}</span>
        <span>{{ points()[points().length - 1]?.label }}</span>
      </div>
      <figcaption class="sr-only" [id]="captionId">{{ caption() }}</figcaption>
      <table class="sr-only" data-testid="bar-chart-table">
        <caption>{{ caption() }}</caption>
        <thead>
          <tr>
            <th scope="col">{{ axisLabel() }}</th>
            @for (s of series(); track s.key) {
              <th scope="col">{{ s.label }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (point of points(); track $index) {
            <tr>
              <th scope="row">{{ point.label }}</th>
              @for (s of series(); track s.key) {
                <td>{{ point.values[s.key] ?? 0 }}</td>
              }
            </tr>
          }
        </tbody>
      </table>
    </figure>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .bar-chart { margin: 0; display: flex; flex-direction: column; gap: 6px; }
    .bar-chart__legend { display: flex; flex-wrap: wrap; gap: 12px; font-size: 12px; color: var(--text-muted); }
    .bar-chart__key { display: inline-flex; align-items: center; gap: 6px; }
    .bar-chart__swatch { width: 10px; height: 10px; border-radius: 2px; }
    .bar-chart__svg { width: 100%; height: 180px; display: block; }
    .bar-chart__grid { stroke: var(--border-color); stroke-width: 1; vector-effect: non-scaling-stroke; }
    .bar-chart__bar:hover rect { opacity: 0.8; }
    .bar-chart__axis { display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); font-variant-numeric: tabular-nums; }
  `]
})
export class UiBarChartComponent {
  readonly series = input.required<readonly BarChartSeries[]>();
  readonly points = input.required<readonly BarChartPoint[]>();
  /** What the chart shows, for its accessible name and the table's caption. */
  readonly caption = input.required<string>();

  /** The header of the table's first column (the axis), e.g. "Day". */
  readonly axisLabel = input('');

  readonly width = computed(() => Math.max(1, this.points().length) * this.step);

  readonly ticks = computed(() => {
    const max = this.max();
    return [0.25, 0.5, 0.75, 1].map(share => ({ value: max * share, y: this.height - this.height * share }));
  });

  readonly bars = computed(() => {
    const max = this.max();
    return this.points().map((point, index) => {
      let top = this.height;
      const parts = this.series().map(s => {
        const value = point.values[s.key] ?? 0;
        const h = max === 0 ? 0 : (value / max) * this.height;
        top -= h;
        return { key: s.key, y: top, h, color: s.color };
      });
      const title = `${point.label}: ` + this.series().map(s => `${s.label} ${point.values[s.key] ?? 0}`).join(', ');
      return { index, x: index * this.step + this.gap / 2, w: this.step - this.gap, parts, title };
    });
  });

  private readonly max = computed(() => {
    const totals = this.points().map(point => this.series().reduce((sum, s) => sum + (point.values[s.key] ?? 0), 0));
    return niceMax(Math.max(0, ...totals));
  });

  readonly captionId = `bar-chart-caption-${nextChartId++}`;
  readonly height = 180;
  private readonly step = 20;
  private readonly gap = 4;
}

/** A round top for the scale (1, 2, 5, 10, 20, 50…), so gridlines fall on readable numbers. */
export function niceMax(value: number): number {
  if (value <= 0) return 0;
  const power = Math.pow(10, Math.floor(Math.log10(value)));
  const scaled = value / power;
  const nice = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
  return nice * power;
}
