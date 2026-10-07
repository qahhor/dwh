import { booleanAttribute, ChangeDetectionStrategy, Component, computed, inject, input, model } from '@angular/core';
import type { FormCollectionMeta, FormFieldMeta, FormProblems, FormValues } from '@core/models/form-meta.models';
import type { MoneyValue } from '@core/services/field-values';
import { fieldLabel, optionLabel, rowMoney, rowValues } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '../lookups/ref-lookup';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTControlComponent } from '../ui-kit/components/forms/control/control.component';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import { SMTDynamicFieldComponent, type SMTDynamicFieldDef } from '../ui-kit/components/forms/dynamic-field';
import { ENTITY_CONTROLS } from './smt-entity-form.component';
import { fieldText } from './entity-values';
import { SMTMoneyFieldComponent } from './smt-money-field.component';

interface DrawnRowField {
  meta: FormFieldMeta;
  label: string;
  def: SMTDynamicFieldDef;
  source: SMTLookupSource<unknown, SMTLookupKey> | null;
}

let nextLinesId = 0;

/**
 * The rows of a document on its form (ADR-0032 9.1, plan 10/10, item 5.7), drawn from the collection of `form-meta`:
 * each row a group of the controls of its fields, with buttons to move it up or down and to remove it, and a button
 * that adds a row. A row's problem shows under its control by the address the server gives it (`lines[3].qty`), the
 * collection's own problem under the rows. Money in the document's currency shows that currency and offers no other;
 * a computed value is the server's, shown once the record is saved. The rows are the form's values under the
 * collection's key (`recordValues`), sent with the record (`recordPayload`).
 */
@Component({
  selector: 'smt-entity-lines',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, SMTControlComponent, SMTDynamicFieldComponent, SMTMoneyFieldComponent, TranslatePipe],
  host: { class: 'smt-entity-lines', '[attr.data-collection]': 'collection().key' },
  template: `
    <section class="entity-lines" [attr.aria-labelledby]="headingId">
      <h2 class="entity-lines-title" [id]="headingId">{{ title() }}</h2>
      @if (rows().length === 0) {
        <p class="entity-lines-empty">{{ 'ui.entity_lines.empty' | t }}</p>
      }
      <ol class="entity-lines-list">
        @for (row of rows(); track $index; let index = $index; let last = $last; let first = $first) {
          <li class="entity-line" [attr.data-line]="index">
            <fieldset class="entity-line-fieldset">
              <legend class="entity-line-title">{{ 'ui.entity_lines.line' | t: { n: index + 1 } }}</legend>
              <div class="entity-line-grid">
                @for (field of drawn(); track field.meta.key) {
                  <div class="entity-line-field" [attr.data-field]="field.meta.key">
                    @if (field.meta.computed) {
                      <smt-control [smtLabel]="field.label">
                        <output class="entity-line-computed">{{ computedText(field.meta, row) }}</output>
                      </smt-control>
                    } @else if (field.meta.type === 'money') {
                      <smt-money-field
                        [label]="field.label"
                        [currencies]="currenciesOf(field.meta)"
                        [required]="field.meta.required"
                        [disabled]="locked()"
                        [error]="problemOf(index, field.meta.key)"
                        [value]="moneyOf(field.meta, row)"
                        (valueChange)="set(index, field.meta.key, $event)"
                      />
                    } @else {
                      <smt-dynamic-field
                        [field]="field.def"
                        [source]="field.source"
                        [disabled]="locked()"
                        [error]="problemOf(index, field.meta.key)"
                        [value]="row[field.meta.key] ?? null"
                        (valueChange)="set(index, field.meta.key, $event)"
                      />
                    }
                  </div>
                }
              </div>
              @if (rowProblem(index); as problem) {
                <p class="entity-line-problem" role="alert">{{ problem }}</p>
              }
              @if (!locked()) {
                <div class="entity-line-actions">
                  <button
                    smt-button
                    type="button"
                    smtVariant="ghost"
                    smtSize="sm"
                    smtIconOnly
                    smtIcon="arrow_upward"
                    data-testid="entity-line-up"
                    [disabled]="first"
                    [attr.aria-label]="'ui.entity_lines.move_up' | t: { n: index + 1 }"
                    (click)="move(index, -1)"
                  ></button>
                  <button
                    smt-button
                    type="button"
                    smtVariant="ghost"
                    smtSize="sm"
                    smtIconOnly
                    smtIcon="arrow_downward"
                    data-testid="entity-line-down"
                    [disabled]="last"
                    [attr.aria-label]="'ui.entity_lines.move_down' | t: { n: index + 1 }"
                    (click)="move(index, 1)"
                  ></button>
                  <button
                    smt-button
                    type="button"
                    smtVariant="ghost"
                    smtSize="sm"
                    smtIconOnly
                    smtIcon="delete"
                    data-testid="entity-line-remove"
                    [attr.aria-label]="'ui.entity_lines.remove' | t: { n: index + 1 }"
                    (click)="remove(index)"
                  ></button>
                </div>
              }
            </fieldset>
          </li>
        }
      </ol>
      @if (collectionProblem(); as problem) {
        <p class="entity-lines-problem" role="alert" data-testid="entity-lines-problem">{{ problem }}</p>
      }
      @if (!locked()) {
        <div>
          <button
            smt-button
            type="button"
            smtVariant="secondary"
            smtIcon="add"
            data-testid="entity-line-add"
            [disabled]="rows().length >= collection().maxRows"
            (click)="add()"
          >
            {{ 'ui.entity_lines.add' | t }}
          </button>
        </div>
      }
    </section>
  `,
  styleUrl: './smt-entity-lines.component.css',
})
export class SMTEntityLinesComponent {
  private readonly i18n = inject(I18nService);

