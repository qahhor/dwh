import { ChangeDetectionStrategy, Component, computed, signal, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { TrendDataPoint, ChartPoint, YAxisTick } from '../analytics.models';

@Component({
  selector: 'app-analytics-trend-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  templateUrl: './analytics-trend-chart.component.html',
  styles: [
    `
      .analytics-card {
        min-width: 0;
        background-color: var(--bg-surface);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-lg);
        padding: 18px 22px;
        box-shadow: var(--shadow-sm);
        display: flex;
        flex-direction: column;
        gap: 16px;
      }

      .card-header-row {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 12px;
      }

      .card-title {
        font-size: 15px;
        font-weight: 700;
        color: var(--text-main);
        overflow-wrap: anywhere;
      }

      .card-subtitle {
        font-size: 12px;
        color: var(--text-muted);
        margin-top: 2px;
        overflow-wrap: anywhere;
      }

      .chart-legend {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 14px;
      }

      .legend-item {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        font-weight: 500;
        color: var(--text-main);
      }

      .legend-dot {
        width: 8px;
        height: 8px;
        flex-shrink: 0;
        border-radius: 50%;
      }

      .svg-chart-container {
        width: 100%;
        min-width: 0;
        overflow-x: auto;
        position: relative;
      }

      .svg-chart-container:focus-visible {
        outline: 2px solid var(--primary);
        outline-offset: -2px;
      }

      .chart-y-axis {
        position: absolute;
        left: 4px;
        top: 0;
        bottom: 0;
        width: 32px;
        pointer-events: none;
        z-index: 2;
      }

      .y-axis-tick {
        position: absolute;
        right: 0;
        font-size: 10px;
        line-height: 1;
        color: var(--text-muted);
        text-align: right;
      }

      .trend-svg {
        display: block;
        width: 100%;
        min-width: 700px;
        height: 240px;
      }

      .empty-chart {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 50px 20px;
        gap: 8px;
        color: var(--text-muted);
        font-size: 13px;
      }

      .chart-tooltip-floating {
        position: absolute;
        top: 10px;
        background-color: var(--bg-surface);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-sm);
        box-shadow: var(--shadow-md);
        padding: 6px 10px;
        font-size: 11px;
        pointer-events: none;
        z-index: 10;
        transition: left 0.08s ease-out;
      }

      .tooltip-date {
        font-weight: 600;
        color: var(--text-muted);
        margin-bottom: 4px;
      }

      .tooltip-values {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .tooltip-val {
        display: flex;
        align-items: center;
        gap: 6px;
        color: var(--text-main);
      }

      .tooltip-dot {
        width: 6px;
        height: 6px;
        border-radius: 50%;
      }

      .hit-area {
        cursor: pointer;
      }

      .chart-point-group:focus-visible circle {
        stroke-width: 3;
        stroke: var(--text-main);
      }

      .font-mono {
        font-family: monospace;
      }
    `,
  ],
})
export class AnalyticsTrendChartComponent {
  readonly displayedRange = input('7d');
  readonly loading = input(false);
  readonly error = input('');

  readonly trends = input<TrendDataPoint[]>([]);

  hoveredPoint = signal<ChartPoint | null>(null);
  hoverIndex = signal<number | null>(null);

  chartPoints = computed<ChartPoint[]>(() => {
    const list = this._trends();
    if (list.length === 0) return [];

    let maxVal = 1;
    for (const d of list) {
      if (d.createdCount > maxVal) maxVal = d.createdCount;
      if (d.completedCount > maxVal) maxVal = d.completedCount;
    }

    const width = 640;
    const startX = 40;
    const bottomY = 190;
    const topY = 40;
    const height = bottomY - topY;

    const step = list.length > 1 ? width / (list.length - 1) : width;

    return list.map((d, i) => {
      const x = startX + i * step;
      const yCreated = bottomY - (d.createdCount / maxVal) * height;
      const yCompleted = bottomY - (d.completedCount / maxVal) * height;
      const label = d.date.substring(5); // MM-DD
      return { x, yCreated, yCompleted, label, date: d.date, created: d.createdCount, completed: d.completedCount };
    });
  });

  yAxisTicks = computed<YAxisTick[]>(() => {
    const list = this._trends();
    if (list.length === 0) return [];
    let maxVal = 1;
    for (const d of list) {
      if (d.createdCount > maxVal) maxVal = d.createdCount;
      if (d.completedCount > maxVal) maxVal = d.completedCount;
    }
    return [
      { y: 40, value: maxVal },
      { y: 90, value: Math.round((maxVal * 2) / 3) },
      { y: 140, value: Math.round(maxVal / 3) },
      { y: 190, value: 0 },
    ];
  });

  createdLinePath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    return pts.map((p, i) => (i === 0 ? `M ${p.x} ${p.yCreated}` : `L ${p.x} ${p.yCreated}`)).join(' ');
  });

  completedLinePath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    return pts.map((p, i) => (i === 0 ? `M ${p.x} ${p.yCompleted}` : `L ${p.x} ${p.yCompleted}`)).join(' ');
  });

  createdAreaPath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    const line = this.createdLinePath();
    const last = pts[pts.length - 1];
    const first = pts[0];
    return `${line} L ${last.x} 190 L ${first.x} 190 Z`;
  });

  completedAreaPath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    const line = this.completedLinePath();
    const last = pts[pts.length - 1];
    const first = pts[0];
    return `${line} L ${last.x} 190 L ${first.x} 190 Z`;
  });
  private _trends = computed<TrendDataPoint[]>(() => this.trends() || []);

  setHoveredPoint(pt: ChartPoint, idx: number): void {
    this.hoveredPoint.set(pt);
    this.hoverIndex.set(idx);
  }

  clearHover(): void {
    this.hoveredPoint.set(null);
    this.hoverIndex.set(null);
  }

  getTooltipLeft(x: number): number {
    return Math.max(10, Math.min(x - 60, 560));
  }

  shouldShowDateLabel(index: number, total: number): boolean {
    if (total <= 7) return true;
    if (total <= 14) return index % 2 === 0;
    return index % Math.ceil(total / 6) === 0 || index === total - 1;
  }
}
