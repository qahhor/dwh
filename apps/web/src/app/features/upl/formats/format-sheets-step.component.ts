import { Component, computed, inject, input, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UPL_DATA_TYPES, UplColumn, UplFormatDraftRequest, UplSheet, UplUnit } from '../upl-api';
import { UPL_DATA_TYPE_KEY } from '../upl-labels';
import { UplFieldError, uplCellError, uplFieldErrorText, uplSheetError, uplSheetHasErrors } from './upl-format-errors';
import { clearFieldsForType, emptyColumn, emptySheet, isNumericColumn } from './upl-format-model';

/**
 * Шаг «Листы и колонки» анкеты: вкладки листов, параметры листа и таблица колонок.
 * Правит модель на месте; активный лист и ошибки — двусторонние, их держит редактор.
 */
@Component({
  selector: 'app-upl-format-sheets-step',
  standalone: true,
  // Не OnPush: вид файла и сопоставление колонок меняет соседний шаг «Файл» в той же изменяемой модели.
  imports: [FormsModule, TranslatePipe, UiButtonComponent, UiModalComponent],
  template: `
    <h2 class="upl-block-title">{{ 'upl.format.sheets' | t }}</h2>
    <div class="upl-tabs" role="tablist">
      @for (sheet of model().sheets; track $index) {
        <span class="upl-tab" [class.upl-tab-active]="activeSheet() === $index">
          <button type="button" class="upl-tab-button" data-testid="upl-sheet-tab" (click)="activeSheet.set($index)">
            <span>{{ sheet.sheetName || text('upl.format.sheet_n', { n: ($index + 1).toString() }) }}</span>
            @if (sheetHasErrors($index)) {
              <span class="upl-tab-dot" data-testid="upl-tab-error" aria-hidden="true"></span>
            }
          </button>
          @if (editable()) {
            <button
              type="button"
              class="upl-tab-remove"
              data-testid="upl-remove-sheet"
              [attr.aria-label]="'upl.format.remove_sheet' | t"
              (click)="sheetToRemove.set($index)"
            >×</button>
          }
        </span>
      }
      @if (editable()) {
        <button type="button" class="upl-tab-add" data-testid="upl-add-sheet" (click)="addSheet()">
          {{ 'upl.format.add_sheet' | t }}
        </button>
      }
    </div>

    @if (model().sheets.length === 0) {
      <p class="upl-muted" data-testid="upl-no-sheets">{{ 'upl.format.no_sheets' | t }}</p>
    } @else if (activeSheetModel(); as sheet) {
      <div class="upl-row">
        @if (model().fileKind !== 'csv') {
          <div class="form-group">
            <label class="form-label" for="upl-sheet-name">{{ 'upl.format.field.sheet_name' | t }}</label>
            <input
              id="upl-sheet-name"
              class="form-input"
              type="text"
              data-testid="upl-sheet-name"
              [class.upl-cell-error]="sheetError(activeSheet(), 'sheetName')"
              [disabled]="!editable()"
              [(ngModel)]="sheet.sheetName"
              [ngModelOptions]="{ standalone: true }"
            />
            @if (sheetError(activeSheet(), 'sheetName'); as problem) {
              <span class="upl-field-error">{{ errorText(problem) }}</span>
            }
          </div>
        }
        <div class="form-group">
          <label class="form-label" for="upl-header-row">{{ 'upl.format.field.header_row' | t }}</label>
          <input
            id="upl-header-row"
            class="form-input upl-input-small"
            type="number"
            min="1"
            [class.upl-cell-error]="sheetError(activeSheet(), 'headerRow')"
            [disabled]="!editable()"
            [(ngModel)]="sheet.headerRow"
            [ngModelOptions]="{ standalone: true }"
          />
          <span class="upl-hint">{{ 'upl.format.hint.header_row' | t }}</span>
          @if (sheetError(activeSheet(), 'headerRow'); as problem) {
            <span class="upl-field-error">{{ errorText(problem) }}</span>
          }
        </div>
        <div class="form-group">
          <label class="form-label" for="upl-total-marker">{{ 'upl.format.field.total_row_marker' | t }}</label>
          <input
            id="upl-total-marker"
            class="form-input"
            type="text"
            [class.upl-cell-error]="sheetError(activeSheet(), 'totalRowMarker')"
            [disabled]="!editable()"
            [(ngModel)]="sheet.totalRowMarker"
            [ngModelOptions]="{ standalone: true }"
          />
          <span class="upl-hint">{{ 'upl.format.hint.total_marker' | t }}</span>
          @if (sheetError(activeSheet(), 'totalRowMarker'); as problem) {
            <span class="upl-field-error">{{ errorText(problem) }}</span>
          }
        </div>
      </div>

      <div class="table-card">
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>{{ 'upl.format.col.name_in_file' | t }}</th>
                <th>{{ 'upl.format.col.target_field' | t }}</th>
                <th>{{ 'upl.format.col.type' | t }}</th>
                <th>{{ 'upl.format.col.required' | t }}</th>
                <th>{{ 'upl.format.col.source_unit' | t }}</th>
                <th>{{ 'upl.format.col.base_unit' | t }}</th>
                <th>{{ 'upl.format.col.key_mask' | t }}</th>
                <th>{{ 'upl.format.col.key_pad' | t }}</th>
                <th>{{ 'upl.format.col.ref_book' | t }}</th>
                @if (model().matchColumnsBy === 'position') {
                  <th>{{ 'upl.format.col.file_position' | t }}</th>
                }
                @if (editable()) {
                  <th [attr.aria-label]="'upl.format.col.actions' | t"></th>
                }
              </tr>
            </thead>
            <tbody>
              @for (column of sheet.columns; track $index) {
                <tr data-testid="upl-column-row">
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'nameInFile')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'nameInFile')"
                  >
                    <input
                      class="form-input"
                      type="text"
                      data-testid="upl-cell-name-in-file"
                      [attr.aria-label]="'upl.format.col.name_in_file' | t"
                      [disabled]="!editable()"
                      [(ngModel)]="column.nameInFile"
                      [ngModelOptions]="{ standalone: true }"
                    />
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'targetField')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'targetField') ?? text('upl.format.hint.target_field')"
                  >
                    <input
                      class="form-input"
                      type="text"
                      data-testid="upl-cell-target-field"
                      [attr.aria-label]="'upl.format.col.target_field' | t"
                      [disabled]="!editable()"
                      [(ngModel)]="column.targetField"
                      [ngModelOptions]="{ standalone: true }"
                    />
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'dataType')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'dataType')"
                  >
                    <select
                      class="form-select"
                      data-testid="upl-cell-type"
                      [attr.aria-label]="'upl.format.col.type' | t"
                      [disabled]="!editable()"
                      [(ngModel)]="column.dataType"
                      [ngModelOptions]="{ standalone: true }"
                      (ngModelChange)="onTypeChange(column)"
                    >
                      @for (type of dataTypes; track type) {
                        <option [value]="type">{{ dataTypeKey[type] | t }}</option>
                      }
                    </select>
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'required')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'required')"
                  >
                    <input
                      type="checkbox"
                      data-testid="upl-cell-required"
                      [attr.aria-label]="'upl.format.col.required' | t"
                      [disabled]="!editable()"
                      [(ngModel)]="column.required"
                      [ngModelOptions]="{ standalone: true }"
                    />
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'sourceUnit')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'sourceUnit')"
                  >
                    @if (isNumeric(column)) {
                      <select
                        class="form-select"
                        data-testid="upl-cell-source-unit"
                        [attr.aria-label]="'upl.format.col.source_unit' | t"
                        [disabled]="!editable()"
                        [(ngModel)]="column.sourceUnit"
                        [ngModelOptions]="{ standalone: true }"
                        (ngModelChange)="onUnitChange(column)"
                      >
                        <option [ngValue]="null">—</option>
                        @for (unit of units(); track unit.code) {
                          <option [ngValue]="unit.code">{{ unit.name }} ({{ unit.code }})</option>
                        }
                      </select>
                    }
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'baseUnit')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'baseUnit')"
                  >
                    @if (isNumeric(column)) {
                      <span data-testid="upl-cell-base-unit">{{ baseUnitLabel(column) }}</span>
                    }
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'keyMask')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'keyMask') ?? text('upl.format.hint.key_mask')"
                  >
                    @if (column.dataType === 'object_key') {
                      <input
                        class="form-input"
                        type="text"
                        data-testid="upl-cell-key-mask"
                        [attr.aria-label]="'upl.format.col.key_mask' | t"
                        [disabled]="!editable()"
                        [(ngModel)]="column.keyMask"
                        [ngModelOptions]="{ standalone: true }"
                      />
                    }
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'keyPadLength') || cellError(activeSheet(), $index, 'keyPadMax')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'keyPadLength') ?? cellTitle(activeSheet(), $index, 'keyPadMax')"
                  >
                    @if (column.dataType === 'object_key') {
                      <span class="upl-pad-pair">
                        <input
                          class="form-input upl-input-small"
                          type="number"
                          min="1"
                          data-testid="upl-cell-key-pad-length"
                          [attr.aria-label]="'upl.format.col.key_pad_length' | t"
                          [disabled]="!editable()"
                          [(ngModel)]="column.keyPadLength"
                          [ngModelOptions]="{ standalone: true }"
                        />
                        <input
                          class="form-input upl-input-small"
                          type="number"
                          min="1"
                          data-testid="upl-cell-key-pad-max"
                          [attr.aria-label]="'upl.format.col.key_pad_max' | t"
                          [disabled]="!editable()"
                          [(ngModel)]="column.keyPadMax"
                          [ngModelOptions]="{ standalone: true }"
                        />
                      </span>
                    }
                  </td>
                  <td
                    [class.upl-cell-error]="cellError(activeSheet(), $index, 'refBookCode')"
                    [attr.title]="cellTitle(activeSheet(), $index, 'refBookCode')"
                  >
                    @if (column.dataType === 'ref_code') {
                      <input
                        class="form-input"
                        type="text"
                        data-testid="upl-cell-ref-book"
                        [attr.aria-label]="'upl.format.col.ref_book' | t"
                        [disabled]="!editable()"
                        [(ngModel)]="column.refBookCode"
                        [ngModelOptions]="{ standalone: true }"
                      />
                    }
                  </td>
                  @if (model().matchColumnsBy === 'position') {
                    <td
                      [class.upl-cell-error]="cellError(activeSheet(), $index, 'filePosition')"
                      [attr.title]="cellTitle(activeSheet(), $index, 'filePosition')"
                    >
                      <input
                        class="form-input upl-input-small"
                        type="number"
                        min="1"
                        data-testid="upl-cell-file-position"
                        [attr.aria-label]="'upl.format.col.file_position' | t"
                        [disabled]="!editable()"
                        [(ngModel)]="column.filePosition"
                        [ngModelOptions]="{ standalone: true }"
                      />
                    </td>
                  }
                  @if (editable()) {
                    <td class="upl-row-actions">
                      <ui-button
                        variant="ghost"
                        size="sm"
                        icon="arrow_upward"
                        data-testid="upl-column-up"
                        [ariaLabel]="'upl.format.move_up' | t"
                        [disabled]="$index === 0"
                        (onClick)="moveColumn($index, -1)"
                      ></ui-button>
                      <ui-button
                        variant="ghost"
                        size="sm"
                        icon="arrow_downward"
                        data-testid="upl-column-down"
                        [ariaLabel]="'upl.format.move_down' | t"
                        [disabled]="$index === sheet.columns.length - 1"
                        (onClick)="moveColumn($index, 1)"
                      ></ui-button>
                      <ui-button
                        variant="ghost"
                        size="sm"
                        icon="close"
                        data-testid="upl-column-remove"
                        [ariaLabel]="'upl.format.remove_column' | t"
                        (onClick)="removeColumn($index)"
                      ></ui-button>
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      @if (editable()) {
        <ui-button variant="secondary" size="sm" data-testid="upl-add-column" (onClick)="addColumn()">
          {{ 'upl.format.add_column' | t }}
        </ui-button>
      }
    }

    <ui-modal
      [isOpen]="sheetToRemove() !== null"
      [title]="'upl.format.remove_sheet' | t"
      size="sm"
      (close)="sheetToRemove.set(null)"
    >
      <p body>{{ text('upl.format.remove_sheet_confirm', { count: sheetToRemoveColumns().toString() }) }}</p>
      <div footer class="upl-modal-actions">
        <ui-button variant="secondary" (onClick)="sheetToRemove.set(null)">{{ 'upl.common.cancel' | t }}</ui-button>
        <ui-button variant="danger" data-testid="upl-remove-sheet-confirm" (onClick)="confirmRemoveSheet()">
          {{ 'upl.format.remove_sheet' | t }}
        </ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 0.75rem; }
    .upl-block-title { margin: 0; font-size: 1rem; color: var(--text-main); }
    .upl-muted { color: var(--text-muted); }
    .upl-row { display: flex; flex-wrap: wrap; gap: 1rem; align-items: flex-start; }
    .upl-input-small { max-width: 7rem; }
    .upl-hint { color: var(--text-light); font-size: 0.75rem; }
    .upl-field-error { color: var(--danger); font-size: 0.75rem; }
    .upl-tabs { display: flex; flex-wrap: wrap; gap: 0.25rem; border-bottom: 1px solid var(--border-color); }
    .upl-tab { display: inline-flex; align-items: center; background: var(--bg-hover); border: 1px solid var(--border-color); border-radius: var(--radius-sm) var(--radius-sm) 0 0; }
    .upl-tab-active { background: var(--bg-active); border-color: var(--primary); }
    .upl-tab-button { display: inline-flex; align-items: center; gap: 0.375rem; background: none; border: none; color: var(--text-main); padding: 0.375rem 0.625rem; cursor: pointer; }
    .upl-tab-remove { background: none; border: none; color: var(--text-muted); padding: 0 0.5rem 0 0; cursor: pointer; }
    .upl-tab-add { background: none; border: 1px dashed var(--border-color); border-radius: var(--radius-sm); color: var(--primary); padding: 0.375rem 0.625rem; cursor: pointer; }
    .upl-tab-dot { width: 0.5rem; height: 0.5rem; border-radius: 50%; background: var(--danger); }
    .upl-pad-pair { display: inline-flex; gap: 0.25rem; }
    .upl-row-actions { display: flex; gap: 0.25rem; white-space: nowrap; }
    .upl-cell-error { border: 1px solid var(--danger); background: var(--danger-bg); border-radius: var(--radius-sm); }
    .upl-modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; }
  `]
})
export class FormatSheetsStepComponent {
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly model = input.required<UplFormatDraftRequest>();
  readonly editable = input(false);
  readonly units = input<UplUnit[]>([]);
  readonly activeSheet = model(0);
  readonly errors = model<UplFieldError[]>([]);

