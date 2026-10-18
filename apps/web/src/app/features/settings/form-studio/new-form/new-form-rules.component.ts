import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { STUDIO_LANGUAGES, type StudioLanguage } from '../form-studio.models';
import type { FormulaDocument } from '../studio-formulas.api';
import { FormulaInputComponent } from './formula-input.component';
import { MAX_RULES, type SimpleField, type SimpleFormRule } from './new-form.models';

/** Characters of a rule's message (server limit). */
export const MAX_RULE_MESSAGE = 200;

/**
 * The validation rules of a live form (ADR-0042): each a yes/no formula every save must not make false — an empty
 * value passes — with its message in three languages and, optionally, the field the problem is shown at. The server
 * checks them on every write path, whatever the client sends.
 */
@Component({
  selector: 'app-new-form-rules',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTSelectComponent,
    FormulaInputComponent,
  ],
  template: `
    <fieldset class="form-rules" data-testid="form-rules">
      <legend>{{ 'studio.rules.title' | t }}</legend>
      <p class="rules-note">{{ 'studio.rules.hint' | t }}</p>
      @for (rule of list(); track $index; let ruleIndex = $index) {
        <div class="form-rule" [attr.data-testid]="'rule-' + ruleIndex">
          <smt-control [smtLabel]="'studio.rules.key' | t" [required]="true" [smtHint]="'studio.rules.key_hint' | t">
            <smt-input
              [value]="rule.key"
              [maxLength]="20"
              [disabled]="disabled()"
              [attr.data-testid]="'rule-key-' + ruleIndex"
              (valueChange)="patch(ruleIndex, { key: text($event) })"
            />
          </smt-control>
          <app-formula-input
            kind="rule"
            [label]="'studio.rules.formula' | t"
            [hint]="'studio.rules.formula_hint' | t"
            [value]="rule.formula"
            [fields]="fields()"
            [document]="document()"
            [disabled]="disabled()"
            [testId]="'rule-formula-' + ruleIndex"
            (valueChange)="patch(ruleIndex, { formula: $event ?? '' })"
          />
          @for (language of languages; track language) {
            <smt-control [smtLabel]="messageKeys[language] | t" [required]="true">
              <smt-input
                [value]="rule.message[language]"
                [maxLength]="maxMessage"
                [disabled]="disabled()"
                [attr.data-testid]="'rule-message-' + language + '-' + ruleIndex"
                (valueChange)="setMessage(ruleIndex, language, $event)"
              />
            </smt-control>
          }
          <smt-control [smtLabel]="'studio.rules.field' | t" [smtHint]="'studio.rules.field_hint' | t">
            <smt-select
              [options]="fieldOptions()"
              [value]="rule.field ?? null"
              [disabled]="disabled()"
              [attr.data-testid]="'rule-field-' + ruleIndex"
              (valueChange)="setField(ruleIndex, $event)"
            />
          </smt-control>
          <div class="rule-actions">
            <button
              smt-button
              type="button"
              smtVariant="ghost"
              smtSize="sm"
              smtIcon="delete"
              [disabled]="disabled()"
              [attr.aria-label]="'studio.rules.remove' | t: { number: ruleIndex + 1 }"
              (click)="remove(ruleIndex)"
            ></button>
          </div>
        </div>
      }
      <div>
        <button
          smt-button
          type="button"
          smtVariant="secondary"
          smtSize="sm"
          data-testid="rule-add"
          [disabled]="disabled() || list().length >= max"
          (click)="add()"
        >
          {{ 'studio.rules.add' | t }}
        </button>
      </div>
    </fieldset>
  `,
  styles: [
    ':host { display: block; min-width: 0; }',
    '.form-rules { display: grid; gap: 14px; min-width: 0; margin: 0; padding: 16px; border: 1px solid var(--border-color); border-radius: 8px; }',
    '.form-rules legend { color: var(--text-main); font-size: 14px; font-weight: 600; }',
    '.form-rule { display: grid; gap: 12px; min-width: 0; padding: 12px; border: 1px dashed var(--border-color); border-radius: 8px; }',
    '.rule-actions { display: flex; justify-content: flex-end; }',
    '.rules-note { margin: 0; color: var(--text-muted); font-size: 13px; }',
  ],
})
export class NewFormRulesComponent {
  private readonly i18n = inject(I18nService);
  readonly rules = input<SimpleFormRule[] | undefined>(undefined);
  /** Every field of the form: a rule reads the shown ones and may be shown at one of them. */
  readonly fields = input<readonly SimpleField[]>([]);
  /** What a rule of a document reads besides the fields: lines, number, status (ADR-0043). */
  readonly document = input<FormulaDocument | null>(null);
  readonly disabled = input(false);
  readonly changed = output<SimpleFormRule[] | undefined>();
  readonly list = computed(() => this.rules() ?? []);
  readonly fieldOptions = computed(() => {
    const language = this.i18n.currentLang();
    return this.fields()
      .filter((field) => !field.hidden && field.id)
      .map((field) => {
        const labels = field.labels as Record<string, string>;
        return { id: field.id, label: `${labels[language] || labels['ru'] || field.id} (${field.id})` };
      });
  });
  readonly languages = STUDIO_LANGUAGES;
  readonly messageKeys: Record<StudioLanguage, string> = {
    ru: 'studio.rules.message_ru',
    uz: 'studio.rules.message_uz',
    en: 'studio.rules.message_en',
  };
  readonly max = MAX_RULES;
  readonly maxMessage = MAX_RULE_MESSAGE;

  add(): void {
    const rules = this.list();
    if (rules.length >= MAX_RULES) return;
    let number = rules.length + 1;
    while (rules.some((rule) => rule.key === `rule${number}`)) number++;
    this.emit([...rules, { key: `rule${number}`, formula: '', message: { ru: '', uz: '', en: '' } }]);
  }

  remove(index: number): void {
    const rules = [...this.list()];
    rules.splice(index, 1);
    this.emit(rules);
  }

  patch(index: number, change: Partial<SimpleFormRule>): void {
    const rules = [...this.list()];
    if (!rules[index]) return;
    rules[index] = { ...rules[index], ...change };
    this.emit(rules);
  }

  setMessage(index: number, language: StudioLanguage, value: unknown): void {
    const rule = this.list()[index];
    if (!rule) return;
    this.patch(index, { message: { ...rule.message, [language]: this.text(value) } });
  }

  setField(index: number, value: unknown): void {
    const rules = [...this.list()];
    if (!rules[index]) return;
    const next = { ...rules[index] };
    if (typeof value === 'string' && value) next.field = value;
    else delete next.field;
    rules[index] = next;
    this.emit(rules);
  }

  text(value: unknown): string {
    return value === null || value === undefined ? '' : String(value);
  }

  private emit(rules: SimpleFormRule[]): void {
    this.changed.emit(rules.length ? rules : undefined);
  }
}
