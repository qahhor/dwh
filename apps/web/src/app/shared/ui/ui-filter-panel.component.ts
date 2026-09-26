import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { fieldLabel, QueryCondition, QueryFieldMeta, QueryListMeta, QueryOp } from '../../core/models/query-meta.models';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import {
  FilterDraft,
  changeField,
  draftError,
  filterableFields,
  fromCondition,
  newDraft,
  opTakesValue,
  toCondition,
} from '../list-views/filter-conditions';
import { SMT_DRAWER_DATA, SMT_DRAWER_REF, SMTDrawerRef } from '../ui-kit/components/drawer';
import { DateRange, SMTDatePickerComponent, SMTDateRangePickerComponent } from '../ui-kit/components/forms/date-picker';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTInputComponent, SMTInputValue } from '../ui-kit/components/forms/input';
import { SMTCheckboxComponent } from '../ui-kit/components/forms/checkbox';
import { SMTSelectComponent, SMTSelectOption } from '../ui-kit/components/forms/select';
import { optionsMemo } from '../ui-kit/components/forms/radio-group/radio-options';

export interface FilterPanelData {
  meta: QueryListMeta;
  conditions: QueryCondition[];
}

let nextPanelId = 0;

/**
 * The filter builder, shown in a drawer beside the list: rows of "field —
 * operation — value", joined with "and". The fields and the operations each
 * one offers come from the list's server metadata (ADR-0016), and the value
 * editor follows the field type: text, number, a date picker, yes/no, a
 * choice or a set of choices. Nothing reaches the list until "Apply"; an
 * incomplete row keeps the panel open, says what is missing and takes focus.
 * The drawer closes with the new conditions, or with nothing on "Cancel".
 */