  readonly dataTypes = UPL_DATA_TYPES;
  readonly dataTypeKey = UPL_DATA_TYPE_KEY;

  readonly sheetToRemove = signal<number | null>(null);
  readonly sheetToRemoveColumns = computed(() => {
    const index = this.sheetToRemove();
    return index === null ? 0 : this.model().sheets[index]?.columns.length ?? 0;
  });

  text(key: string, params?: Record<string, string>): string {
    return this.i18n.translate(key, params);
  }

  activeSheetModel(): UplSheet | null {
    return this.model().sheets[this.activeSheet()] ?? null;
  }

  isNumeric(column: UplColumn): boolean {
    return isNumericColumn(column);
  }

  cellError(sheet: number, column: number, field: string): UplFieldError | null {
    return uplCellError(this.errors(), sheet, column, field);
  }

  cellTitle(sheet: number, column: number, field: string): string | null {
    const problem = this.cellError(sheet, column, field);
    return problem === null ? null : this.errorText(problem);
  }

  sheetError(sheet: number, field: string): UplFieldError | null {
    return uplSheetError(this.errors(), sheet, field);
  }

  sheetHasErrors(sheet: number): boolean {
    return uplSheetHasErrors(this.errors(), sheet);
  }

  /** Неизвестный код не прячем: показываем сообщение сервера и сам код. */
  errorText(problem: UplFieldError): string {
    return uplFieldErrorText(problem, key => this.i18n.translate(key));
  }

