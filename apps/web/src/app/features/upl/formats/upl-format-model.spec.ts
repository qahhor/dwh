import { describe, expect, it } from 'vitest';
import { UplColumn, UplFormatDraftRequest } from '../upl.api';
import {
  buildDraftRequest,
  clearFieldsForType,
  emptyColumn,
  emptyModel,
  emptySheet,
  uplErrorStep,
} from './upl-format-model';

const column = (patch: Partial<UplColumn>): UplColumn => ({ ...emptyColumn(), ...patch });

describe('buildDraftRequest', () => {
  it('numbers sheets and columns in the order on screen and trims what the person typed', () => {
    const model: UplFormatDraftRequest = {
      ...emptyModel(),
      sheets: [
        {
          ...emptySheet(),
          ordinal: 5,
          sheetName: '  Sheet1 ',
          totalRowMarker: ' ',
          columns: [
            column({ ordinal: 9, nameInFile: ' Volume ', targetField: ' volume ', headerSynonyms: [' Итого ', ' '] }),
            column({ ordinal: 3, nameInFile: 'STIR', targetField: 'stir', keyMask: '  ' }),
          ],
        },
      ],
    };

    const request = buildDraftRequest(model, 4);

    expect(request.lockVersion).toBe(4);
    expect(request.sheets.map((sheet) => sheet.ordinal)).toEqual([1]);
    expect(request.sheets[0].sheetName).toBe('Sheet1');
    expect(request.sheets[0].totalRowMarker).toBeNull();
    expect(request.sheets[0].columns.map((item) => item.ordinal)).toEqual([1, 2]);
    expect(request.sheets[0].columns[0]).toEqual(
      expect.objectContaining({ nameInFile: 'Volume', targetField: 'volume', headerSynonyms: ['Итого'] }),
    );
    expect(request.sheets[0].columns[1].keyMask).toBeNull();
  });

  it('drops the settings of the other file kind', () => {
    const workbook = { ...emptyModel(), encoding: 'utf-8', delimiter: ';' } as UplFormatDraftRequest;
    expect(buildDraftRequest(workbook, 0)).toEqual(expect.objectContaining({ encoding: null, delimiter: null }));

    const csv: UplFormatDraftRequest = {
      ...emptyModel(),
      fileKind: 'csv',
      encoding: 'utf-8',
      delimiter: ' ; ',
      sheets: [{ ...emptySheet(), sheetName: 'Sheet1' }],
    };
    const request = buildDraftRequest(csv, 0);
    expect(request).toEqual(expect.objectContaining({ encoding: 'utf-8', delimiter: ';' }));
    expect(request.sheets[0].sheetName).toBeNull();
  });
});

describe('clearFieldsForType', () => {
  it('clears the key settings of a column that stops being a key and says it did', () => {
    const key = column({ dataType: 'text', keyMask: '^[0-9]{9}$', keyPadLength: 9, keyPadMax: 9 });

    expect(clearFieldsForType(key)).toBe(true);
    expect(key).toEqual(expect.objectContaining({ keyMask: null, keyPadLength: null, keyPadMax: null }));
  });

  it('clears units and a reference book the new type has not', () => {
    const numeric = column({ dataType: 'date', sourceUnit: 'ton', baseUnit: 'kg', refBookCode: 'regions' });

    expect(clearFieldsForType(numeric)).toBe(true);
    expect(numeric).toEqual(expect.objectContaining({ sourceUnit: null, baseUnit: null, refBookCode: null }));
  });

  it('says nothing was cleared when the column had nothing the new type lacks', () => {
    expect(clearFieldsForType(column({ dataType: 'date' }))).toBe(false);
    expect(clearFieldsForType(column({ dataType: 'number', sourceUnit: 'ton', baseUnit: 'kg' }))).toBe(false);
  });
});

describe('uplErrorStep', () => {
  it('leads a file field to the file step and everything else to the sheets', () => {
    const error = (sheet: number | null, field: string) => ({
      sheet,
      column: null,
      field,
      code: 'X',
      key: 'x',
      message: '',
    });

    expect(uplErrorStep(error(null, 'delimiter'))).toBe('file');
    expect(uplErrorStep(error(null, 'sheets'))).toBe('sheets');
    expect(uplErrorStep(error(0, 'sheetName'))).toBe('sheets');
  });
});
