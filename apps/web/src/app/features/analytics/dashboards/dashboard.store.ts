import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subscription, catchError, finalize, map, of, tap } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { problemText } from '@shared/ui/problem-text';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { DashboardApi } from './dashboard.api';
import type { DashboardDetail, DashboardSummary, DashboardWidgetWrite, DashboardWrite } from './dashboard.models';

export const DASHBOARD_WIDGET_LIMIT = 12;

@Injectable()
export class DashboardStore {
  private readonly api = inject(DashboardApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18nService);

  readonly selectedId = signal<number | null>(null);
  readonly selected = signal<DashboardDetail | null>(null);
  readonly draft = signal<DashboardWrite | null>(null);
  readonly conflict = signal(false);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');

  readonly dirty = computed(() => {
    const draft = this.draft();
    const selected = this.selected();
    return !!draft && !!selected && JSON.stringify(draft) !== JSON.stringify(payload(selected));
  });

  private read?: Subscription;
  private operation = 0;
  readonly panels = new KeysetPager<DashboardSummary>((cursor, limit) => this.api.list(cursor, limit), {
    destroyRef: this.destroyRef,
  });

  load(id: number | null): void {
    if (this.busy()) return;
    const operation = ++this.operation;
    this.read?.unsubscribe();
    this.selectedId.set(id);
    this.selected.set(null);
    this.draft.set(null);
    this.error.set('');
    this.conflict.set(false);
    this.loading.set(id !== null);
    if (id === null) return;
    this.read = this.api
      .get(id)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.loading.set(false);
        }),
      )
      .subscribe({ next: (panel) => this.accept(panel), error: (failure: unknown) => this.fail(failure) });
  }

  edit(): void {
    const panel = this.selected();
    if (!panel?.canEdit || this.busy() || this.conflict()) return;
    this.draft.set(payload(panel));
    this.error.set('');
  }

  cancel(): void {
    if (!this.busy()) {
      this.draft.set(null);
      this.error.set('');
    }
  }

  rename(name: string): void {
    this.change((draft) => ({ ...draft, name }));
  }

  move(index: number, delta: number): void {
    this.change((draft) => {
      const next = index + delta;
      if (index < 0 || next < 0 || index >= draft.widgets.length || next >= draft.widgets.length) return draft;
      const widgets = [...draft.widgets];
      [widgets[index], widgets[next]] = [widgets[next], widgets[index]];
      return { ...draft, widgets };
    });
  }

  add(viewId: number, periodField?: string, width: 1 | 2 = 1): void {
    this.change((draft) =>
      draft.widgets.length >= DASHBOARD_WIDGET_LIMIT || draft.widgets.some((item) => item.viewId === viewId)
        ? draft
        : { ...draft, widgets: [...draft.widgets, { viewId, width, ...(periodField ? { periodField } : {}) }] },
    );
  }

  configure(index: number, periodField: string | null, width: 1 | 2): void {
    this.change((draft) => ({
      ...draft,
      widgets: draft.widgets.map((item, at) =>
        at === index ? { viewId: item.viewId, width, ...(periodField ? { periodField } : {}) } : item,
      ),
    }));
  }

  takeOff(index: number): void {
    this.change((draft) => ({ ...draft, widgets: draft.widgets.filter((_item, at) => at !== index) }));
  }

  save(): void {
    const panel = this.selected();
    const draft = this.draft();
    if (!panel?.canEdit || !draft || this.busy() || this.conflict()) return;
    this.busy.set(true);
    this.error.set('');
    this.api
      .update(panel.id, { ...draft, name: draft.name.trim() }, panel.revision)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({ next: (saved) => this.accept(saved), error: (failure: unknown) => this.fail(failure) });
  }

  create(name: string): Observable<boolean> {
    if (this.busy()) return of(false);
    ++this.operation;
    this.read?.unsubscribe();
    this.loading.set(false);
    this.busy.set(true);
    this.error.set('');
    return this.api.create({ name: name.trim(), widgets: [] }).pipe(
      takeUntilDestroyed(this.destroyRef),
      tap((saved) => this.accept(saved)),
      map(() => true),
      catchError((failure: unknown) => {
        this.fail(failure);
        return of(false);
      }),
      finalize(() => this.busy.set(false)),
    );
  }

  renameSaved(name: string): Observable<boolean> {
    const panel = this.selected();
    if (!panel?.canEdit || this.busy() || this.conflict()) return of(false);
    this.busy.set(true);
    this.error.set('');
    return this.api.update(panel.id, { ...payload(panel), name: name.trim() }, panel.revision).pipe(
      takeUntilDestroyed(this.destroyRef),
      tap((saved) => this.accept(saved)),
      map(() => true),
      catchError((failure: unknown) => {
        this.fail(failure);
        return of(false);
      }),
      finalize(() => this.busy.set(false)),
    );
  }

  remove(): Observable<boolean> {
    const panel = this.selected();
    if (!panel?.canEdit || this.busy()) return of(false);
    this.busy.set(true);
    this.error.set('');
    return this.api.remove(panel.id, panel.revision).pipe(
      takeUntilDestroyed(this.destroyRef),
      tap(() => {
        this.selected.set(null);
        this.selectedId.set(null);
        this.draft.set(null);
        this.panels.items.update((items) => items.filter((item) => item.id !== panel.id));
      }),
      map(() => true),
      catchError((failure: unknown) => {
        this.fail(failure);
        return of(false);
      }),
      finalize(() => this.busy.set(false)),
    );
  }

  markStale(): void {
    this.conflict.set(true);
    this.error.set(this.i18n.translate('analytics.panels.conflict'));
  }

  sharingChanged(revision: number, shared: boolean): void {
    const panel = this.selected();
    if (panel) this.accept({ ...panel, revision, shared });
  }

  private change(change: (draft: DashboardWrite) => DashboardWrite): void {
    const draft = this.draft();
    if (draft && !this.busy()) this.draft.set(change(draft));
  }

  private accept(panel: DashboardDetail): void {
    this.selectedId.set(panel.id);
    this.selected.set(panel);
    this.draft.set(null);
    this.conflict.set(false);
    this.error.set('');
    this.panels.items.update((items) => [...items.filter((item) => item.id !== panel.id), panel]);
  }

  private fail(failure: unknown): void {
    const status =
      typeof failure === 'object' && failure !== null ? (failure as { status?: number }).status : undefined;
    this.conflict.set(status === 409);
    const fields = Object.entries(problemFieldErrors(failure).fields)
      .slice(0, 12)
      .map(([path, message]) => `${path}: ${message}`);
    this.error.set(
      [problemText(failure) || this.i18n.translate('common.operation_failed'), ...fields].join(String.fromCharCode(10)),
    );
  }
}

function payload(panel: DashboardDetail): DashboardWrite {
  return {
    name: panel.name,
    widgets: panel.widgets.map(({ viewId, width, periodField }): DashboardWidgetWrite => ({
      viewId,
      width,
      ...(periodField ? { periodField } : {}),
    })),
  };
}
