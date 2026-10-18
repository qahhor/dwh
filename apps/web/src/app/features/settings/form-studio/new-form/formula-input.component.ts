import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FORMULA_FUNCTIONS } from '@core/formula/formula-functions';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea/textarea.component';
import type { Subscription } from 'rxjs';
import { StudioFormulasApi, type FormulaCheck, type FormulaKind } from '../studio-formulas.api';
import type { SimpleField } from './new-form.models';

/** How long the editor waits after the last key before it asks the server to check the formula. */
export const FORMULA_CHECK_DELAY_MS = 400;

/** Characters of one formula (server limit). */
export const MAX_FORMULA_LENGTH = 1000;

/**
 * A formula of a live form in the editor (ADR-0042): the text, a picker that inserts a field of the form and one that
 * inserts a function, and the server's check of the text while it is typed — its problem with the position it is at
 * shown under the field. The server checks every formula again when the form is prepared and activated.
 */
@Component({
  selector: 'app-formula-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTControlComponent, SMTSelectComponent, SMTTextareaComponent],
  template: `
    <smt-control [smtLabel]="label()" [smtHint]="hint()" [smtError]="problem()">
      <smt-textarea
        class="formula-text"
        [name]="name()"
        [value]="value() ?? ''"
        [rows]="2"
        [maxRows]="6"
        [maxLength]="maxLength"
        [disabled]="disabled()"
        [attr.data-testid]="testId() || null"
        (valueChange)="edit($event)"
      />
    </smt-control>
    <div class="formula-pickers">
      <smt-select
        [options]="fieldOptions()"
        [value]="fieldPick()"
        [disabled]="disabled() || !fieldOptions().length"
        [placeholder]="'studio.formula.insert_field' | t"
        [ariaLabel]="('studio.formula.insert_field' | t) + ': ' + label()"
        (valueChange)="insert($event, false)"
      />
      <smt-select
        [options]="functionOptions"
        [value]="functionPick()"
        [disabled]="disabled()"
        [placeholder]="'studio.formula.insert_function' | t"
        [ariaLabel]="('studio.formula.insert_function' | t) + ': ' + label()"
        (valueChange)="insert($event, true)"
      />
    </div>
  `,
  styles: [
    ':host { display: grid; gap: 8px; min-width: 0; }',
    '.formula-text textarea { font-family: monospace; }',
    '.formula-pickers { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }',
  ],
})
export class FormulaInputComponent {
  private readonly api = inject(StudioFormulasApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  /** The label, already in the viewer's language. */
  readonly label = input.required<string>();
  /** Where the formula stands: it decides the type the server expects. */
  readonly kind = input.required<FormulaKind>();
  readonly hint = input('');
  readonly value = input<string | undefined>(undefined);
  /** The field the formula belongs to; none for a rule. */
  readonly field = input<string | null>(null);
  /** Every field of the form: the shown ones are offered and checked against. */
  readonly fields = input<readonly SimpleField[]>([]);
  readonly disabled = input(false);
  readonly name = input('');
  readonly testId = input('');
  readonly valueChange = output<string | undefined>();
  readonly checked = signal<FormulaCheck | null>(null);
  /** The pickers return to their placeholder after each insertion. */
  readonly fieldPick = signal<string | null>(null);
  readonly functionPick = signal<string | null>(null);
  readonly problem = computed(() => {
    this.i18n.currentLang();
    const check = this.checked();
    if (!check || check.valid || !check.messageKey) return '';
    const params: Record<string, string | number> = { position: check.position ?? 1 };
    if (check.name) params['name'] = check.name;
    return this.i18n.translate(check.messageKey, params);
  });
  readonly fieldOptions = computed(() => {
    const language = this.i18n.currentLang();
    const own = this.field();
    return this.fields()
      .filter((field) => !field.hidden && field.id && (this.kind() !== 'visibleIf' || field.id !== own))
      .map((field) => {
        const labels = field.labels as Record<string, string>;
        const name = labels[language] || labels['ru'] || field.id;
        return { id: field.id, label: `${name} (${field.id})` };
      });
  });
  readonly functionOptions = FORMULA_FUNCTIONS.map((name) => ({ id: name, label: `${name}()` }));
  readonly maxLength = MAX_FORMULA_LENGTH;
  private request?: Subscription;

  constructor() {
    effect((onCleanup) => {
      const text = (this.value() ?? '').trim();
      const fields = this.fields()
        .filter((field) => !field.hidden && field.id)
        .map((field) => ({ id: field.id, type: field.type, ...(field.target ? { target: field.target.kind } : {}) }));
      const kind = this.kind();
      const owner = this.field();
      this.request?.unsubscribe();
      if (!text) {
        this.checked.set(null);
        return;
      }
      const timer = setTimeout(() => {
        this.request = this.api.check(fields, text, kind, owner).subscribe({
          next: (check) => this.checked.set(check),
          error: () => this.checked.set(null),
        });
      }, FORMULA_CHECK_DELAY_MS);
      onCleanup(() => clearTimeout(timer));
    });
    this.destroyRef.onDestroy(() => this.request?.unsubscribe());
  }

  edit(text: string | null | undefined): void {
    const value = text ?? '';
    this.valueChange.emit(value.trim() ? value : undefined);
  }

  /** Appends a field's key or a function's name with its opening parenthesis to the formula. */
  insert(choice: unknown, call: boolean): void {
    const pick = call ? this.functionPick : this.fieldPick;
    pick.set(typeof choice === 'string' ? choice : null);
    queueMicrotask(() => pick.set(null));
    if (typeof choice !== 'string' || !choice) return;
    const current = this.value() ?? '';
    const separator = current && !/[\s(]$/.test(current) ? ' ' : '';
    this.edit(`${current}${separator}${call ? `${choice}(` : choice}`);
  }
}
