import { ChangeDetectionStrategy, Component, computed, signal, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { TrendDataPoint, ChartPoint, YAxisTick } from '../analytics.models';

@Component({
  selector: 'app-analytics-trend-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  templateUrl: './analytics-trend-chart.component.html',
  styleUrl: './analytics-trend-chart.component.css',
})
export class AnalyticsTrendChartComponent {
  readonly displayedRange = input('7d');
  readonly loading = input(false);
  readonly error = input('');

  readonly trends = input<TrendDataPoint[]>([]);

  readonly hoveredPoint = signal<ChartPoint | null>(null);
  readonly hoverIndex = signal<number | null>(null);

  readonly chartPoints = computed<ChartPoint[]>(() => {
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

  readonly yAxisTicks = computed<YAxisTick[]>(() => {
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

  readonly createdLinePath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    return pts.map((p, i) => (i === 0 ? `M ${p.x} ${p.yCreated}` : `L ${p.x} ${p.yCreated}`)).join(' ');
  });

  readonly completedLinePath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    return pts.map((p, i) => (i === 0 ? `M ${p.x} ${p.yCompleted}` : `L ${p.x} ${p.yCompleted}`)).join(' ');
  });

  readonly createdAreaPath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    const line = this.createdLinePath();
    const last = pts[pts.length - 1];
    const first = pts[0];
    return `${line} L ${last.x} 190 L ${first.x} 190 Z`;
  });

  readonly completedAreaPath = computed(() => {
    const pts = this.chartPoints();
    if (pts.length === 0) return '';
    const line = this.completedLinePath();
    const last = pts[pts.length - 1];
    const first = pts[0];
    return `${line} L ${last.x} 190 L ${first.x} 190 Z`;
  });
  private readonly _trends = computed<TrendDataPoint[]>(() => this.trends() || []);

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
