import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subject, Subscription, of, switchMap } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { SMTDateRangePickerComponent, type DateRange } from '@shared/ui-kit/components/forms/date-picker';
import { SMTDialogComponent, SMTDialogContentDirective, SMTModalService } from '@shared/ui-kit/components/modal';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { ReportSharingComponent } from '@shared/entity/report/report-sharing.component';
import { AnalyticsWidgetsComponent } from '../components/analytics-widgets.component';
import { DashboardEditorComponent } from './dashboard-editor.component';
import { DashboardWidgetComponent } from './dashboard-widget.component';
import { DashboardStore } from './dashboard.store';
import type { DashboardPeriod } from './dashboard.models';

@Component({
  selector: 'app-dashboards',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiFormActionsComponent,
    TranslatePipe,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    SMTDateRangePickerComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    ReportSharingComponent,
    AnalyticsWidgetsComponent,
    DashboardEditorComponent,
    DashboardWidgetComponent,
  ],
  providers: [DashboardStore],
  templateUrl: './dashboards.component.html',
  styleUrl: './dashboards.component.css',
})
export class DashboardsComponent {
  readonly store = inject(DashboardStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly sharing = viewChild(ReportSharingComponent);

  readonly nameMode = signal<'create' | 'rename' | null>(null);
  readonly name = signal('');
  readonly originalName = signal('');
  readonly nameError = signal('');
  readonly refresh = signal(0);

  readonly period = computed<DashboardPeriod | null>(() => {
    const from = this.params().get('from');
    const to = this.params().get('to');
    return from || to ? { from: from ?? '', to: to ?? '' } : null;
  });
  readonly options = computed(() => [
    { id: 0, label: this.i18n.translate('analytics.widgets.title') },
    ...this.store.panels.items().map((panel) => ({
      id: panel.id,
      label: panel.name,
      subLabel: panel.shared ? this.i18n.translate('ui.report.shared') : undefined,
    })),
  ]);
  readonly nameDirty = computed(() => this.nameMode() !== null && this.name().trim() !== this.originalName().trim());

  private readonly askDiscard = discardChangesQuestion();
  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });

  private readonly closeDecision = new Subject<void>();
  private decision?: Subscription;
  private navigation = 0;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.closeDecision.next();
      this.decision?.unsubscribe();
      this.closeDecision.complete();
    });
    this.store.panels.first();
    effect(() => {
      const raw = this.params().get('dashboard');
      const value = Number(raw);
      const id = raw && Number.isSafeInteger(value) && value > 0 ? value : null;
      untracked(() => {
        const navigation = ++this.navigation;
        this.closeDecision.next();
        this.decision?.unsubscribe();
        if (id === this.store.selectedId()) return;
        if (this.store.busy() || this.sharing()?.busy()) {
          this.navigate(this.store.selectedId(), true);
          return;
        }
        this.decision = this.canLeaveRecordPage(this.closeDecision).subscribe((discard) => {
          if (this.destroyRef.destroyed || navigation !== this.navigation) return;
          if (discard) {
            this.nameMode.set(null);
            this.store.load(id);
          } else this.navigate(this.store.selectedId(), true);
        });
      });
    });
  }

  choose(id: number | null): void {
    if (!this.store.busy()) this.navigate(id || null);
  }
  setPeriod(period: DateRange | null): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { from: period?.from ?? null, to: period?.to ?? null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
  openName(mode: 'create' | 'rename'): void {
    this.nameMode.set(mode);
    const name = mode === 'rename' ? (this.store.selected()?.name ?? '') : '';
    this.name.set(name);
    this.originalName.set(name);
    this.nameError.set('');
  }
  closeName(): void {
    if (!this.store.busy())
      this.askDiscard(this.nameDirty()).subscribe((discard) => {
        if (discard) this.nameMode.set(null);
      });
  }
  submitName(): void {
    if (!this.name().trim()) {
      this.nameError.set(this.i18n.translate('ui.report.name_required'));
      return;
    }
    const request = this.nameMode() === 'create' ? this.store.create(this.name()) : this.store.renameSaved(this.name());
    request.subscribe((saved) => {
      if (saved) {
        this.nameMode.set(null);
        this.navigate(this.store.selectedId());
      }
    });
  }
  cancelEdit(): void {
    this.askDiscard(this.store.dirty()).subscribe((discard) => {
      if (discard) this.store.cancel();
    });
  }
  reload(): void {
    this.canLeaveRecordPage().subscribe((discard) => {
      if (discard) {
        this.nameMode.set(null);
        this.store.load(this.store.selectedId());
      }
    });
  }
  remove(): void {
    const panel = this.store.selected();
    if (!panel || this.store.busy()) return;
    this.modal
      .confirm({
        title: this.i18n.translate('common.delete'),
        message: this.i18n.translate('analytics.panels.delete_confirm', { name: panel.name }),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (confirmed)
          this.store.remove().subscribe((removed) => {
            if (removed) this.navigate(null);
          });
      });
  }
  canLeaveRecordPage(closeWhen?: Observable<void>): Observable<boolean> {
    if (this.store.busy()) return of(false);
    return (this.sharing()?.canLeaveRecordPage(closeWhen) ?? of(true)).pipe(
      switchMap((allow) => (allow ? this.askDiscard(this.store.dirty() || this.nameDirty(), closeWhen) : of(false))),
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.store.dirty() || this.nameDirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  private navigate(id: number | null, replaceUrl = false): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { dashboard: id },
      queryParamsHandling: 'merge',
      replaceUrl,
    });
  }
}
