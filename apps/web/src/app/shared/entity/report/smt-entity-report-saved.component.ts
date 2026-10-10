import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
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
  template: `
    <div class="saved" data-testid="report-saved">
      <smt-control class="saved-field" [smtLabel]="'ui.report.saved' | t">
        <smt-select
          [smtTriggerId]="ids + '-pick'"
          [options]="options()"
          [placeholder]="'ui.report.saved_none' | t"
          [value]="activeId()"
          (valueChange)="pick($event)"
        />
      </smt-control>
      @if (active(); as report) {
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          smtIcon="save"
          [smtLoading]="busy()"
          (click)="saveActive(report)"
        >
          {{ 'common.save' | t }}
        </button>
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          [smtIcon]="report.kind === 'widget' ? 'dashboard_customize' : 'dashboard'"
          [attr.aria-pressed]="report.kind === 'widget'"
          data-testid="report-pin"
          (click)="togglePin(report)"
        >
          {{ 'ui.report.on_dashboard' | t }}
        </button>
        <button smt-button type="button" smtVariant="ghost" smtSize="sm" smtIcon="delete" (click)="remove(report)">
          {{ 'common.delete' | t }}
        </button>
      }
      <button
        smt-button
        type="button"
        smtVariant="primary"
        smtSize="sm"
        smtIcon="bookmark_add"
        data-testid="report-save-as"
        (click)="openSaveAs()"
      >
        {{ 'ui.report.save_as' | t }}
      </button>
    </div>

    <smt-dialog
      [open]="saveAsOpen()"
      [smtTitle]="'ui.report.save_as' | t"
      smtSize="sm"
      [dismissible]="!busy()"
      (closed)="closeSaveAs()"
    >
      <ng-template smtDialogContent>
        <form
          class="saved-form"
          [id]="ids + '-form'"
          uiFocusFirstInvalid
          (submit)="$event.preventDefault(); submitSaveAs()"
          novalidate
        >
          @if (saveError(); as text) {
            <div class="saved-alert" role="alert" data-testid="report-save-error">{{ text }}</div>
          }
          <smt-control [smtLabel]="'ui.report.name' | t" [smtError]="error()" [required]="true">
            <smt-input
              smtTestId="report-name"
              smtFocusInitial
              [smtFieldId]="ids + '-name'"
              [maxLength]="nameMax"
              [value]="name()"
              (valueChange)="name.set($event === null ? '' : '' + $event)"
            />
          </smt-control>
          <div smt-checkbox data-testid="report-widget" [checked]="asWidget()" (checkedChange)="asWidget.set($event)">
            {{ 'ui.report.show_on_dashboard' | t }}
          </div>
        </form>
        <ui-form-actions
          footer
          [form]="ids + '-form'"
          data-testid="report-save-as-actions"
          [submitting]="busy()"
          (cancelled)="closeSaveAs()"
        />
      </ng-template>
    </smt-dialog>
  `,
  styles: [
    `
      .saved {
        display: flex;
        flex-wrap: wrap;
        align-items: flex-end;
        gap: 8px;
      }
      .saved-field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        min-width: 220px;
      }
      .saved-form {
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .saved-alert {
        padding: 8px 10px;
        border: 1px solid var(--danger-border);
        border-radius: var(--radius-md);
        background: var(--danger-bg);
        color: var(--danger-text);
        font-size: 13px;
      }
    `,
  ],
})
export class SMTEntityReportSavedComponent {
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly reports = inject(EntityReportsApi);
  private readonly injector = inject(Injector);

  readonly listCode = input.required<string>();
  /** What is on screen now: what a save writes. */
  readonly state = input.required<ReportState>();

  /** A saved report was chosen: the builder shows it. */
  readonly opened = output<ReportState>();

  /** The reports on screen: the read, then the person's saves and deletions over it. */
  readonly saved = linkedSignal<SavedReport[]>(() => (this.list.hasValue() ? this.list.value() : []));
  readonly activeId = signal<number | null>(null);
  readonly busy = signal(false);
  readonly saveAsOpen = signal(false);
  readonly name = signal('');
  readonly asWidget = signal(false);
  /** The error under the name: empty, or the server's word on it. */
  readonly error = signal('');
  /** A refusal of the save that names no field: an alert at the top of the dialog. */
  readonly saveError = signal('');

  readonly active = computed(() => this.saved().find((report) => report.id === this.activeId()) ?? null);
  readonly options = computed<SMTSelectOption<number>[]>(() => {
    this.i18n.currentLang();
    return this.saved().map((report) => ({
      id: report.id,
      label: report.name,
      icon: report.kind === 'widget' ? 'dashboard' : 'summarize',
      subLabel: report.kind === 'widget' ? this.i18n.translate('ui.report.on_dashboard') : undefined,
    }));
  });

  private readonly askDiscard = discardChangesQuestion();

  /** The person's reports of the list, read again when the list changes; a failed read shows none. */
  private readonly list = rxResource({
    params: () => this.listCode(),
    stream: ({ params }) => this.reports.list(params).pipe(catchError(() => of<SavedReport[]>([]))),
  });

  readonly ids = `report-saved-${nextSavedId++}`;
  readonly nameMax = NAME_MAX;

  pick(id: number | null): void {
    this.activeId.set(id);
    const report = this.active();
    if (report) this.opened.emit(report.state);
  }

  openSaveAs(): void {
    this.name.set('');
    this.asWidget.set(false);
    this.error.set('');
    this.saveError.set('');
    this.saveAsOpen.set(true);
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before a typed name is dropped (forms standard, 8). */
  closeSaveAs(): void {
    if (this.busy()) return;
    this.askDiscard(!!this.name().trim() || this.asWidget()).subscribe((discard) => {
      if (discard) this.saveAsOpen.set(false);
    });
  }

  /** Enter and "Save" land here; one request while a save runs; an empty name says so and takes the focus. */
  submitSaveAs(): void {
    if (this.busy()) return;
    const name = this.name().trim();
    this.saveError.set('');
    if (!name) {
      this.error.set(this.i18n.translate('ui.report.name_required'));
      return;
    }
    this.error.set('');
    this.busy.set(true);
    this.reports.save(this.listCode(), name, this.asWidget() ? 'widget' : 'report', this.state()).subscribe({
      next: (report) => {
        this.busy.set(false);
        this.saveAsOpen.set(false);
        this.replace(report);
        this.activeId.set(report.id);
        this.toast.success(this.i18n.translate('ui.report.saved_done'));
      },
      error: (failure: unknown) => {
        this.busy.set(false);
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
    this.write({ ...report, state: this.state() });
  }

  togglePin(report: SavedReport): void {
    this.write({ ...report, kind: report.kind === 'widget' ? 'report' : 'widget' });
  }

  remove(report: SavedReport): void {
    this.modal
      .confirm({ message: this.i18n.translate('ui.report.delete_confirm', { name: report.name }), destructive: true })
      .subscribe((confirmed) => {
        if (!confirmed) return;
        this.reports.remove(this.listCode(), report.id).subscribe({
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
    this.busy.set(true);
    this.reports.update(this.listCode(), report).subscribe({
      next: (written) => {
        this.busy.set(false);
        this.replace(written);
        this.toast.success(this.i18n.translate('ui.report.saved_done'));
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        this.toast.error(problemText(failure) || this.i18n.translate('ui.report.save_failed'));
      },
    });
  }

  private replace(report: SavedReport): void {
    this.saved.update((reports) =>
      [...reports.filter((item) => item.id !== report.id), report].sort(
        (a, b) => a.name.localeCompare(b.name) || a.id - b.id,
      ),
    );
  }
}
