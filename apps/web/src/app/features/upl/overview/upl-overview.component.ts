import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, filter, interval } from 'rxjs';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiDashboardCardComponent } from '../../../shared/ui/ui-dashboard-card.component';
import { UPL_OVERVIEW_PERIODS, UplOverview, UplOverviewApi, UplOverviewPeriod } from './overview-api';

/** How often an open, visible overview asks for fresh figures. */
const REFRESH_MS = 5 * 60 * 1000;

/**
 * The data overview (roadmap wave 5): what came into the warehouse over a
 * chosen period and what came of it. The page is a grid of widgets, each with
 * its own loading and failure state; it refreshes by itself while the tab is
 * visible and says when the figures were taken. It is a lazily loaded route,
 * so the start of the application does not grow with it.
 */
@Component({
  selector: 'app-upl-overview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TranslatePipe, UiButtonComponent, UiDashboardCardComponent],
  template: `
    <section class="overview" aria-labelledby="overview-title">
      <header class="overview__head">
        <div>
          <h1 id="overview-title" class="overview__title">{{ 'upl.overview.title' | t }}</h1>
          <p class="overview__subtitle">{{ 'upl.overview.subtitle' | t }}</p>
        </div>
        <div class="overview__controls">
          <div class="overview__periods" role="group" [attr.aria-label]="'upl.overview.period' | t">
            @for (period of periods; track period) {
              <button type="button" class="overview__period" data-testid="overview-period"
                [class.overview__period--active]="days() === period" [attr.aria-pressed]="days() === period"
                (click)="choose(period)">
                {{ 'upl.overview.days' | t: { n: period } }}
              </button>
            }
          </div>
          <ui-button variant="secondary" size="sm" icon="refresh" data-testid="overview-refresh" (onClick)="load()">
            {{ 'common.refresh' | t }}
          </ui-button>
        </div>
      </header>
      @if (data(); as current) {
        <p class="overview__stamp" data-testid="overview-stamp">
          {{ 'upl.overview.taken_at' | t: { time: (current.generatedAt | date: 'dd.MM.yyyy HH:mm') ?? '' } }}
        </p>
      }

      <div class="overview__grid">
        <ui-dashboard-card
          data-testid="overview-totals"
          [title]="'upl.overview.totals.title' | t"
          [subtitle]="'upl.overview.days_long' | t: { n: days() }"
          [loading]="loading() && !data()"
          [failed]="failed()"
          [empty]="data()?.totals?.uploads === 0"
          [emptyText]="'upl.overview.totals.empty' | t"
          (retry)="load()">
          @if (data(); as current) {
            <dl class="overview__tiles">
              <div class="overview__tile">
                <dt>{{ 'upl.overview.totals.uploads' | t }}</dt>
                <dd>{{ count(current.totals.uploads) }}</dd>
              </div>
              <div class="overview__tile">
                <dt>{{ 'upl.overview.totals.applied' | t }}</dt>
                <dd>{{ count(current.totals.applied) }}</dd>
              </div>
              <div class="overview__tile">
                <dt>{{ 'upl.overview.totals.verified' | t }}</dt>
                <dd>{{ count(current.totals.verified) }}</dd>
              </div>
              <div class="overview__tile">
                <dt>{{ 'upl.overview.totals.rejected' | t }}</dt>
                <dd>{{ count(current.totals.rejected) }}</dd>
              </div>
              <div class="overview__tile">
                <dt>{{ 'upl.overview.totals.rows' | t }}</dt>
                <dd>{{ count(current.totals.rowsApplied) }}</dd>
              </div>
            </dl>
          }
        </ui-dashboard-card>
      </div>
    </section>
  `,
  styles: [`
    .overview { display: flex; flex-direction: column; gap: 12px; padding: 24px; min-width: 0; }
    .overview__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .overview__title { margin: 0; font-size: 20px; font-weight: 700; color: var(--text-main); }
    .overview__subtitle { margin: 4px 0 0; font-size: 13px; color: var(--text-muted); }
    .overview__controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .overview__periods { display: inline-flex; border: 1px solid var(--border-color); border-radius: var(--radius-sm); overflow: hidden; }
    .overview__period {
      border: 0; background: var(--bg-surface); color: var(--text-main); padding: 6px 12px; font: inherit; font-size: 12px; cursor: pointer;
    }
    .overview__period + .overview__period { border-left: 1px solid var(--border-color); }
    .overview__period--active { background: var(--primary); color: var(--on-primary); }
    .overview__period:focus-visible { outline: 2px solid var(--focus-ring, var(--primary)); outline-offset: -2px; }
    .overview__stamp { margin: 0; font-size: 12px; color: var(--text-muted); }
    .overview__grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 16px; }
    .overview__tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 12px; margin: 0; }
    .overview__tile { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--bg-hover); }
    .overview__tile dt { font-size: 12px; color: var(--text-muted); }
    .overview__tile dd { margin: 4px 0 0; font-size: 22px; font-weight: 700; color: var(--text-main); font-variant-numeric: tabular-nums; }
  `]
})
export class UplOverviewComponent implements OnInit {
  private readonly api = inject(UplOverviewApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18nService);

  readonly periods = UPL_OVERVIEW_PERIODS;
  readonly days = signal<UplOverviewPeriod>(30);
  readonly data = signal<UplOverview | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);
  private request?: Subscription;

  ngOnInit(): void {
    this.load();
    interval(REFRESH_MS).pipe(
      filter(() => typeof document === 'undefined' || document.visibilityState === 'visible'),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => this.load());
  }

  /** A figure with digit grouping in the reader's language. */
  count(value: number): string {
    return new Intl.NumberFormat(this.i18n.currentLang()).format(value);
  }

  choose(period: UplOverviewPeriod): void {
    if (period === this.days()) return;
    this.days.set(period);
    this.data.set(null);
    this.load();
  }

  /** The latest answer wins: switching the period cancels the request still on its way. */
  load(): void {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.failed.set(false);
    this.request = this.api.get(this.days()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: overview => {
        this.data.set(overview);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.failed.set(true);
      }
    });
  }
}
