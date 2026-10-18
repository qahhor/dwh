import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import type { StudioLanguage } from '../form-studio.models';
import type { FormulaDocument } from '../studio-formulas.api';
import type { StudioTarget } from '../studio-targets.store';
import { NewFormFieldComponent } from './new-form-field.component';
import { NewFormLabelsComponent } from './new-form-labels.component';
import {
  MAX_LINE_COLUMNS,
  MAX_LINE_ROWS,
  MAX_LINE_TABLES,
  type SimpleField,
  type SimpleFormLines,
  type SimpleLineField,
} from './new-form.models';

/** A column as the field editor edits it: a field that is always listed. */
export function columnField(column: SimpleLineField): SimpleField {
  return { ...column, listed: true };
}

/** A field of the field editor as a column keeps it: no list flag, default or condition (ADR-0043). */
export function fieldColumn(field: SimpleField): SimpleLineField {
  const column: SimpleLineField = { id: field.id, type: field.type, labels: field.labels, required: field.required };
  if (field.maxLength != null) column.maxLength = field.maxLength;
  if (field.scale != null) column.scale = field.scale;
  if (field.options) column.options = field.options;
  if (field.target) column.target = field.target;
  if (field.formula !== undefined) column.formula = field.formula;
  return column;
}

/**
 * The tables of lines of a live form's document (ADR-0043): up to three, each with its key, its name, the most rows a
 * document has and 1 to 20 columns of the field types — a product from a reference list, a quantity, a price, a sum
 * computed by a formula. In a new version a stored table keeps its key and place and is never removed, takes no fewer
 * rows, and its stored columns keep their key and type (`stored`); the server checks the same.
 */
@Component({
  selector: 'app-new-form-lines',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    NewFormFieldComponent,
    NewFormLabelsComponent,
  ],
  template: `
    <fieldset class="form-lines" data-testid="form-lines">
      <legend>{{ 'studio.lines.title' | t }}</legend>
      <p class="lines-note">{{ 'studio.lines.hint' | t }}</p>
      @for (table of tables(); track $index; let tableIndex = $index) {
        <fieldset class="form-lines-table" [attr.data-testid]="'lines-table-' + tableIndex">
          <legend>{{ 'studio.lines.table_number' | t: { number: tableIndex + 1 } }}</legend>
          <smt-control
            [smtLabel]="'studio.lines.key' | t"
            [required]="true"
            [smtHint]="(storedTable(table) ? 'studio.lines.key_locked' : 'studio.lines.key_hint') | t"
          >
            <smt-input
              [value]="table.key"
              [maxLength]="20"
              [disabled]="disabled() || !!storedTable(table)"
              [attr.data-testid]="'lines-key-' + tableIndex"
              (valueChange)="patch(tableIndex, { key: text($event) })"
            />
          </smt-control>
          <app-new-form-labels
            [labels]="table.labels"
            [disabled]="disabled()"
            (changed)="setLabel(tableIndex, $event.language, $event.value)"
          />
          <smt-control
            [smtLabel]="'studio.lines.max_rows' | t"
            [required]="true"
            [smtHint]="'studio.lines.max_rows_hint' | t: { min: minRows(table), max: maxRows }"
          >
            <smt-input
              type="number"
              [value]="table.maxRows"
              [smtMin]="minRows(table)"
              [smtMax]="maxRows"
              [disabled]="disabled()"
              [attr.data-testid]="'lines-max-rows-' + tableIndex"
              (valueChange)="setMaxRows(tableIndex, $event)"
            />
          </smt-control>
          @for (column of table.fields; track $index; let columnIndex = $index) {
            <app-new-form-field
              [field]="asField(column)"
              [index]="columnIndex"
              [count]="table.fields.length"
              [disabled]="disabled()"
              [live]="true"
              [line]="true"
              [versioning]="versioning()"
              [lists]="lists()"
              [forms]="forms()"
              [fields]="fields()"
              [stored]="storedColumn(table, column)"
              [document]="documentOf(table.key)"
              [attr.data-testid]="'lines-column-' + tableIndex + '-' + columnIndex"
              (changed)="setColumn(tableIndex, columnIndex, $event)"
              (moved)="moveColumn(tableIndex, columnIndex, $event)"
              (removed)="removeColumn(tableIndex, columnIndex)"
            />
          }
          <div class="lines-actions">
            <button
              smt-button
              type="button"
              smtVariant="secondary"
              smtSize="sm"
              [disabled]="disabled() || table.fields.length >= maxColumns"
              [attr.data-testid]="'lines-add-column-' + tableIndex"
              (click)="addColumn(tableIndex)"
            >
              {{ 'studio.lines.add_column' | t }}
            </button>
            @if (!storedTable(table)) {
              <button
                smt-button
                type="button"
                smtVariant="ghost"
                smtSize="sm"
                smtIcon="delete"
                [disabled]="disabled()"
                [attr.aria-label]="'studio.lines.remove_table' | t: { number: tableIndex + 1 }"
                [attr.data-testid]="'lines-remove-' + tableIndex"
                (click)="removeTable(tableIndex)"
              ></button>
            }
          </div>
        </fieldset>
      }
      <button
        smt-button
        type="button"
        smtVariant="secondary"
        smtSize="sm"
        data-testid="lines-add"
        [disabled]="disabled() || tables().length >= maxTables"
        (click)="addTable()"
      >
        {{ 'studio.lines.add' | t }}
      </button>
    </fieldset>
  `,
  styles: [
    ':host { display: block; min-width: 0; }',
    '.form-lines, .form-lines-table { display: grid; gap: 12px; min-width: 0; }',
    '.lines-actions { display: flex; gap: 8px; flex-wrap: wrap; }',
    '.lines-note { margin: 0; color: var(--color-text-secondary, inherit); }',
  ],
})
export class NewFormLinesComponent {
  readonly lines = input<SimpleFormLines[] | undefined>(undefined);
  /** The header fields: a column's formula reads the entered ones as `doc.<field>`. */
  readonly fields = input<readonly SimpleField[]>([]);
  readonly lists = input<StudioTarget[]>([]);
  readonly forms = input<StudioTarget[]>([]);
  readonly disabled = input(false);
  readonly versioning = input(false);
  /** The tables the active version stores, or none for a new form. */
  readonly stored = input<SimpleFormLines[] | undefined>(undefined);
  /** The document has its number and statuses: the formulas of the header read them. */
  readonly numbered = input(false);
  readonly statuses = input(false);
  readonly changed = output<SimpleFormLines[] | undefined>();
  readonly tables = computed(() => this.lines() ?? []);
  readonly maxTables = MAX_LINE_TABLES;
  readonly maxColumns = MAX_LINE_COLUMNS;
  readonly maxRows = MAX_LINE_ROWS;

