import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { problemText } from '@shared/ui/problem-text';
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
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTInputComponent,
    SMTSelectComponent,
    TranslatePipe,
  ],
  template: `
    <div class="saved" data-testid="report-saved">
      <div class="saved-field">
        <label [for]="ids + '-pick'">{{ 'ui.report.saved' | t }}</label>
        <smt-select
          [smtTriggerId]="ids + '-pick'"
          [options]="options()"
          [placeholder]="'ui.report.saved_none' | t"
          [value]="activeId()"
          (valueChange)="pick($event)"
        />
      </div>
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
      (closed)="saveAsOpen.set(false)"
    >
      <ng-template smtDialogContent>
        <form body class="saved-form" (submit)="$event.preventDefault(); submitSaveAs()" novalidate>
          <label class="form-label" [for]="ids + '-name'">{{ 'ui.report.name' | t }}</label>
          <smt-input
            smtTestId="report-name"
            [smtFieldId]="ids + '-name'"
            [maxLength]="nameMax"
            [value]="name()"
            (valueChange)="name.set($event === null ? '' : '' + $event)"
            [smtInvalid]="!!error()"
            [smtDescribedBy]="error() ? ids + '-error' : null"
          />
          @if (error(); as text) {
            <span class="saved-error" [id]="ids + '-error'" data-testid="report-name-error">{{ text }}</span>
          }
          <div smt-checkbox data-testid="report-widget" [checked]="asWidget()" (checkedChange)="asWidget.set($event)">
            {{ 'ui.report.show_on_dashboard' | t }}
          </div>
        </form>
        <div footer class="saved-footer">
          <button smt-button type="button" smtVariant="secondary" (click)="saveAsOpen.set(false)">
            {{ 'common.cancel' | t }}
          </button>
          <button
            smt-button
            type="button"
            smtVariant="primary"
            data-testid="report-name-submit"
            [smtLoading]="busy()"
            (click)="submitSaveAs()"
          >
            {{ 'common.save' | t }}
          </button>
        </div>
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
      .saved-field label {
        font-size: 12px;
        font-weight: 500;
        color: var(--text-muted);
      }
      .saved-form {
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .saved-error {
        color: var(--danger-text, var(--danger));
        font-size: 12px;
      }
      .saved-footer {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
      }
    `,
  ],
})
export class SMTEntityReportSavedComponent {
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly reports = inject(EntityReportsApi);

  readonly listCode = input.required<string>();
  /** What is on screen now: what a save writes. */
  readonly state = input.required<ReportState>();
  /** A saved report was chosen: the builder shows it. */
  readonly opened = output<ReportState>();

  readonly saved = signal<SavedReport[]>([]);
  readonly activeId = signal<number | null>(null);
  readonly busy = signal(false);
  readonly saveAsOpen = signal(false);
  readonly name = signal('');
  readonly asWidget = signal(false);
  readonly error = signal('');

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

  readonly ids = `report-saved-${nextSavedId++}`;
  readonly nameMax = NAME_MAX;

  constructor() {
    effect(() => this.load(this.listCode()));
  }

  pick(id: number | null): void {
    this.activeId.set(id);
    const report = this.active();
    if (report) this.opened.emit(report.state);
  }

  openSaveAs(): void {
    this.name.set('');
    this.asWidget.set(false);
    this.error.set('');
    this.saveAsOpen.set(true);
  }

  submitSaveAs(): void {
    const name = this.name().trim();
    if (!name) {
      this.error.set(this.i18n.translate('ui.report.name_required'));
      return;
    }
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
        this.error.set(problemText(failure) || this.i18n.translate('ui.report.save_failed'));
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

  private load(listCode: string): void {
    this.reports.list(listCode).subscribe({
      next: (reports) => this.saved.set(reports),
      error: () => this.saved.set([]),
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
