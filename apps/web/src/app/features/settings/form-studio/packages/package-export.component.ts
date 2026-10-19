import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { form, FormField, maxLength, pattern, required } from '@angular/forms/signals';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFormErrorItem, UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { messageForError } from '@shared/ui-kit/components/forms/control/control-messages';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { downloadJson, labelOf } from './package-words';
import {
  PackagesApi,
  type PackageFormSummary,
  type SolutionExportItem,
  type SolutionExportPlan,
  type SolutionExportRequest,
} from './packages.api';

interface ExportForm {
  code: string;
  version: string;
  ru: string;
  uz: string;
  en: string;
  listItems: boolean;
}

const CODE = /^[a-z][a-z0-9-]{1,39}$/;
const VERSION = /^\d{1,4}\.\d{1,4}\.\d{1,6}$/;
const FIELDS: [keyof ExportForm, string, string][] = [
  ['code', 'package-export-code', 'studio.packages.export.code'],
  ['version', 'package-export-version', 'studio.packages.export.version'],
  ['ru', 'package-export-ru', 'settings.form_studio.labels.ru'],
  ['uz', 'package-export-uz', 'settings.form_studio.labels.uz'],
  ['en', 'package-export-en', 'settings.form_studio.labels.en'],
];

/**
 * The export of a solution package (ADR-0047): its code, version and name in ru, uz and en, the live forms it is made
 * of and whether its reference lists carry their items. "Show contents" asks the server what the package will hold —
 * the forms and lists those stand on as dependencies — and what else uses its forms: datasets, reports, mailings and
 * automations to add. The download is the package the server sealed with its checksum.
 */
@Component({
  selector: 'app-package-export',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormField,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTTagComponent,
    TranslatePipe,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    UiFormErrorSummaryComponent,
  ],
  templateUrl: './package-export.component.html',
  styleUrl: './packages.component.css',
})
export class PackageExportComponent implements OnInit {
  private readonly api = inject(PackagesApi);
  private readonly i18n = inject(I18nService);
  private readonly kitI18n = inject(SMTI18nService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  readonly model = signal<ExportForm>({ code: '', version: '1.0.0', ru: '', uz: '', en: '', listItems: true });
  readonly forms = signal<PackageFormSummary[]>([]);
  readonly chosen = signal<readonly string[]>([]);
  /** Items of the plan's related list the administrator added, as kind:key. */
  readonly added = signal<readonly string[]>([]);
  readonly plan = signal<SolutionExportPlan | null>(null);
  readonly planning = signal(false);
  readonly exporting = signal(false);
  readonly selectionError = signal('');
  readonly problems = signal<readonly string[]>([]);

  readonly included = computed(() => this.plan()?.included ?? []);
  readonly related = computed(() => this.plan()?.related ?? []);

  readonly exportForm = form(this.model, (path) => {
    required(path.code);
    pattern(path.code, CODE);
    required(path.version);
    pattern(path.version, VERSION);
    for (const name of [path.ru, path.uz, path.en]) {
      required(name);
      maxLength(name, 80);
    }
  });

  ngOnInit(): void {
    this.api
      .forms()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (page) => this.forms.set(page.items), error: () => this.forms.set([]) });
  }

  title(labels: PackageFormSummary['title']): string {
    return labelOf(labels, this.i18n.currentLang());
  }

  toggleForm(code: string, on: boolean): void {
    this.chosen.update((codes) => (on ? [...codes, code] : codes.filter((known) => known !== code)));
    this.selectionError.set('');
    this.plan.set(null);
  }

  toggleRelated(item: SolutionExportItem, on: boolean): void {
    const key = `${item.kind}:${item.key}`;
    this.added.update((keys) => (on ? [...keys, key] : keys.filter((known) => known !== key)));
  }

  isAdded(item: SolutionExportItem): boolean {
    return this.added().includes(`${item.kind}:${item.key}`);
  }

  /** Why an item is in the package: selected, or what needs it. */
  reason(item: SolutionExportItem): string {
    if (item.reason !== 'dependency' || !item.of) return this.i18n.translate(`studio.packages.reason.${item.reason}`);
    return this.i18n.translate('studio.packages.reason.of', { of: item.of });
  }

  error(field: keyof ExportForm): string {
    const state = this.exportForm[field]();
    return state.touched() ? messageForError(state.errors()[0], this.kitI18n.messages().control) : '';
  }

  summary(): UiFormErrorItem[] {
    return FIELDS.flatMap(([key, fieldId, label]) => {
      const message = this.error(key);
      return message ? [{ fieldId, label: this.i18n.translate(label), message }] : [];
    });
  }

  show(): void {
    const request = this.request();
    if (!request) return;
    this.planning.set(true);
    this.problems.set([]);
    this.api
      .plan(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (plan) => {
          this.planning.set(false);
          this.plan.set(plan);
        },
        error: (failure: unknown) => this.failed(failure, this.planning),
      });
  }

  download(): void {
    if (this.exporting()) return;
    const request = this.request();
    if (!request) return;
    this.exporting.set(true);
    this.problems.set([]);
    this.api
      .export(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (pack) => {
          this.exporting.set(false);
          downloadJson(`${request.code}-${request.version}.json`, JSON.stringify(pack, null, 2));
          this.toast.success(this.i18n.translate('studio.packages.exported'));
        },
        error: (failure: unknown) => this.failed(failure, this.exporting),
      });
  }

  /** The request of the form, or null with the errors shown. */
  private request(): SolutionExportRequest | null {
    markSMTFormFieldsTouched(this.exportForm);
    const valid = FIELDS.every(([key]) => this.exportForm[key]().valid());
    if (!this.chosen().length) this.selectionError.set(this.i18n.translate('studio.packages.export.forms_required'));
    if (!valid || !this.chosen().length) return null;
    const value = this.model();
    const pick = (kind: string) =>
      this.added()
        .filter((key) => key.startsWith(`${kind}:`))
        .map((key) => key.slice(kind.length + 1));
    return {
      code: value.code.trim(),
      version: value.version.trim(),
      title: { ru: value.ru.trim(), uz: value.uz.trim(), en: value.en.trim() },
      forms: [...this.chosen()],
      datasets: pick('dataset'),
      reports: pick('report').map(Number),
      mailings: pick('mailing').map(Number),
      automations: pick('automation').map(Number),
      listItems: value.listItems,
    };
  }

  private failed(failure: unknown, busy: { set(value: boolean): void }): void {
    busy.set(false);
    const fields = problemFieldErrors(failure);
    const messages = [...Object.values(fields.fields), ...fields.other];
    this.problems.set([problemText(failure) || this.i18n.translate('studio.packages.action_failed'), ...messages]);
  }
}
