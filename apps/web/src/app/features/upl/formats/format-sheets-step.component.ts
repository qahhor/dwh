import { ChangeDetectionStrategy, Component, inject, input, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { UPL_DATA_TYPES, UplColumn, UplDataType, UplFormatDraftRequest, UplSheet, UplUnit } from '../upl-api';
import { UPL_DATA_TYPE_KEY } from '../upl-labels';
import { UplFieldError, uplCellError, uplFieldErrorText, uplSheetError, uplSheetHasErrors } from './upl-format-errors';
import { clearFieldsForType, emptyColumn, emptySheet, isNumericColumn } from './upl-format-model';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '@shared/ui-kit/components/forms/checkbox';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

/**
 * Шаг «Листы и колонки» анкеты: вкладки листов, параметры листа и таблица колонок.
 * Правит модель на месте; активный лист и ошибки — двусторонние, их держит редактор.
 */
@Component({
  selector: 'app-upl-format-sheets-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTCheckboxValueAccessor,
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
  ],
  templateUrl: './format-sheets-step.component.html',
  styleUrl: './format-sheets-step.component.css',
})
export class FormatSheetsStepComponent {
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly model = input.required<UplFormatDraftRequest>();
  /**
   * The step is on screen. The file step edits the same mutable draft (file kind, column matching), so the
   * sheets are redrawn each time the step is shown: a changed input marks this OnPush step for checking.
   */
  readonly shown = input(true);
  readonly editable = input(false);
  readonly units = input<UplUnit[]>([]);
  readonly activeSheet = model(0);
  readonly errors = model<UplFieldError[]>([]);

  private readonly dataTypeMemo = optionsMemo<SMTSelectOption<UplDataType>[]>();
  private readonly unitMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** Типы данных колонки; подписи переводятся заново при смене языка. */
  dataTypeOptions(): SMTSelectOption<UplDataType>[] {
    return this.dataTypeMemo([this.i18n.currentLang()], () =>
      UPL_DATA_TYPES.map((type) => ({ id: type, label: this.i18n.translate(UPL_DATA_TYPE_KEY[type]) })),
    );
  }

  /** Единицы из /upl/units как «Имя (код)». */
  unitOptions(): SMTSelectOption<string>[] {
    return this.unitMemo([this.units()], () =>
      this.units().map((unit) => ({ id: unit.code, label: `${unit.name} (${unit.code})` })),
    );
  }

  /** Synonyms as the person types them: separated by semicolons, since a header may hold a comma. */
  synonymsText(column: UplColumn): string {
    return (column.headerSynonyms ?? []).join('; ');
  }

  setSynonyms(column: UplColumn, value: string): void {
    column.headerSynonyms = value
      .split(';')
      .map((name) => name.trim())
      .filter((name) => name.length > 0);
  }

  /** The first server problem with any of the column's synonyms, as words. */
  synonymError(sheet: number, column: number): string | null {
    const found = this.errors().find(
      (error) => error.sheet === sheet && error.column === column && error.field.startsWith('headerSynonyms'),
    );
    return found ? this.errorText(found) : null;
  }

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
    return uplFieldErrorText(problem, (key) => this.i18n.translate(key));
  }

  /** «Имя (код)» из /upl/units; единица вне списка — только код. */
  baseUnitLabel(column: UplColumn): string {
    const code = column.baseUnit ?? '';
    const unit = this.units().find((item) => item.code === code);
    return unit ? `${unit.name} (${unit.code})` : code;
  }

  addSheet(): void {
    this.model().sheets.push(emptySheet());
    this.activeSheet.set(this.model().sheets.length - 1);
  }

  /** Removing a sheet drops its column mapping, so it is asked first, with the number of columns lost. */
  askRemoveSheet(index: number): void {
    const columns = this.model().sheets[index]?.columns.length ?? 0;
    this.modal
      .confirm({
        title: this.text('upl.format.remove_sheet'),
        message: this.text('upl.format.remove_sheet_confirm', { count: columns.toString() }),
        yesLabel: this.text('upl.format.remove_sheet'),
        noLabel: this.text('upl.common.cancel'),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (confirmed) this.confirmRemoveSheet(index);
      });
  }

  confirmRemoveSheet(index: number): void {
    const sheets = this.model().sheets;
    if (!sheets[index]) return;
    sheets.splice(index, 1);
    this.errors.set([]);
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
    const unit = this.units().find((item) => item.code === column.sourceUnit);
    if (!unit) {
      column.sourceUnit = null;
      column.baseUnit = null;
      return;
    }
    column.baseUnit = unit.baseUnitCode;
  }
}