  /** What a column's formula of `table` reads, the checks of the server included. */
  documentOf(table: string): FormulaDocument {
    return {
      lines: this.tables().map((candidate) => ({
        key: candidate.key,
        fields: candidate.fields.map((column) => ({
          id: column.id,
          type: column.type,
          ...(column.target ? { target: column.target.kind } : {}),
        })),
      })),
      line: table,
      numbered: this.numbered(),
      statuses: this.statuses(),
    };
  }

  asField(column: SimpleLineField): SimpleField {
    return columnField(column);
  }

  storedTable(table: SimpleFormLines): SimpleFormLines | null {
    return this.stored()?.find((candidate) => candidate.key === table.key) ?? null;
  }

  storedColumn(table: SimpleFormLines, column: SimpleLineField): SimpleField | null {
    const kept = this.storedTable(table)?.fields.find((candidate) => candidate.id === column.id);
    return kept ? columnField(kept) : null;
  }

  minRows(table: SimpleFormLines): number {
    return this.storedTable(table)?.maxRows ?? 1;
  }

  addTable(): void {
    const tables = this.tables();
    if (tables.length >= MAX_LINE_TABLES) return;
    let number = tables.length + 1;
    while (tables.some((table) => table.key === `lines${number}`)) number++;
    this.emit([
      ...tables,
      {
        key: `lines${number}`,
        labels: { ru: '', uz: '', en: '' },
        maxRows: 100,
        fields: [{ id: 'name', type: 'text', labels: { ru: '', uz: '', en: '' }, required: true, maxLength: 255 }],
      },
    ]);
  }

  removeTable(index: number): void {
    const tables = [...this.tables()];
    if (!tables[index] || this.storedTable(tables[index])) return;
    tables.splice(index, 1);
    this.emit(tables);
  }

  patch(index: number, change: Partial<SimpleFormLines>): void {
    const tables = [...this.tables()];
    if (!tables[index]) return;
    tables[index] = { ...tables[index], ...change };
    this.emit(tables);
  }

  setLabel(index: number, language: StudioLanguage, value: string): void {
    const table = this.tables()[index];
    if (table) this.patch(index, { labels: { ...table.labels, [language]: value } });
  }

  setMaxRows(index: number, value: unknown): void {
    if (typeof value === 'number') this.patch(index, { maxRows: value });
  }

  addColumn(index: number): void {
    const table = this.tables()[index];
    if (!table || table.fields.length >= MAX_LINE_COLUMNS) return;
    let number = table.fields.length + 1;
    while (table.fields.some((column) => column.id === `column${number}`)) number++;
    this.patch(index, {
      fields: [
        ...table.fields,
        { id: `column${number}`, type: 'number', labels: { ru: '', uz: '', en: '' }, required: false, scale: 2 },
      ],
    });
  }

  setColumn(index: number, columnIndex: number, field: SimpleField): void {
    const table = this.tables()[index];
    if (!table) return;
    const fields = [...table.fields];
    fields[columnIndex] = fieldColumn(field);
    this.patch(index, { fields });
  }

  moveColumn(index: number, columnIndex: number, direction: number): void {
    const table = this.tables()[index];
    const next = columnIndex + direction;
    if (!table || next < 0 || next >= table.fields.length) return;
    const fields = [...table.fields];
    [fields[columnIndex], fields[next]] = [fields[next], fields[columnIndex]];
    this.patch(index, { fields });
  }

  removeColumn(index: number, columnIndex: number): void {
    const table = this.tables()[index];
    if (!table || table.fields.length <= 1) return;
    const column = table.fields[columnIndex];
    if (column && this.storedColumn(table, column)) return;
    this.patch(index, { fields: table.fields.filter((_, position) => position !== columnIndex) });
  }

  text(value: unknown): string {
    return value === null || value === undefined ? '' : String(value);
  }

  private emit(tables: SimpleFormLines[]): void {
    this.changed.emit(tables.length ? tables : undefined);
  }
}
