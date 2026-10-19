import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { runTone } from './automation-words';
import {
  AutomationsApi,
  type AutomationActionResult,
  type AutomationDryRun,
  type AutomationProblem,
  type AutomationRequest,
} from './automations.api';

/**
 * The dry run of an automation in its editor (ADR-0046): the automation as it stands runs over a chosen record of its
 * form in a transaction that never commits — whether the condition holds and what each action would write, create,
 * run or send to whom. Nothing changes and nothing is sent; the server's messages about the automation go back to the
 * editor, which shows them under the parts to fix.
 */
@Component({
  selector: 'app-automation-dry-run',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTDataSelectComponent,
    SMTTagComponent,
    TranslatePipe,
  ],
  templateUrl: './automation-dry-run.component.html',
  styleUrl: './automation-editor.component.css',
})
export class AutomationDryRunComponent {
  private readonly api = inject(AutomationsApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  /** The live form whose record the run reads. */
  readonly form = input.required<string>();
  /** The automation as the editor holds it. */
  readonly request = input.required<AutomationRequest>();
  /** The server's messages about the automation, by the part they name. */
  readonly problems = output<Record<string, string>>();

  readonly record = signal<number | null>(null);
  readonly result = signal<AutomationDryRun | null>(null);
  readonly busy = signal(false);
  readonly alert = signal('');

  readonly source = computed(() => this.api.records(this.form()));

  readonly tone = runTone;

  constructor() {
    effect(() => {
      this.form();
      untracked(() => {
        this.record.set(null);
        this.result.set(null);
      });
    });
  }

  run(): void {
    const record = this.record();
    if (!record || this.busy()) return;
    this.busy.set(true);
    this.alert.set('');
    this.result.set(null);
    this.api
      .dryRun(record, { ...this.request(), form: this.form() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.problems.emit({});
          this.result.set(result);
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.problems.emit({ ...problemFieldErrors(failure).fields });
          this.alert.set(problemText(failure) || this.i18n.translate('studio.automations.dry_failed'));
        },
      });
  }

  /** A problem of a run as a person reads it. */
  problem(problem: AutomationProblem): string {
    const params = Object.fromEntries(
      Object.entries(problem.params ?? {}).map(([key, value]) => [key, String(value)]),
    ) as Record<string, string>;
    const fields = (problem.fields ?? []).map((item) => `${item.field}: ${this.i18n.translate(item.key)}`);
    return [this.i18n.translate(problem.key, params), ...fields].join('; ');
  }

  /** The values an action writes, as `field = value` pairs. */
  valuesText(values: Record<string, unknown>): string {
    return Object.entries(values)
      .map(([field, value]) => `${field} = ${String(value)}`)
      .join(', ');
  }

  /** Who a notification goes to, each left-out person with the reason. */
  peopleText(result: AutomationActionResult): string {
    this.i18n.currentLang();
    return (result.recipients ?? [])
      .map((person) =>
        person.skipped
          ? `${person.name} (${this.i18n.translate(`studio.automations.skipped.${person.skipped}`)})`
          : person.name,
      )
      .join(', ');
  }
}