@Component({
  selector: 'ui-filter-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, SMTCheckboxComponent, SMTSelectComponent, TranslatePipe, SMTButtonComponent, SMTDatePickerComponent, SMTDateRangePickerComponent],
  template: `
    <form class="filter-panel" (submit)="$event.preventDefault(); apply()" novalidate>
      <p class="filter-intro">{{ 'ui.filter.intro' | t }}</p>

      @if (rows().length === 0) {
        <p class="filter-empty" data-testid="filter-empty">{{ 'ui.filter.empty' | t }}</p>
      }

      <ol class="filter-rows">
        @for (row of rows(); track $index; let i = $index) {
          @let field = fieldOf(row);
          @let error = errors()[i];
          <li>
            <fieldset class="filter-row" [class.filter-row--invalid]="error" data-testid="filter-row"
              [attr.aria-describedby]="error ? rowId(i) + '-error' : null">
              <legend class="filter-legend">{{ 'ui.filter.condition_n' | t: { n: i + 1 } }}</legend>
              <div class="filter-controls">
                <div class="filter-control">
                  <label class="filter-label" [for]="rowId(i) + '-field'">{{ 'ui.filter.field' | t }}</label>
                  <smt-select data-testid="filter-field" [smtTriggerId]="rowId(i) + '-field'" [options]="fieldOptions()"
                    [allowClear]="false" [value]="row.field" (valueChange)="setField(i, $event)" />
                </div>
                <div class="filter-control">
                  <label class="filter-label" [for]="rowId(i) + '-op'">{{ 'ui.filter.operation' | t }}</label>
                  <smt-select data-testid="filter-op" [smtTriggerId]="rowId(i) + '-op'" [options]="opOptions(field)"
                    [allowClear]="false" [value]="row.op" (valueChange)="setOp(i, $event)" />
                </div>

                @if (field && takesValue(row.op)) {
                  @switch (editorOf(field, row.op)) {
                    @case ('choice') {
                      <div class="filter-control">
                        <label class="filter-label" [for]="rowId(i) + '-choice'">{{ 'ui.filter.value' | t }}</label>
                        <smt-select data-testid="filter-value" [smtTriggerId]="rowId(i) + '-choice'" [options]="choiceOptions(field)"
                          [placeholder]="'ui.filter.choose' | t" [emptyLabel]="'ui.filter.choose' | t"
                          [value]="row.value || null" (valueChange)="patch(i, { value: $event ?? '' })" />
                      </div>
                    }
                    @case ('choices') {
                      <div class="filter-control filter-choices" role="group" [attr.aria-labelledby]="rowId(i) + '-values'">
                        <span class="filter-label" [id]="rowId(i) + '-values'">{{ 'ui.filter.values' | t }}</span>
                        @for (value of choices(field); track value.value) {
                          <div smt-checkbox class="filter-choice" data-testid="filter-choice" [checked]="row.values.includes(value.value)"
                            (checkedChange)="toggleValue(i, value.value, $event)">{{ value.label }}</div>
                        }
                      </div>
                    }
                    @case ('date') {
                      @if (row.op === 'between') {
                        <div class="filter-control">
                          <span class="filter-label" aria-hidden="true">{{ 'ui.filter.period' | t }}</span>
                          <smt-date-range-picker data-testid="filter-date-range" [smtAriaLabel]="'ui.filter.period' | t"
                            [value]="rangeOf(row)" (valueChange)="patch(i, { value: $event?.from ?? '', valueTo: $event?.to ?? '' })" />
                        </div>
                      } @else {
                        <div class="filter-control">
                          <label class="filter-label" [for]="rowId(i) + '-from'">{{ 'ui.filter.value' | t }}</label>
                          <smt-date-picker data-testid="filter-date" [smtInputId]="rowId(i) + '-from'" [value]="row.value || null"
                            (valueChange)="patch(i, { value: $event ?? '' })" />
                        </div>
                      }
                    }
                    @default {
                      <label class="filter-control">
                        <span class="filter-label">{{ (row.op === 'between' ? 'ui.filter.from' : 'ui.filter.value') | t }}</span>
                        <smt-input smtTestId="filter-value" [type]="field.type === 'number' && row.op !== 'in' ? 'number' : 'text'"
                          [inputmode]="field.type === 'number' ? 'decimal' : null" [value]="row.value"
                          (valueChange)="patch(i, { value: text($event) })" />
                        @if (row.op === 'in') {
                          <span class="filter-hint">{{ 'ui.filter.comma_hint' | t }}</span>
                        }
                      </label>
                      @if (row.op === 'between') {
                        <label class="filter-control">
                          <span class="filter-label">{{ 'ui.filter.to' | t }}</span>
                          <smt-input smtTestId="filter-value-to" [type]="field.type === 'number' ? 'number' : 'text'"
                            [value]="row.valueTo" (valueChange)="patch(i, { valueTo: text($event) })" />
                        </label>
                      }
                    }
                  }
                }
              </div>
              @if (error) {
                <p class="filter-error" [id]="rowId(i) + '-error'" data-testid="filter-error">{{ error | t }}</p>
              }
              <button type="button" class="filter-remove" data-testid="filter-remove"
                [attr.aria-label]="'ui.filter.remove_n' | t: { n: i + 1 }" (click)="remove(i)">
                <span class="material-symbols-outlined" aria-hidden="true">close</span>
              </button>
            </fieldset>
          </li>
        }
      </ol>

      <div class="filter-add">
        <button smt-button type="button" smtVariant="secondary" smtSize="sm" smtIcon="add" data-testid="filter-add" [disabled]="!canAdd()" (click)="add()">
          {{ 'ui.filter.add' | t }}
        </button>
        @if (!canAdd() && rows().length > 0) {
          <span class="filter-hint">{{ 'ui.filter.max' | t: { count: data.meta.maxConditions } }}</span>
        }
      </div>

      <div class="filter-footer">
        <button smt-button type="button" smtVariant="ghost" data-testid="filter-clear" [disabled]="rows().length === 0" (click)="clear()">
          {{ 'ui.filter.clear' | t }}
        </button>
        <span class="filter-spacer"></span>
        <button smt-button type="button" smtVariant="secondary" data-testid="filter-cancel" (click)="cancel()">{{ 'common.cancel' | t }}</button>
        <button smt-button smtVariant="primary" type="submit" data-testid="filter-apply">{{ 'ui.filter.apply' | t }}</button>
      </div>
    </form>
  `,
  styles: [`
    :host { display: block; }
    .filter-panel { display: flex; flex-direction: column; gap: 12px; }
    .filter-intro, .filter-empty { margin: 0; color: var(--text-muted); }
    .filter-rows { display: flex; flex-direction: column; gap: 10px; margin: 0; padding: 0; list-style: none; }
    .filter-row {
      position: relative; margin: 0; padding: 10px 40px 10px 12px; border: 1px solid var(--border-color);
      border-radius: var(--radius-md); background: var(--bg-surface); color: var(--text-main);
    }
    .filter-row--invalid { border-color: var(--danger); }
    .filter-legend { padding: 0 4px; color: var(--text-muted); font-size: 12px; }
    .filter-controls { display: flex; flex-wrap: wrap; gap: 8px; }
    .filter-control { display: flex; flex: 1 1 150px; flex-direction: column; gap: 4px; min-width: 0; }
    .filter-label { color: var(--text-muted); font-size: 12px; }
    .filter-choices { flex-basis: 100%; }
    .filter-choice { display: inline-flex; }
    .filter-hint { color: var(--text-muted); font-size: 12px; }
    .filter-error { margin: 6px 0 0; color: var(--danger-text); font-size: 12px; }
    .filter-remove {
      position: absolute; top: 8px; right: 8px; display: inline-flex; align-items: center; justify-content: center;
      width: 28px; height: 28px; border: none; border-radius: var(--radius-sm); background: none;
      color: var(--text-muted); cursor: pointer;
    }
    .filter-remove:hover { background: var(--bg-hover); color: var(--text-main); }
    .filter-remove:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
    .filter-add { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .filter-footer { display: flex; align-items: center; gap: 8px; padding-top: 12px; border-top: 1px solid var(--border-color); }
    .filter-spacer { flex: 1; }
  `],
})
export class UiFilterPanelComponent {
  readonly data = inject<FilterPanelData>(SMT_DRAWER_DATA);
  private readonly drawer = inject<SMTDrawerRef<QueryCondition[]>>(SMT_DRAWER_REF);
  private readonly i18n = inject(I18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly rows = signal<FilterDraft[]>(this.data.conditions.map(fromCondition));
  /** Shown once "Apply" was pressed, then kept up to date as the rows change. */
  readonly errors = signal<(string | null)[]>([]);

  readonly fields = computed(() => filterableFields(this.data.meta));

  readonly canAdd = computed(() => this.rows().length < this.data.meta.maxConditions && this.fields().length > 0);

  private readonly panelId = `ui-filter-panel-${nextPanelId++}`;
  private checked = false;

  private readonly ranges = new WeakMap<FilterDraft, DateRange | null>();

  private readonly fieldOptionsMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** The language the cached operation and choice lists were translated in. */
  private optionsLang = '';

  private readonly opOptionsCache = new Map<string, SMTSelectOption<QueryOp>[]>();

  private readonly choiceOptionsCache = new Map<string, SMTSelectOption<string>[]>();

  fieldOptions(): SMTSelectOption<string>[] {
    return this.fieldOptionsMemo([this.i18n.currentLang(), this.fields()], () =>
      this.fields().map(field => ({ id: field.key, label: this.fieldLabel(field) }))
    );
  }

  /** The operations a field offers, built once per field and language. */
  opOptions(field: QueryFieldMeta | undefined): SMTSelectOption<QueryOp>[] {
    if (!field) return [];
    return this.cachedOptions(this.opOptionsCache, field.key, () => field.ops.map(op => ({ id: op, label: this.opLabel(op) })));
  }

  choiceOptions(field: QueryFieldMeta): SMTSelectOption<string>[] {
    return this.cachedOptions(this.choiceOptionsCache, field.key, () =>
      this.choices(field).map(choice => ({ id: choice.value, label: choice.label }))
    );
  }

  /** A "between" date row as one period with presets; rows are replaced on change, so each keeps its own value. */
  rangeOf(row: FilterDraft): DateRange | null {
    if (!this.ranges.has(row)) {
      this.ranges.set(row, row.value || row.valueTo ? { from: row.value || null, to: row.valueTo || null } : null);
    }
    return this.ranges.get(row) ?? null;
  }

  rowId(index: number): string {
    return `${this.panelId}-${index}`;
  }

  fieldOf(row: FilterDraft): QueryFieldMeta | undefined {
    return this.data.meta.fields.find(field => field.key === row.field);
  }

  fieldLabel(field: QueryFieldMeta): string {
    return fieldLabel(field, key => this.i18n.translate(key));
  }

  opLabel(op: QueryOp): string {
    return this.i18n.translate(`ui.filter.op.${op}`);
  }

  takesValue(op: QueryOp): boolean {
    return opTakesValue(op);
  }

  editorOf(field: QueryFieldMeta, op: QueryOp): 'choice' | 'choices' | 'date' | 'input' {
    if (field.type === 'enum' || field.type === 'boolean') return op === 'in' ? 'choices' : 'choice';
    if (field.type === 'date' || field.type === 'instant') return 'date';
    return 'input';
  }

  choices(field: QueryFieldMeta): { value: string; label: string }[] {
    if (field.type === 'boolean') {
      return [
        { value: 'true', label: this.i18n.translate('common.yes') },
        { value: 'false', label: this.i18n.translate('common.no') },
      ];
    }
    return field.enumValues.map(value => ({ value, label: field.enumLabelPrefix ? this.i18n.translate(`${field.enumLabelPrefix}${value}`) : value }));
  }

  add(): void {
    const draft = newDraft(this.data.meta);
    if (!draft || !this.canAdd()) return;
    this.rows.update(rows => [...rows, draft]);
    this.recheck();
    this.focusRow(this.rows().length - 1);
  }

  remove(index: number): void {
    this.rows.update(rows => rows.filter((_, i) => i !== index));
    this.recheck();
    const left = this.rows().length;
    if (left > 0) this.focusRow(Math.min(index, left - 1));
    else setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('[data-testid="filter-add"] button')?.focus());
  }

