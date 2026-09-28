import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { UplColumn, UplFormatDraftRequest, UplUnit } from '../upl-api';
import { FormatSheetsStepComponent } from './format-sheets-step.component';
import { UplFieldError } from './upl-format-errors';
import { emptyColumn, emptyModel, emptySheet } from './upl-format-model';

const UNITS: UplUnit[] = [
  { code: 'ton', name: 'Тонна', baseUnitCode: 'kg' },
  { code: 'kg', name: 'Килограмм', baseUnitCode: 'kg' },
];

const column = (nameInFile: string, extra: Partial<UplColumn> = {}): UplColumn => ({
  ...emptyColumn(),
  nameInFile,
  targetField: nameInFile.toLowerCase(),
  ...extra,
});

/** A workbook draft: «Продажи» with three columns, the volume in tons, and an empty «Остатки». */
function draft(): UplFormatDraftRequest {
  return {
    ...emptyModel(),
    sheets: [
      {
        ...emptySheet(),
        sheetName: 'Продажи',
        columns: [
          column('STIR', { dataType: 'object_key', keyMask: '^[0-9]{9}$' }),
          column('Volume', { dataType: 'number', sourceUnit: 'ton', baseUnit: 'kg' }),
          column('Comment'),
        ],
      },
      { ...emptySheet(), sheetName: 'Остатки' },
    ],
  };
}

function render(model: UplFormatDraftRequest, inputs: Record<string, unknown> = {}) {
  const toast = { info: vi.fn() };
  const modal = { confirm: vi.fn(() => of(true)) };
  TestBed.configureTestingModule({
    imports: [FormatSheetsStepComponent],
    providers: [
      { provide: ToastService, useValue: toast },
      { provide: SMTModalService, useValue: modal },
    ],
  });
  const fixture = TestBed.createComponent(FormatSheetsStepComponent);
  fixture.componentRef.setInput('model', model);
  fixture.componentRef.setInput('editable', true);
  fixture.componentRef.setInput('units', UNITS);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return { fixture, toast, modal };
}

