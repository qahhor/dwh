import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface ReportPlotLabel {
  row: number;
  value: number | null;
  position: number;
  connect: boolean;
  label: string;
  text: string;
}

let nextPlotId = 0;

/** Single-measure plots; their source table remains visible in the report (ADR-0039). */
@Component({
  selector: 'ui-report-plot',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="report-plot">
      <figcaption [id]="captionId">{{ caption() }}</figcaption>
      @if (kind() === 'line') {
        <div data-testid="report-line">
          <svg class="report-plot__line" viewBox="0 0 600 220" role="img" [attr.aria-labelledby]="captionId">
            <line class="report-plot__zero" x1="16" x2="584" [attr.y1]="y(0)" [attr.y2]="y(0)" />
            @for (segment of segments(); track $index) {
              <path class="report-plot__stroke" data-testid="line-segment" [attr.d]="segment" />
            }
            @for (point of points(); track point.row) {
              @if (point.value !== null) {
                <circle
                  class="report-plot__point"
                  data-testid="line-point"
                  [attr.cx]="x(point.position)"
                  [attr.cy]="y(point.value)"
                  r="3"
                >
                  <title>{{ point.label }}: {{ point.text }}</title>
                </circle>
              }
            }
          </svg>
          <div class="report-plot__labels" aria-hidden="true">
            <span>{{ points()[0]?.label }}</span
            ><span>{{ points()[points().length - 1]?.label }}</span>
          </div>
        </div>
      } @else {
        <div
          class="report-plot__bars"
          data-testid="report-horizontal-bar"
          role="img"
          [attr.aria-labelledby]="captionId"
        >
          @for (point of points(); track point.row) {
            <div class="report-plot__bar" aria-hidden="true">
              <span class="report-plot__category">{{ point.label }}</span>
              <svg viewBox="0 0 240 24" preserveAspectRatio="none">
                <line
                  class="report-plot__zero"
                  data-testid="horizontal-zero"
                  [attr.x1]="horizontal(0)"
                  [attr.x2]="horizontal(0)"
                  y1="0"
                  y2="24"
                />
                @if (point.value !== null) {
                  <rect
                    class="report-plot__point"
                    data-testid="horizontal-value"
                    [attr.x]="barStart(point.value)"
                    y="3"
                    [attr.width]="barWidth(point.value)"
                    height="18"
                  />
                }
              </svg>
              <span class="report-plot__value">{{ point.text }}</span>
            </div>
          }
        </div>
      }
    </figure>
  `,
  styleUrl: './ui-report-plot.component.css',
})
export class UiReportPlotComponent {
  readonly points = input.required<ReportPlotLabel[]>();
  readonly caption = input.required<string>();
  readonly kind = input.required<'line' | 'horizontalBar'>();

  readonly segments = computed(() => {
    const paths: string[] = [];
    for (const point of this.points()) {
      if (point.value === null) continue;
      const coordinate = `${this.x(point.position)} ${this.y(point.value)}`;
      if (point.connect && paths.length) paths[paths.length - 1] += ` L ${coordinate}`;
      else paths.push(`M ${coordinate}`);
    }
    return paths;
  });
  private readonly range = computed(() => {
    const values = this.points().flatMap((point) => (point.value === null ? [] : [point.value]));
    const min = Math.min(0, ...values);
    const max = Math.max(0, ...values);
    return { min, max: min === max ? max + 1 : max };
  });

  readonly captionId = `report-plot-${nextPlotId++}`;

  x(position: number): number {
    const points = this.points();
    const start = points[0]?.position ?? 0;
    const length = (points[points.length - 1]?.position ?? start) - start;
    return length === 0 ? 300 : 16 + ((position - start) / length) * 568;
  }
  y(value: number): number {
    const { min, max } = this.range();
    return 204 - ((value - min) / (max - min)) * 188;
  }
  horizontal(value: number): number {
    const { min, max } = this.range();
    return 2 + ((value - min) / (max - min)) * 236;
  }
  barStart(value: number): number {
    return Math.min(this.horizontal(0), this.horizontal(value));
  }
  barWidth(value: number): number {
    return Math.abs(this.horizontal(value) - this.horizontal(0));
  }
}