  setField(index: number, key: string | null): void {
    const field = this.data.meta.fields.find(item => item.key === key);
    if (!field) return;
    this.update(index, row => changeField(row, field));
  }

  setOp(index: number, op: QueryOp | null): void {
    if (!op) return;
    this.update(index, row => ({ ...row, op, value: row.op === 'in' || op === 'in' ? '' : row.value, valueTo: '', values: [] }));
  }

  /** A draft keeps what was typed as text; a number field hands over a number or null. */
  text(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  patch(index: number, change: Partial<FilterDraft>): void {
    this.update(index, row => ({ ...row, ...change }));
  }

  toggleValue(index: number, value: string, checked: boolean): void {
    this.update(index, row => ({
      ...row,
      values: checked ? [...row.values.filter(item => item !== value), value] : row.values.filter(item => item !== value),
    }));
  }

  clear(): void {
    this.rows.set([]);
    this.recheck();
  }

  cancel(): void {
    this.drawer.close();
  }

  apply(): void {
    this.checked = true;
    const errors = this.rows().map(row => draftError(row, this.data.meta));
    this.errors.set(errors);
    const first = errors.findIndex(error => error !== null);
    if (first >= 0) {
      this.focusRow(first);
      return;
    }
    this.drawer.close(this.rows().map(row => toCondition(row, this.data.meta)));
  }

  private update(index: number, change: (row: FilterDraft) => FilterDraft): void {
    this.rows.update(rows => rows.map((row, i) => (i === index ? change(row) : row)));
    this.recheck();
  }

  private cachedOptions<T>(cache: Map<string, T>, key: string, build: () => T): T {
    const lang = this.i18n.currentLang();
    if (lang !== this.optionsLang) {
      this.optionsLang = lang;
      this.opOptionsCache.clear();
      this.choiceOptionsCache.clear();
    }
    let options = cache.get(key);
    if (!options) {
      options = build();
      cache.set(key, options);
    }
    return options;
  }

  private recheck(): void {
    if (this.checked) this.errors.set(this.rows().map(row => draftError(row, this.data.meta)));
  }

  /** Focus goes to the row's first control after it renders. */
  private focusRow(index: number): void {
    setTimeout(() => {
      const rows = this.host.nativeElement.querySelectorAll<HTMLElement>('[data-testid="filter-row"]');
      rows[index]?.querySelector<HTMLElement>('.smt-select__trigger, input')?.focus();
    });
  }
}
