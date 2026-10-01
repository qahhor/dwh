import { booleanAttribute, ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import type { MoneyValue } from '@core/services/field-values';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '../ui-kit/components/forms/control/control.component';
import { SMTSelectComponent, type SMTSelectOption } from '../ui-kit/components/forms/select/select.component';

let nextMoneyId = 0;

/**
 * A money field (ADR-0032 4.1, plan 10/10, item 5.2): the amount as typed — never a floating point number — and its
 * currency, picked from the field's currencies. The label names the amount; the currency list is a part of the field
 * with its own name, so a screen reader reads "Amount, edit" and then "Currency".
 */
@Component({
  selector: 'smt-money-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTControlComponent, SMTSelectComponent, TranslatePipe],
  host: { class: 'smt-money-field' },
  template: `
    <smt-control [smtLabel]="label()" [required]="required()" [smtError]="error()">
      <div class="money-row">
        <input
          class="form-input money-amount"
          type="text"
          inputmode="decimal"
          autocomplete="off"
          [id]="fieldId"
          [required]="required()"
          [disabled]="disabled()"
          [value]="amountText()"
          (input)="setAmount($event)"
        />
        <smt-select
          class="money-currency"
          data-smt-field-part
          [ariaLabel]="'ui.entity_form.currency' | t"
          [options]="currencyOptions()"
          [allowClear]="false"
          [disabled]="disabled() || currencyOptions().length < 2"
          [value]="currency()"
          (valueChange)="setCurrency($event)"
        />
      </div>
    </smt-control>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .money-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 7rem;
        gap: 8px;
      }
      .money-amount {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
})
export class SMTMoneyFieldComponent {
  readonly label = input.required<string>();

  /** The ISO 4217 codes the field takes, the first offered first. */
  readonly currencies = input<readonly string[]>([]);

  readonly required = input(false, { transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly error = input('');

  /** The money as the form edits it, `{amount, currency}`, or null. */
  readonly value = model<MoneyValue | null>(null);

  readonly currencyOptions = computed<SMTSelectOption<string>[]>(() =>
    this.currencies().map((code) => ({ id: code, label: code })),
  );

  readonly amountText = computed(() => {
    const amount = this.value()?.amount;
    return amount === null || amount === undefined ? '' : String(amount);
  });

  readonly currency = computed(() => this.value()?.currency || this.currencies()[0] || null);

  readonly fieldId = `smt-money-field-${nextMoneyId++}`;

  /** A comma is a decimal point too: people type `1250,50`. */
  setAmount(event: Event): void {
    const typed = (event.target as HTMLInputElement).value.replace(',', '.').replace(/\s/g, '');
    this.value.set({ amount: typed === '' ? null : typed, currency: this.currency() ?? '' });
  }

  setCurrency(code: string | null): void {
    if (!code) return;
    this.value.set({ amount: this.value()?.amount ?? null, currency: code });
  }
}
