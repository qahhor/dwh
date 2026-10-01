import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import {
  fieldLabel,
  QueryCondition,
  QueryFieldMeta,
  QueryListMeta,
  QueryMatch,
  QueryOp,
} from '@core/models/query-meta.models';
import { RefLookups } from '../lookups/ref-lookup';
import { SMTDataSelectComponent } from '../ui-kit/components/forms/data-select/data-select.component';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import { SMTRadioGroupComponent, SMTRadioOption } from '../ui-kit/components/forms/radio-group';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
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
import { SMTTimePickerComponent } from '../ui-kit/components/forms/time-picker';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTInputComponent, SMTInputValue } from '../ui-kit/components/forms/input';
import { SMTCheckboxComponent } from '../ui-kit/components/forms/checkbox';
import { SMTSelectComponent, SMTSelectOption } from '../ui-kit/components/forms/select';
import { optionsMemo } from '../ui-kit/components/forms/radio-group/radio-options';

export interface FilterPanelData {
  meta: QueryListMeta;
  conditions: QueryCondition[];
  /** How the conditions combine; `all` when not given. */
  match?: QueryMatch;
}

/** What the panel closes with on "Apply". */
export interface FilterPanelResult {
  conditions: QueryCondition[];
  match: QueryMatch;
}

let nextPanelId = 0;

/**
 * The filter builder, shown in a drawer beside the list: rows of "field —
 * operation — value", joined with "and" or, when chosen, "or". The fields and the operations each
 * one offers come from the list's server metadata (ADR-0016), and the value
 * editor follows the field type: text, number, a date picker, yes/no, a
 * choice or a set of choices. Nothing reaches the list until "Apply"; an
 * incomplete row keeps the panel open, says what is missing and takes focus.
 * The drawer closes with the new conditions, or with nothing on "Cancel".
 */