const el = (fixture: ComponentFixture<FormatSheetsStepComponent>) => fixture.nativeElement as HTMLElement;
const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const all = (fixture: ComponentFixture<FormatSheetsStepComponent>, testId: string) => [
  ...el(fixture).querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`),
];
const namesInFile = (fixture: ComponentFixture<FormatSheetsStepComponent>) =>
  all(fixture, 'upl-cell-name-in-file').map((input) => (input as HTMLInputElement).value);

function click(fixture: ComponentFixture<FormatSheetsStepComponent>, node: HTMLElement | undefined) {
  if (!node) throw new Error('nothing to click');
  node.click();
  fixture.detectChanges();
}

/** Opens the smt-select inside the host and picks the option with the label. */
function choose(fixture: ComponentFixture<FormatSheetsStepComponent>, host: HTMLElement, label: string) {
  click(fixture, host.querySelector('[role="combobox"]') as HTMLElement);
  const option = [...document.querySelectorAll<HTMLElement>('.smt-select__option')].find(
    (item) => item.querySelector('.smt-select__option-label')?.textContent?.trim() === label,
  );
  click(fixture, option);
}

describe('FormatSheetsStepComponent', () => {
  it('shows the sheets as tabs and the active sheet with its columns', () => {
    const { fixture } = render(draft());

    expect(all(fixture, 'upl-sheet-tab').map(text)).toEqual(['Продажи', 'Остатки']);
    expect((el(fixture).querySelector('#upl-sheet-name') as HTMLInputElement).value).toBe('Продажи');
    expect(namesInFile(fixture)).toEqual(['STIR', 'Volume', 'Comment']);
    expect(text(el(fixture).querySelector('[data-testid="upl-cell-base-unit"]'))).toBe('Килограмм (kg)');
    expect(el(fixture).querySelector('table')?.getAttribute('aria-label')).toBe('Колонки листа «Продажи»');

    click(fixture, all(fixture, 'upl-sheet-tab')[1]);
    expect(fixture.componentInstance.activeSheet()).toBe(1);
    expect(all(fixture, 'upl-column-row')).toHaveLength(0);
  });

  it('adds a sheet and a column to the draft and writes typed cells into it', () => {
    const model = emptyModel();
    const { fixture } = render(model);
    expect(text(el(fixture).querySelector('[data-testid="upl-no-sheets"]'))).toBe('Листов пока нет');

    click(fixture, all(fixture, 'upl-add-sheet')[0]);
    click(fixture, all(fixture, 'upl-add-column')[0]);
    const name = all(fixture, 'upl-cell-name-in-file')[0] as HTMLInputElement;
    name.value = 'Сумма';
    name.dispatchEvent(new Event('input'));
    const synonyms = all(fixture, 'upl-cell-header-synonyms')[0] as HTMLInputElement;
    synonyms.value = 'Итого; Всего ;';
    synonyms.dispatchEvent(new Event('change', { bubbles: true }));

    expect(all(fixture, 'upl-sheet-tab').map(text)).toEqual(['Лист 1']);
    expect(model.sheets).toHaveLength(1);
    expect(model.sheets[0].columns[0]).toEqual(
      expect.objectContaining({ nameInFile: 'Сумма', headerSynonyms: ['Итого', 'Всего'] }),
    );
  });

  it('moves and removes columns in the draft and drops the server errors, which no longer fit', () => {
    const model = draft();
    const errors: UplFieldError[] = [
      { sheet: 0, column: 2, field: 'targetField', code: 'X', key: 'upl.err.X', message: 'Поле занято' },
    ];
    const { fixture } = render(model, { errors });
    const errorsChange = vi.fn();
    fixture.componentInstance.errors.subscribe(errorsChange);

    expect(all(fixture, 'upl-column-up')[0].hasAttribute('disabled')).toBe(true);
    expect(all(fixture, 'upl-column-down')[2].hasAttribute('disabled')).toBe(true);
    click(fixture, all(fixture, 'upl-column-down')[0]);
    expect(model.sheets[0].columns.map((item) => item.nameInFile)).toEqual(['Volume', 'STIR', 'Comment']);
    expect(errorsChange).toHaveBeenCalledWith([]);

    click(fixture, all(fixture, 'upl-column-remove')[2]);
    expect(model.sheets[0].columns.map((item) => item.nameInFile)).toEqual(['Volume', 'STIR']);
    expect(namesInFile(fixture)).toEqual(['Volume', 'STIR']);
  });

  it('clears the unit of a column that stops being a number and says so', () => {
    const model = draft();
    const { fixture, toast } = render(model);

    choose(fixture, all(fixture, 'upl-cell-type')[1], 'Текст');

    expect(model.sheets[0].columns[1]).toEqual(
      expect.objectContaining({ dataType: 'text', sourceUnit: null, baseUnit: null }),
    );
    expect(toast.info).toHaveBeenCalledWith('Единица/маска очищены');
    expect(all(fixture, 'upl-cell-base-unit')).toHaveLength(0);
  });

  it('removes a sheet only after the person confirms it, naming the columns lost', () => {
    const model = draft();
    const { fixture, modal } = render(model);

    click(fixture, all(fixture, 'upl-remove-sheet')[0]);

    expect(modal.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'Лист и его колонки будут удалены (3)', destructive: true }),
    );
    expect(model.sheets.map((sheet) => sheet.sheetName)).toEqual(['Остатки']);

    modal.confirm.mockReturnValueOnce(of(false));
    click(fixture, all(fixture, 'upl-remove-sheet')[0]);
    expect(model.sheets).toHaveLength(1);
  });

  it('shows a server error under the sheet field and marks the tab', () => {
    const errors: UplFieldError[] = [
      {
        sheet: 0,
        column: null,
        field: 'sheetName',
        code: 'UPL_SHEET_NAME_DUPLICATE',
        key: 'upl.err.UPL_SHEET_NAME_DUPLICATE',
        message: '',
      },
    ];
    const { fixture } = render(draft(), { errors });

    expect(text(el(fixture).querySelector('.upl-field-error'))).toBe('Имя листа повторяется');
    expect(all(fixture, 'upl-sheet-tab')[0].querySelector('[data-testid="upl-tab-error"]')).not.toBeNull();
    expect(all(fixture, 'upl-sheet-tab')[1].querySelector('[data-testid="upl-tab-error"]')).toBeNull();
  });

  it('when shown again, follows what the file step changed and locks a draft that may not be edited', () => {
    const model = draft();
    const { fixture } = render(model, { shown: false, editable: false });

    model.fileKind = 'csv';
    model.matchColumnsBy = 'position';
    fixture.componentRef.setInput('shown', true);
    fixture.detectChanges();

    expect(el(fixture).querySelector('#upl-sheet-name')).toBeNull();
    expect(all(fixture, 'upl-cell-header-synonyms')).toHaveLength(0);
    expect(all(fixture, 'upl-cell-file-position')).toHaveLength(3);
    expect(all(fixture, 'upl-add-column')).toHaveLength(0);
    expect(all(fixture, 'upl-remove-sheet')).toHaveLength(0);
    expect((all(fixture, 'upl-cell-name-in-file')[0] as HTMLInputElement).disabled).toBe(true);
  });
});
