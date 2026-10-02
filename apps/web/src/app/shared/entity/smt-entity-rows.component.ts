import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import type { FormCollectionMeta, FormFieldMeta } from '@core/models/form-meta.models';
import { fieldLabel, rowMoney } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '../lookups/ref-lookup';
import { fieldText } from './entity-values';

interface ShownRow {
  key: string;
  cells: { key: string; text: string; numeric: boolean }[];
}

/**
 * The rows of a document on its card (ADR-0032 9.1 and 9.3, plan 10/10, item 5.7): a table with a column per field of
 * a row, each value in words as the card shows it — money in its currency, a computed amount as the server computed
 * it — and the row's place first.
 */
@Component({
  selector: 'smt-entity-rows',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  host: { class: 'smt-entity-rows', '[attr.data-collection]': 'collection().key' },
  template: `
    @if (shown().length === 0) {
      <p class="entity-rows-empty">{{ 'ui.entity_lines.empty' | t }}</p>
    } @else {
      <div class="entity-rows-scroll">
        <table class="entity-rows">
          <caption class="sr-only">
            {{
              title()
            }}
          </caption>
          <thead>
            <tr>
              <th scope="col" class="entity-rows-number">{{ 'ui.entity_lines.number' | t }}</th>
              @for (field of collection().fields; track field.key) {
                <th scope="col" [class.entity-rows-numeric]="numeric(field)">{{ label(field) }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (row of shown(); track row.key; let index = $index) {
              <tr [attr.data-line]="index">
                <td class="entity-rows-number">{{ index + 1 }}</td>
                @for (cell of row.cells; track cell.key) {
                  <td [attr.data-field]="cell.key" [class.entity-rows-numeric]="cell.numeric">{{ cell.text }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .entity-rows-empty {
        margin: 0;
        color: var(--text-secondary, inherit);
      }
      .entity-rows-scroll {
        overflow-x: auto;
      }
      .entity-rows {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.8125rem;
      }
      th,
      td {
        text-align: left;
        padding: 6px 8px;
        border-bottom: 1px solid var(--border-color, currentColor);
      }
      th {
        font-weight: 600;
      }
      .entity-rows-number {
        width: 3rem;
      }
      .entity-rows-numeric {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
})
export class SMTEntityRowsComponent {
  private readonly i18n = inject(I18nService);

  private readonly refLookups = inject(RefLookups);

  readonly collection = input.required<FormCollectionMeta>();

  /** The rows as the record holds them. */
  readonly rows = input<readonly Record<string, unknown>[]>([]);

  /** The record: the currency of a row's money is the document's (`currencyFrom`). */
  readonly record = input<Record<string, unknown>>({});

  readonly title = computed(() => {
    this.i18n.currentLang();
    return this.i18n.translate(this.collection().labelKey);
  });

  readonly shown = computed<ShownRow[]>(() => {
    this.i18n.currentLang();
    const translate = (key: string) => this.i18n.translate(key);
    const record = this.record();
    return this.rows().map((row, index) => ({
      key: String(row['id'] ?? `new-${index}`),
      cells: this.collection().fields.map((field) => {
        const value = row[field.key];
        const empty = value === null || value === undefined || value === '';
        return {
          key: field.key,
          text: empty ? '—' : fieldText(field, rowMoney(field, value, record), translate, this.refLookups),
          numeric: this.numeric(field),
        };
      }),
    }));
  });

  label(field: FormFieldMeta): string {
    this.i18n.currentLang();
    return fieldLabel(field, (key) => this.i18n.translate(key));
  }

  numeric(field: FormFieldMeta): boolean {
    return field.type === 'number' || field.type === 'money';
  }
}
