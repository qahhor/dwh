import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  untracked,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Observable, of, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { ReportCatalogApi } from './report-catalog.api';
import { ReportSharingComponent } from './report-sharing.component';
import type { ReportSharing } from './report-catalog.api';
import { PermissionService } from '@core/services/permission.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { problemText } from '@shared/ui/problem-text';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTDialogComponent, SMTDialogContentDirective, SMTModalService } from '@shared/ui-kit/components/modal';
import { EntityReportsApi, type ReportState, type SavedReport } from './entity-reports';

const NAME_MAX = 80;

let nextSavedId = 0;

/**
 * The person's saved reports of a list (ADR-0032 10.2): open one, write what is on screen into it, save it as a new
 * report or as a widget of the dashboard, put it on the dashboard or take it off, delete it. Reports are the person's
 * own (ADR-0032 19, question 6: the proposed default).
 */
@Component({
  selector: 'smt-entity-report-saved',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReportSharingComponent,
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTInputComponent,
    SMTSelectComponent,
    TranslatePipe,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
  ],
  templateUrl: './smt-entity-report-saved.component.html',
  styleUrl: './smt-entity-report-saved.component.css',
})
export class SMTEntityReportSavedComponent {
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly reports = inject(EntityReportsApi);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly catalog = inject(ReportCatalogApi);
  private readonly permissions = inject(PermissionService);

  readonly listCode = input.required<string>();
  /** What is on screen now: what a save writes. */
  readonly state = input.required<ReportState>();

  /** A saved report to open once the reports are read, as the reports catalog asks; once. */
  readonly openId = input<number | null>(null);

  /** A saved report was chosen: the builder shows it. */
  readonly opened = output<SavedReport>();

  private readonly sharing = viewChild(ReportSharingComponent);

  readonly saved = linkedSignal<SavedReport[]>(() => this.list.items());
  readonly selectedSnapshot = signal<SavedReport | null>(null);
  readonly renaming = signal(false);
  readonly conflict = signal(false);
  readonly activeId = signal<number | null>(null);
  readonly busy = signal(false);
  readonly saveAsOpen = signal(false);
  readonly name = signal('');
  readonly asWidget = signal(false);
  /** The error under the name: empty, or the server's word on it. */
  readonly error = signal('');
  /** A refusal of the save that names no field: an alert at the top of the dialog. */
  readonly saveError = signal('');

  readonly active = computed(() =>
    this.list.failed()
      ? null
      : (this.saved().find((report) => report.id === this.activeId()) ??
        (this.selectedSnapshot()?.id === this.activeId() ? this.selectedSnapshot() : null)),
  );
  readonly sharingPath = computed(() => `/list-views/${encodeURIComponent(this.listCode())}/${this.activeId()}`);
  readonly options = computed<SMTSelectOption<number>[]>(() => {
    this.i18n.currentLang();
    const current = this.active();
    const rows =
      current && !this.saved().some((item) => item.id === current.id) ? [...this.saved(), current] : this.saved();
    return (this.list.failed() ? [] : rows).map((report) => ({
      id: report.id,
      label: report.name,
      icon: report.kind === 'widget' ? 'dashboard' : 'summarize',
      subLabel: report.shared
        ? this.i18n.translate('ui.report.shared')
        : report.kind === 'widget'
          ? this.i18n.translate('ui.report.on_dashboard')
          : undefined,
    }));
  });

  /** The reports on screen: the read, then the person's saves and deletions over it. */
  readonly list = new KeysetPager((cursor, limit) => this.catalog.list(this.listCode(), cursor, limit), {
    destroyRef: this.destroyRef,
  });

  private readonly askDiscard = discardChangesQuestion();
  /** The report the catalog named has been opened. */
  private openedFromCatalog: number | null = null;

  readonly ids = `report-saved-${nextSavedId++}`;
  readonly nameMax = NAME_MAX;

  constructor() {
    effect(() => {
      const id = this.openId();
      const report = id === null ? undefined : this.list.items().find((item) => item.id === id);
      if (!report || this.openedFromCatalog === id) return;
      untracked(() => {
        this.openedFromCatalog = id;
        this.activeId.set(id);
        this.opened.emit(report);
      });
    });
    effect(() => {
      this.listCode();
      untracked(() => {
        this.activeId.set(null);
        this.selectedSnapshot.set(null);
        this.list.items.set([]);
        this.list.first();
      });
    });
  }

  reload(): void {
    this.canLeaveRecordPage().subscribe((allow) => {
      if (!allow) return;
      this.saveAsOpen.set(false);
      this.conflict.set(false);
      this.saveError.set('');
      this.list.first();
    });
  }

  sharingChanged(sharing: ReportSharing): void {
    const active = this.active();
    if (active) this.replace({ ...active, lockVersion: sharing.revision, shared: sharing.roleIds.length > 0 });
    this.list.first();
  }

  openRename(): void {
    const active = this.active();
    if (!active?.canEdit || this.busy()) return;
    this.openSaveAs();
    this.renaming.set(true);
    this.name.set(active.name);
    this.asWidget.set(active.kind === 'widget');
  }

