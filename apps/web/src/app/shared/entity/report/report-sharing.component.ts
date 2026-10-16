import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  computed,
  effect,
  untracked,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subscription, finalize, of, tap } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { problemText } from '@shared/ui/problem-text';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { ReportCatalogApi, type ReportSharing, type ReportShareRole } from './report-catalog.api';

@Component({
  selector: 'smt-report-sharing',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiFormActionsComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTMultiSelectComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    TranslatePipe,
  ],
  templateUrl: './report-sharing.component.html',
})
export class ReportSharingComponent {
  private readonly api = inject(ReportCatalogApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18nService);

  readonly path = input.required<string>();

  readonly changed = output<ReportSharing>();

  readonly open = signal(false);
  readonly selected = signal<readonly number[]>([]);
  readonly initial = signal<ReportSharing | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly conflict = signal(false);
  readonly error = signal('');

  readonly options = computed(() => this.roles.items().map((role) => ({ id: role.id, label: role.name })));
  readonly known = computed(() =>
    this.selected().map((id) => ({ id, label: this.roles.items().find((role) => role.id === id)?.name ?? `#${id}` })),
  );
  readonly dirty = computed(
    () => JSON.stringify([...this.selected()].sort()) !== JSON.stringify([...(this.initial()?.roleIds ?? [])].sort()),
  );

  private readonly askDiscard = discardChangesQuestion();
  private read?: Subscription;
  private operation = 0;
  readonly roles = new KeysetPager<ReportShareRole>((cursor, limit) => this.api.roles(cursor, limit), {
    destroyRef: this.destroyRef,
  });

  constructor() {
    effect(() => {
      this.path();
      untracked(() => this.reset());
    });
    this.destroyRef.onDestroy(() => this.read?.unsubscribe());
  }

  show(): void {
    if (this.busy()) return;
    this.open.set(true);
    this.load();
    this.roles.first();
  }

  reload(): void {
    if (this.busy() || this.loading()) return;
    const path = this.path();
    this.askDiscard(this.dirty())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((discard) => {
        if (discard && path === this.path() && this.open()) this.load();
      });
  }

  save(): void {
    const initial = this.initial();
    if (!initial || this.busy() || this.loading() || this.conflict()) return;
    if (this.selected().length > 20) {
      this.error.set(this.i18n.translate('ui.entity_form.too_many', { count: 20 }));
      return;
    }
    const path = this.path();
    const operation = this.operation;
    this.busy.set(true);
    this.error.set('');
    this.api
      .share(path, [...this.selected()], initial.revision)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (saved) => {
          if (operation !== this.operation || path !== this.path()) return;
          this.initial.set(saved);
          this.selected.set(saved.roleIds);
          this.changed.emit(saved);
          this.open.set(false);
        },
        error: (failure: unknown) => {
          if (operation === this.operation && path === this.path()) this.fail(failure);
        },
      });
  }

  canLeaveRecordPage(closeWhen?: Observable<void>): Observable<boolean> {
    if (this.busy()) return of(false);
    const path = this.path();
    return this.askDiscard(this.open() && this.dirty(), closeWhen).pipe(
      tap((discard) => {
        if (discard && path === this.path()) this.reset();
      }),
    );
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.open() && (this.dirty() || this.busy())) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  close(): void {
    if (this.busy()) return;
    const path = this.path();
    this.askDiscard(this.dirty())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((discard) => {
        if (discard && path === this.path()) this.reset();
      });
  }

  private load(): void {
    const operation = ++this.operation;
    const path = this.path();
    this.read?.unsubscribe();
    this.loading.set(true);
    this.initial.set(null);
    this.error.set('');
    this.read = this.api
      .sharing(path)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.loading.set(false);
        }),
      )
      .subscribe({
        next: (sharing) => {
          if (operation !== this.operation || path !== this.path()) return;
          this.initial.set(sharing);
          this.selected.set(sharing.roleIds);
          this.conflict.set(false);
        },
        error: (failure: unknown) => {
          if (operation === this.operation && path === this.path()) this.fail(failure);
        },
      });
  }

  private reset(): void {
    ++this.operation;
    this.read?.unsubscribe();
    this.loading.set(false);
    this.open.set(false);
    this.initial.set(null);
    this.selected.set([]);
    this.conflict.set(false);
    this.error.set('');
  }

  private fail(failure: unknown): void {
    this.conflict.set(
      typeof failure === 'object' && failure !== null && (failure as { status?: number }).status === 409,
    );
    const fields = Object.values(problemFieldErrors(failure).fields).slice(0, 20);
    this.error.set([problemText(failure) || this.i18n.translate('common.operation_failed'), ...fields].join(' '));
  }
}
