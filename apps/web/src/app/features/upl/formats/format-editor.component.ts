import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { RecordNavigationDecision, RecordNavigationPage } from '@core/guards/record-navigation.guard';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTDatePickerComponent } from '@shared/ui-kit/components/forms/date-picker';
import { SMTProgressStep, SMTProgressStepperComponent } from '@shared/ui-kit/components/progress-stepper';
import { UPL_FILE_KIND_KEY } from '../upl-labels';
import { FormatEditorStore } from './format-editor.store';
import { FormatFileStepComponent } from './format-file-step.component';
import { FormatPublishStepComponent } from './format-publish-step.component';
import { FormatSheetsStepComponent } from './format-sheets-step.component';
import { UplFieldError, uplErrorAddress, uplFieldErrorText } from './upl-format-errors';
import { uplErrorStep } from './upl-format-model';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-upl-format-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTAlertComponent,
    RouterLink,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTBadgeComponent,
    SMTDatePickerComponent,
    SMTProgressStepperComponent,
    FormatFileStepComponent,
    FormatSheetsStepComponent,
    FormatPublishStepComponent,
  ],
  providers: [FormatEditorStore],
  templateUrl: './format-editor.component.html',
  styleUrl: './format-editor.component.css',
})
export class FormatEditorComponent implements RecordNavigationPage {
  /** The version being edited and its flows; the template reads it directly. */
  readonly store = inject(FormatEditorStore);
  private readonly i18n = inject(I18nService);
  private readonly route = inject(ActivatedRoute);

  readonly isLeaveOpen = signal(false);

  private readonly navigationDecision = new RecordNavigationDecision();

  constructor() {
    this.route.paramMap
      .pipe(takeUntilDestroyed())
      .subscribe((params) => this.store.open(params.get('id') ?? '', params.get('v') ?? ''));
  }

  /** The file format steps with a status: "has errors" by the error addresses, "done" by what is filled in. */
  steps(): SMTProgressStep[] {
    const errors = this.store.errors();
    const fileErrors = errors.filter((item) => uplErrorStep(item) === 'file').length;
    const sheetErrors = errors.length - fileErrors;
    const model = this.store.model();
    const sheets = model.sheets;
    const columns = sheets.reduce((total, sheet) => total + sheet.columns.length, 0);
    const sheetsFilled = sheets.length > 0 && sheets.every((sheet) => sheet.columns.length > 0);
    const status = this.store.version()?.status;
    const statusKey = this.store.statusKey();
    const errorHint = (count: number) => this.text('upl.format.step.errors', { count: count.toString() });
    return [
      {
        id: 'file',
        label: this.text('upl.format.step.file'),
        controls: 'upl-step-file',
        status: fileErrors > 0 ? 'error' : 'complete',
        hint: fileErrors > 0 ? errorHint(fileErrors) : this.text(UPL_FILE_KIND_KEY[model.fileKind ?? 'xlsx']),
      },
      {
        id: 'sheets',
        label: this.text('upl.format.step.sheets'),
        controls: 'upl-step-sheets',
        status: sheetErrors > 0 ? 'error' : sheetsFilled ? 'complete' : 'none',
        hint:
          sheetErrors > 0
            ? errorHint(sheetErrors)
            : this.text('upl.format.step.sheets_hint', {
                sheets: sheets.length.toString(),
                columns: columns.toString(),
              }),
      },
      {
        id: 'publish',
        label: this.text('upl.format.step.publish'),
        controls: 'upl-step-publish',
        status: status === 'draft' || !status ? 'none' : 'complete',
        hint: statusKey ? this.text(statusKey) : undefined,
      },
    ];
  }

  text(key: string, params?: Record<string, string>): string {
    return this.i18n.translate(key, params);
  }

  /** The error address in the summary: with the field name if the table header gives it. */
  errorAddress(problem: UplFieldError): string {
    return uplErrorAddress(problem, (key, params) => this.text(key, params));
  }

  /** An unknown code is not hidden: the server message and the code itself are shown. */
  errorText(problem: UplFieldError): string {
    return uplFieldErrorText(problem, (key) => this.i18n.translate(key));
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (!this.store.isDirty()) return true;
    return this.navigationDecision.request(
      () => this.isLeaveOpen.set(true),
      () => this.isLeaveOpen.set(false),
    );
  }

  settleLeave(allow: boolean): void {
    this.isLeaveOpen.set(false);
    this.navigationDecision.settle(allow);
  }
}