  canLeaveRecordPage(): Observable<boolean> {
    if (this.busy()) return of(false);
    return (this.sharing()?.canLeaveRecordPage() ?? of(true)).pipe(
      switchMap((allow) =>
        allow
          ? this.askDiscard(
              this.saveAsOpen() &&
                (this.renaming()
                  ? this.name().trim() !== this.active()?.name
                  : !!this.name().trim() || this.asWidget()),
            )
          : of(false),
      ),
    );
  }

  pick(id: number | null): void {
    if (id === this.activeId()) return;
    this.canLeaveRecordPage().subscribe((allow) => {
      if (!allow) return;
      this.activeId.set(id);
      const report = this.active();
      if (report) this.opened.emit(report);
    });
  }

  openSaveAs(): void {
    this.renaming.set(false);
    this.name.set('');
    this.asWidget.set(false);
    this.error.set('');
    this.saveError.set('');
    this.saveAsOpen.set(true);
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before a typed name is dropped (forms standard, 8). */
  closeSaveAs(): void {
    if (this.busy()) return;
    this.askDiscard(
      this.renaming() ? this.name().trim() !== this.active()?.name : !!this.name().trim() || this.asWidget(),
    ).subscribe((discard) => {
      if (discard) this.saveAsOpen.set(false);
    });
  }

  /** Enter and "Save" land here; one request while a save runs; an empty name says so and takes the focus. */
  submitSaveAs(): void {
    if (this.busy() || (this.renaming() && (!this.active()?.canEdit || this.conflict()))) return;
    const name = this.name().trim();
    this.saveError.set('');
    if (!name) {
      this.error.set(this.i18n.translate('ui.report.name_required'));
      return;
    }
    this.error.set('');
    this.busy.set(true);
    const active = this.active();
    const request =
      this.renaming() && active?.canEdit
        ? this.reports.update(this.listCode(), { ...active, name })
        : this.reports.save(this.listCode(), name, this.asWidget() ? 'widget' : 'report', this.state());
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (report) => {
        this.busy.set(false);
        this.saveAsOpen.set(false);
        this.replace({
          ...(this.renaming() ? active : null),
          ...report,
          canEdit: true,
          canShare: active?.canShare ?? this.permissions.canUpdate('md.settings'),
        });
        this.activeId.set(report.id);
        this.list.first();
        this.toast.success(this.i18n.translate('ui.report.saved_done'));
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        this.conflict.set(
          typeof failure === 'object' && failure !== null && (failure as { status?: number }).status === 409,
        );
        // The server's word on the name goes under it (forms standard, section 5); anything else is the alert.
        const { fields, other } = problemFieldErrors(failure, { known: ['name'] });
        if (fields['name']) {
          this.error.set(fields['name']);
          const form = document.getElementById(`${this.ids}-form`);
          if (form) focusFirstInvalid(form, this.injector);
        }
        if (!fields['name'] || other.length > 0) {
          this.saveError.set(other[0] ?? (problemText(failure) || this.i18n.translate('ui.report.save_failed')));
        }
      },
    });
  }

  saveActive(report: SavedReport): void {
    if (!report.canEdit) return;
    this.write({ ...report, state: this.state() });
  }

  togglePin(report: SavedReport): void {
    if (!report.canEdit) return;
    this.write({ ...report, kind: report.kind === 'widget' ? 'report' : 'widget' });
  }

  remove(report: SavedReport): void {
    if (!report.canEdit || this.busy()) return;
    this.modal
      .confirm({ message: this.i18n.translate('ui.report.delete_confirm', { name: report.name }), destructive: true })
      .subscribe((confirmed) => {
        if (!confirmed) return;
        this.reports.remove(this.listCode(), report.id, report.lockVersion).subscribe({
          next: () => {
            this.saved.update((reports) => reports.filter((item) => item.id !== report.id));
            this.activeId.set(null);
          },
          error: (failure: unknown) =>
            this.toast.error(problemText(failure) || this.i18n.translate('ui.report.save_failed')),
        });
      });
  }

  private write(report: SavedReport): void {
    if (this.busy() || this.conflict() || this.list.loading()) return;
    this.saveError.set('');
    this.busy.set(true);
    this.reports.update(this.listCode(), report).subscribe({
      next: (written) => {
        this.busy.set(false);
        this.replace({ ...report, ...written });
        this.list.first();
        this.toast.success(this.i18n.translate('ui.report.saved_done'));
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        this.conflict.set(
          typeof failure === 'object' && failure !== null && (failure as { status?: number }).status === 409,
        );
        this.saveError.set(problemText(failure) || this.i18n.translate('ui.report.save_failed'));
        this.toast.error(this.saveError());
      },
    });
  }

  private replace(report: SavedReport): void {
    this.selectedSnapshot.set(report);
    this.saved.update((reports) =>
      [...reports.filter((item) => item.id !== report.id), report].sort(
        (a, b) => a.name.localeCompare(b.name) || a.id - b.id,
      ),
    );
  }
}