@Component({
  selector: 'ui-filter-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTCheckboxComponent,
    SMTSelectComponent,
    TranslatePipe,
    SMTButtonComponent,
    SMTDatePickerComponent,
    SMTDateRangePickerComponent,
    SMTDataSelectComponent,
    SMTRadioGroupComponent,
    SMTTimePickerComponent,
  ],
  templateUrl: './ui-filter-panel.component.html',
  styleUrl: './ui-filter-panel.component.css',
})
export class UiFilterPanelComponent {
  readonly data = inject<FilterPanelData>(SMT_DRAWER_DATA);
  private readonly drawer = inject<SMTDrawerRef<FilterPanelResult>>(SMT_DRAWER_REF);
  private readonly i18n = inject(I18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly refLookups = inject(RefLookups);

  readonly rows = signal<FilterDraft[]>(this.data.conditions.map(fromCondition));
  /** How the rows combine; offered once there are two of them. */
  readonly match = signal<QueryMatch>(this.data.match ?? 'all');
  /** Shown once "Apply" was pressed, then kept up to date as the rows change. */
  readonly errors = signal<(string | null)[]>([]);

  readonly fields = computed(() => filterableFields(this.data.meta));

  readonly canAdd = computed(() => this.rows().length < this.data.meta.maxConditions && this.fields().length > 0);

  protected readonly String = String;
  private readonly refSources = new Map<string, SMTLookupSource<Record<string, unknown>, SMTLookupKey>>();
  private readonly matchMemo = optionsMemo<SMTRadioOption<QueryMatch>[]>();

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
      this.fields().map((field) => ({ id: field.key, label: this.fieldLabel(field) })),
    );
  }

  /** The operations a field offers, built once per field and language. */
  opOptions(field: QueryFieldMeta | undefined): SMTSelectOption<QueryOp>[] {
    if (!field) return [];
    return this.cachedOptions(this.opOptionsCache, field.key, () =>
      field.ops.map((op) => ({ id: op, label: this.opLabel(op) })),
    );
  }

  choiceOptions(field: QueryFieldMeta): SMTSelectOption<string>[] {
    return this.cachedOptions(this.choiceOptionsCache, field.key, () =>
      this.choices(field).map((choice) => ({ id: choice.value, label: choice.label })),
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
    return this.data.meta.fields.find((field) => field.key === row.field);
  }

  fieldLabel(field: QueryFieldMeta): string {
    return fieldLabel(field, (key) => this.i18n.translate(key));
  }

  opLabel(op: QueryOp): string {
    return this.i18n.translate(`ui.filter.op.${op}`);
  }

  takesValue(op: QueryOp): boolean {
    return opTakesValue(op);
  }

  editorOf(field: QueryFieldMeta, op: QueryOp): 'ref' | 'choice' | 'choices' | 'date' | 'time' | 'input' {
    if (field.ref && (op === 'eq' || op === 'ne')) return 'ref';
    if (field.type === 'enum' || field.type === 'boolean') return op === 'in' ? 'choices' : 'choice';
    if (field.type === 'date' || field.type === 'instant') return 'date';
    // A time of day is picked as one (plan 10/10, item 5.0); "in" stays a comma-separated list.
    if (field.type === 'time' && op !== 'in') return 'time';
    return 'input';
  }

  choices(field: QueryFieldMeta): { value: string; label: string }[] {
    if (field.type === 'boolean') {
      return [
        { value: 'true', label: this.i18n.translate('common.yes') },
        { value: 'false', label: this.i18n.translate('common.no') },
      ];
    }
    return field.enumValues.map((value) => ({
      value,
      label: field.enumLabelPrefix ? this.i18n.translate(`${field.enumLabelPrefix}${value}`) : value,
    }));
  }

  matchOptions(): SMTRadioOption<QueryMatch>[] {
    return this.matchMemo([this.i18n.currentLang()], () => [
      { value: 'all', label: this.i18n.translate('ui.filter.match_all') },
      { value: 'any', label: this.i18n.translate('ui.filter.match_any') },
    ]);
  }

  /** One lookup per reference field, so its loaded rows survive re-rendering. */
  refSource(field: QueryFieldMeta): SMTLookupSource<Record<string, unknown>, SMTLookupKey> {
    let source = this.refSources.get(field.key);
    if (!source) {
      source = this.refLookups.source(field.ref!);
      this.refSources.set(field.key, source);
    }
    return source;
  }

  /** The stored text value as the key the data select compares: a number field keeps a number. */
  refValue(row: FilterDraft): SMTLookupKey | null {
    if (row.value === '') return null;
    const field = this.fieldOf(row);
    return field?.type === 'number' && Number.isFinite(Number(row.value)) ? Number(row.value) : row.value;
  }

  refLabel(field: QueryFieldMeta, row: Record<string, unknown> | null): string | undefined {
    return row ? String(row[field.ref!.labelField] ?? '') || undefined : undefined;
  }

  add(): void {
    const draft = newDraft(this.data.meta);
    if (!draft || !this.canAdd()) return;
    this.rows.update((rows) => [...rows, draft]);
    this.recheck();
    this.focusRow(this.rows().length - 1);
  }

  remove(index: number): void {
    this.rows.update((rows) => rows.filter((_, i) => i !== index));
    this.recheck();
    const left = this.rows().length;
    if (left > 0) this.focusRow(Math.min(index, left - 1));
    else
      setTimeout(() =>
        this.host.nativeElement.querySelector<HTMLElement>('[data-testid="filter-add"] button')?.focus(),
      );
  }

  setField(index: number, key: string | null): void {
    const field = this.data.meta.fields.find((item) => item.key === key);
    if (!field) return;
    this.update(index, (row) => changeField(row, field));
  }

  setOp(index: number, op: QueryOp | null): void {
    if (!op) return;
    this.update(index, (row) => ({
      ...row,
      op,
      value: row.op === 'in' || op === 'in' ? '' : row.value,
      valueTo: '',
      values: [],
    }));
  }

  /** A draft keeps what was typed as text; a number field hands over a number or null. */
  text(value: SMTInputValue): string {
    return value === null ? '' : String(value);
  }

  patch(index: number, change: Partial<FilterDraft>): void {
    this.update(index, (row) => ({ ...row, ...change }));
  }

  toggleValue(index: number, value: string, checked: boolean): void {
    this.update(index, (row) => ({
      ...row,
      values: checked
        ? [...row.values.filter((item) => item !== value), value]
        : row.values.filter((item) => item !== value),
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
    const errors = this.rows().map((row) => draftError(row, this.data.meta));
    this.errors.set(errors);
    const first = errors.findIndex((error) => error !== null);
    if (first >= 0) {
      this.focusRow(first);
      return;
    }
    this.drawer.close({ conditions: this.rows().map((row) => toCondition(row, this.data.meta)), match: this.match() });
  }

  private update(index: number, change: (row: FilterDraft) => FilterDraft): void {
    this.rows.update((rows) => rows.map((row, i) => (i === index ? change(row) : row)));
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
    if (this.checked) this.errors.set(this.rows().map((row) => draftError(row, this.data.meta)));
  }

  /** Focus goes to the row's first control after it renders. */
  private focusRow(index: number): void {
    setTimeout(() => {
      const rows = this.host.nativeElement.querySelectorAll<HTMLElement>('[data-testid="filter-row"]');
      rows[index]?.querySelector<HTMLElement>('.smt-select__trigger, input')?.focus();
    });
  }
}
