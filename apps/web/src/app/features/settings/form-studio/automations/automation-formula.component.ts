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
} from '@angular/core';
import type { Subscription } from 'rxjs';
import { FORMULA_FUNCTIONS } from '@core/formula/formula-functions';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea/textarea.component';
import { AutomationsApi, type AutomationCheck, type TriggerKind } from './automations.api';

/** How long the editor waits after the last key before it asks the server to check the formula. */
export const AUTOMATION_CHECK_DELAY_MS = 400;

/**
 * A formula of an automation (ADR-0046, the language of ADR-0042): a condition over the record — for a change also
 * `old.<field>` and `changed.<field>` — or the value of a field an action writes. Pickers insert a name of the form or a
 * function; the server checks the text against the active version of the form while it is typed and again on save.
 */
@Component({
  selector: 'app-automation-formula',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTControlComponent, SMTSelectComponent, SMTTextareaComponent],
  template: `
    <smt-control [smtLabel]="label()" [smtHint]="hint()" [smtError]="problem()" [required]="required()">
      <smt-textarea
        class="automation-formula-text"
        [value]="value() ?? ''"
        [rows]="2"
        [maxRows]="6"
        [maxLength]="1000"
        [attr.data-testid]="testId() || null"
        (valueChange)="edit($event)"
      />
    </smt-control>
    <div class="automation-formula-pickers">
      <smt-select
        [options]="nameOptions()"
        [value]="namePick()"
        [disabled]="!nameOptions().length"
        [placeholder]="'studio.formula.insert_field' | t"
        [ariaLabel]="('studio.formula.insert_field' | t) + ': ' + label()"
        (valueChange)="insert($event, false)"
      />
      <smt-select
        [options]="functionOptions"
        [value]="functionPick()"
        [placeholder]="'studio.formula.insert_function' | t"
        [ariaLabel]="('studio.formula.insert_function' | t) + ': ' + label()"
        (valueChange)="insert($event, true)"
      />
    </div>
  `,
  styles: [
    ':host { display: grid; gap: 8px; min-width: 0; }',
    '.automation-formula-text textarea { font-family: monospace; }',
    '.automation-formula-pickers { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }',
  ],
})
export class AutomationFormulaComponent {
  private readonly api = inject(AutomationsApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  /** The label, already in the viewer's language. */
  readonly label = input.required<string>();
  /** The live form whose record the formula reads. */
  readonly form = input.required<string>();
  readonly trigger = input.required<TriggerKind>();

  readonly hint = input('');
  readonly required = input(false);
  readonly value = input<string | undefined>(undefined);
  /** The field the formula computes, of `targetForm` (the automation's form when null); none — a condition. */
  readonly targetField = input<string | null>(null);
  readonly targetForm = input<string | null>(null);
  /** The names a picker offers: the form's fields and, for a change, their old values. */
  readonly names = input<readonly SMTSelectOption<string>[]>([]);
  /** The server's message for the formula from the last save. */
  readonly error = input('');
  readonly testId = input('');

  readonly valueChange = output<string | undefined>();

  readonly checked = signal<AutomationCheck | null>(null);
  readonly namePick = signal<string | null>(null);
  readonly functionPick = signal<string | null>(null);

  readonly nameOptions = computed(() => this.names());
  readonly problem = computed(() => {
    this.i18n.currentLang();
    const check = this.checked();
    if (check && !check.valid && check.messageKey) {
      const params: Record<string, string | number> = { position: check.position ?? 1 };
      if (check.name) params['name'] = check.name;
      return this.i18n.translate(check.messageKey, params);
    }
    return this.error();
  });

  readonly functionOptions = FORMULA_FUNCTIONS.map((name) => ({ id: name, label: `${name}()` }));
  private request?: Subscription;

  constructor() {
    effect((onCleanup) => {
      const text = (this.value() ?? '').trim();
      const form = this.form();
      const trigger = this.trigger();
      const field = this.targetField();
      const target = field ? { form: this.targetForm(), field } : null;
      this.request?.unsubscribe();
      if (!text || !form) {
        this.checked.set(null);
        return;
      }
      const timer = setTimeout(() => {
        this.request = this.api.check(form, trigger, text, target).subscribe({
          next: (check) => this.checked.set(check),
          error: () => this.checked.set(null),
        });
      }, AUTOMATION_CHECK_DELAY_MS);
      onCleanup(() => clearTimeout(timer));
    });
    this.destroyRef.onDestroy(() => this.request?.unsubscribe());
  }

  edit(text: string | null | undefined): void {
    const value = text ?? '';
    this.valueChange.emit(value.trim() ? value : undefined);
  }

  /** Appends a name or a function with its opening parenthesis to the formula. */
  insert(choice: unknown, call: boolean): void {
    const pick = call ? this.functionPick : this.namePick;
    pick.set(typeof choice === 'string' ? choice : null);
    queueMicrotask(() => pick.set(null));
    if (typeof choice !== 'string' || !choice) return;
    const current = this.value() ?? '';
    const separator = current && !/[\s(]$/.test(current) ? ' ' : '';
    this.edit(`${current}${separator}${call ? `${choice}(` : choice}`);
  }
}