  private readonly refLookups = inject(RefLookups);

  readonly collection = input.required<FormCollectionMeta>();

  /** The record's values on the form: the currency of a row's money is the document's (`currencyFrom`). */
  readonly values = input<FormValues>({});

  /** Problems by address (`lines[3].qty`, `lines`), from `formProblems` or the server's answer. */
  readonly problems = input<FormProblems>({});

  readonly disabled = input(false, { transform: booleanAttribute });

  /** The rows cannot be changed now: a state of the process locks them (ADR-0032 9.2). */
  readonly readonly = input(false, { transform: booleanAttribute });

  /** The rows as the form edits them. */
  readonly rows = model<FormValues[]>([]);

  readonly title = computed(() => {
    this.i18n.currentLang();
    return this.i18n.translate(this.collection().labelKey);
  });

  readonly locked = computed(() => this.disabled() || this.readonly());

  /** The fields of a row with their controls, rebuilt when the collection or the language changes. */
  readonly drawn = computed<DrawnRowField[]>(() => {
    this.i18n.currentLang();
    const translate = (key: string) => this.i18n.translate(key);
    return this.collection().fields.map((field) => {
      const label = fieldLabel(field, translate);
      const control = ENTITY_CONTROLS[field.type];
      const def: SMTDynamicFieldDef = {
        code: field.key,
        label,
        type:
          control === 'markdown' || control === 'money' || control === 'multi_ref' || control === 'file'
            ? 'string'
            : control === 'json'
              ? 'text'
              : control,
        required: field.required,
        maxLength: field.maxLength ?? null,
        options:
          field.type === 'select' || field.type === 'enum'
            ? (field.options ?? []).map((option) => ({
                id: option,
                label: field.optionLabels?.[option] ?? optionLabel(field, option, translate),
              }))
            : undefined,
      };
      return { meta: field, label, def, source: field.ref ? this.refLookups.source(field.ref) : null };
    });
  });

  readonly collectionProblem = computed(() => this.problems()[this.collection().key] ?? '');

  readonly headingId = `smt-entity-lines-${nextLinesId++}`;

  problemOf(index: number, key: string): string {
    return this.problems()[`${this.collection().key}[${index}].${key}`] ?? '';
  }

  rowProblem(index: number): string {
    return this.problems()[`${this.collection().key}[${index}]`] ?? '';
  }

  /** The currencies a row's money is offered in: the document's alone when it takes the document's. */
  currenciesOf(field: FormFieldMeta): readonly string[] {
    if (!field.currencyFrom) return field.currencies ?? [];
    const currency = this.values()[field.currencyFrom];
    return typeof currency === 'string' && currency !== '' ? [currency] : (field.currencies ?? []);
  }

  moneyOf(field: FormFieldMeta, row: FormValues): MoneyValue | null {
    return (rowMoney(field, row[field.key], this.values()) as MoneyValue | null) ?? null;
  }

  /** A computed value of a saved row in words; a dash until the server computes it. */
  computedText(field: FormFieldMeta, row: FormValues): string {
    const value = row[field.key];
    if (value === null || value === undefined || value === '') return '—';
    return fieldText(field, rowMoney(field, value, this.values()), (key) => this.i18n.translate(key), this.refLookups);
  }

  set(index: number, key: string, value: unknown): void {
    this.rows.update((rows) => rows.map((row, at) => (at === index ? { ...row, [key]: value } : row)));
  }

  add(): void {
    if (this.rows().length >= this.collection().maxRows) return;
    this.rows.update((rows) => [...rows, rowValues(this.collection(), null)]);
  }

  remove(index: number): void {
    this.rows.update((rows) => rows.filter((_row, at) => at !== index));
  }

  /** Moves a row one place up (-1) or down (1). */
  move(index: number, step: -1 | 1): void {
    const target = index + step;
    this.rows.update((rows) => {
      if (target < 0 || target >= rows.length) return rows;
      const moved = [...rows];
      [moved[index], moved[target]] = [moved[target], moved[index]];
      return moved;
    });
  }
}