  /** «Имя (код)» из /upl/units; единица вне списка — только код. */
  baseUnitLabel(column: UplColumn): string {
    const code = column.baseUnit ?? '';
    const unit = this.units().find(item => item.code === code);
    return unit ? `${unit.name} (${unit.code})` : code;
  }

  addSheet(): void {
    this.model().sheets.push(emptySheet());
    this.activeSheet.set(this.model().sheets.length - 1);
  }

  confirmRemoveSheet(): void {
    const index = this.sheetToRemove();
    if (index === null) return;
    const sheets = this.model().sheets;
    sheets.splice(index, 1);
    this.errors.set([]);
    this.sheetToRemove.set(null);
    if (this.activeSheet() >= sheets.length) {
      this.activeSheet.set(Math.max(0, sheets.length - 1));
    }
  }

  addColumn(): void {
    this.activeSheetModel()?.columns.push(emptyColumn());
  }

  moveColumn(index: number, shift: number): void {
    const sheet = this.activeSheetModel();
    if (!sheet) return;
    const target = index + shift;
    if (target < 0 || target >= sheet.columns.length) return;
    const [column] = sheet.columns.splice(index, 1);
    sheet.columns.splice(target, 0, column);
    this.errors.set([]);
  }

  removeColumn(index: number): void {
    const sheet = this.activeSheetModel();
    if (!sheet) return;
    sheet.columns.splice(index, 1);
    this.errors.set([]);
  }

  /** Поля, которых у нового типа нет, очищаются — единственная молчаливая правка, и о ней говорим тостом. */
  onTypeChange(column: UplColumn): void {
    const cleared = clearFieldsForType(column);
    this.errors.set([]);
    if (cleared) {
      this.toast.info(this.i18n.translate('upl.format.cleared'));
    }
  }

  onUnitChange(column: UplColumn): void {
    const unit = this.units().find(item => item.code === column.sourceUnit);
    if (!unit) {
      column.sourceUnit = null;
      column.baseUnit = null;
      return;
    }
    column.baseUnit = unit.baseUnitCode;
  }
}
