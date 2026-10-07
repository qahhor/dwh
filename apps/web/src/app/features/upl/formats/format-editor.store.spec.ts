import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { UplApiService, UplFormatDraftRequest, UplFormatVersion, UplVersionItem } from '../upl.api';
import { FormatEditorStore } from './format-editor.store';
import { emptyColumn, emptySheet } from './upl-format-model';

/** A draft with one sheet per name, each with a valid column. */
function version(sheetNames: string[] = ['Sheet1'], patch: Partial<UplFormatVersion> = {}): UplFormatVersion {
  return {
    sourceId: 7,
    version: 2,
    status: 'draft',
    validFrom: null,
    validTo: null,
    publishedAt: null,
    publishedBy: null,
    lockVersion: 4,
    fileKind: 'xlsx',
    encoding: null,
    delimiter: null,
    matchColumnsBy: 'header',
    sheets: sheetNames.map((sheetName) => ({
      ...emptySheet(),
      sheetName,
      columns: [{ ...emptyColumn(), nameInFile: 'STIR', targetField: 'stir' }],
    })),
    ...patch,
  };
}

const published = (v: number, validFrom: string): UplVersionItem => ({
  version: v,
  status: 'published',
  validFrom,
  validTo: null,
  publishedAt: null,
  publishedBy: null,
});

interface Options {
  versions?: Array<Observable<UplFormatVersion>>;
  save?: Observable<UplFormatVersion>;
  publish?: Observable<void>;
}

async function openStore(options: Options = {}) {
  const loads = options.versions ?? [of(version())];
  let load = 0;
  const api = {
    getSource: vi.fn(() => of({ id: 7, name: 'Source A', hasDraft: true })),
    getVersion: vi.fn(() => loads[Math.min(load++, loads.length - 1)]),
    listVersions: vi.fn(() => of([published(1, '2026-01-01'), published(3, '2026-05-01'), published(2, '2026-03-01')])),
    listUnits: vi.fn(() => of([{ code: 'kg', name: 'Kilogramm', baseUnitCode: 'kg' }])),
    saveDraft: vi.fn(
      (_id: string, _v: string, body: UplFormatDraftRequest) =>
        options.save ?? of({ ...version(), lockVersion: body.lockVersion + 1, sheets: structuredClone(body.sheets) }),
    ),
    publish: vi.fn(() => options.publish ?? of(undefined)),
  };
  const toast = { success: vi.fn(), info: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      FormatEditorStore,
      { provide: UplApiService, useValue: api },
      { provide: ToastService, useValue: toast },
      { provide: PermissionService, useValue: { hasPermission: () => true } },
    ],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const store = TestBed.inject(FormatEditorStore);
  const settle = async () => {
    TestBed.tick();
    await TestBed.inject(ApplicationRef).whenStable();
  };
  store.open('7', '2');
  await settle();
  return { store, api, toast, navigate, settle };
}

describe('FormatEditorStore', () => {
  it('loads the version with its source, versions and units, and tells the last valid date', async () => {
    const { store, api } = await openStore();

    expect(api.getSource).toHaveBeenCalledWith('7');
    expect(api.getVersion).toHaveBeenCalledWith('7', '2');
    expect(api.listVersions).toHaveBeenCalledWith('7');
    expect(store.isLoading()).toBe(false);
    expect(store.source()?.name).toBe('Source A');
    expect(store.units()).toHaveLength(1);
    expect(store.model().sheets.map((sheet) => sheet.sheetName)).toEqual(['Sheet1']);
    expect(store.step()).toBe('sheets');
    expect(store.previousValidFrom()).toBe('2026-05-01');
    expect(store.statusVariant()).toBe('blue');
    expect(store.isDirty()).toBe(false);
  });

  it('tells a missing version from a failure, and a failed reload keeps what is on screen', async () => {
    const missing = await openStore({ versions: [throwError(() => ({ status: 404 }))] });
    expect(missing.store.notFound()).toBe(true);
    expect(missing.store.loadError()).toBe(false);

    TestBed.resetTestingModule();
    const { store, settle } = await openStore({ versions: [of(version()), throwError(() => ({ status: 503 }))] });
    store.reload();
    await settle();
    expect(store.loadError()).toBe(true);
    expect(store.notFound()).toBe(false);
    expect(store.version()?.lockVersion).toBe(4);
  });

  it('tells unsaved edits made in place and reverts them', async () => {
    const { store } = await openStore();
    store.errors.set([{ sheet: 0, column: 0, field: 'x', code: 'X', key: 'upl.err.X', message: '' }]);
    store.actionError.set('failed');

    store.model().sheets[0].columns.push({ ...emptyColumn(), nameInFile: 'Volume', targetField: 'volume' });
    expect(store.isDirty()).toBe(true);

    store.revert();
    expect(store.model().sheets[0].columns).toHaveLength(1);
    expect(store.isDirty()).toBe(false);
    expect(store.errors()).toEqual([]);
    expect(store.actionError()).toBeNull();
  });

  it('saves the draft with the lock version and takes the saved version as the new baseline', async () => {
    const { store, api, toast } = await openStore();
    store.model().sheets[0].columns.push({ ...emptyColumn(), nameInFile: 'Volume', targetField: 'volume' });

    store.save();

    expect(api.saveDraft).toHaveBeenCalledWith('7', '2', expect.objectContaining({ lockVersion: 4 }));
    expect(store.version()?.lockVersion).toBe(5);
    expect(store.model().sheets[0].columns).toHaveLength(2);
    expect(store.isDirty()).toBe(false);
    expect(toast.success).toHaveBeenCalledWith(PACKAGED_RUSSIAN['upl.format.saved']);
  });

  it('keeps a target field that breaks the pattern from the server', async () => {
    const { store, api } = await openStore();
    store.model().sheets[0].columns[0].targetField = 'Поле 1';

    store.save();

    expect(api.saveDraft).not.toHaveBeenCalled();
    expect(store.errors().map((error) => error.key)).toEqual(['upl.err.Pattern']);
    expect(PACKAGED_RUSSIAN['upl.err.Pattern']).not.toContain('[a-z]');
  });

  it('leads a refusal to the sheet it addresses, and a file field to the file step', async () => {
    const refusal = (field: string) => ({
      status: 422,
      code: 'validation_failed',
      errors: [{ field, code: 'UPL_TARGET_FIELD_DUPLICATE', message: 'x' }],
    });
    const sheets = await openStore({
      versions: [of(version(['Sheet1', 'Sheet2']))],
      save: throwError(() => refusal('sheets[1].columns[0].targetField')),
    });
    sheets.store.model().sheets[0].sheetName = 'Edited';
    sheets.store.save();
    expect(sheets.store.activeSheet()).toBe(1);
    expect(sheets.store.step()).toBe('sheets');

    TestBed.resetTestingModule();
    const file = await openStore({ save: throwError(() => refusal('delimiter')) });
    file.store.save();
    expect(file.store.step()).toBe('file');
  });

  it('reloads a draft published meanwhile and says so', async () => {
    const { store, api, toast, settle } = await openStore({
      save: throwError(() => ({
        status: 409,
        code: 'conflict',
        detail: 'Версия уже опубликована, изменить нельзя',
        messageKey: 'error.upl.format_not_draft',
      })),
    });
    store.save();
    await settle();

    expect(toast.info).toHaveBeenCalledWith(expect.stringContaining('Версия уже опубликована, изменить нельзя'));
    expect(api.getVersion).toHaveBeenCalledTimes(2);
  });

  it('shows an unknown refusal with its code', async () => {
    const { store } = await openStore({
      save: throwError(() => ({ status: 400, code: 'bad_request', detail: 'UPL_NEW' })),
    });
    store.save();
    expect(store.actionError()).toContain('UPL_NEW (bad_request)');
  });

  it('keeps the active sheet on a sheet that is still there when a reload brings fewer', async () => {
    const { store, settle } = await openStore({ versions: [of(version(['A', 'B', 'C'])), of(version(['A']))] });
    store.activeSheet.set(2);

    store.discardAndReload();
    await settle();

    expect(store.activeSheet()).toBe(0);
    expect(store.model().sheets).toHaveLength(1);
  });

  it('publishes from the chosen day, or shows why the day does not fit', async () => {
    const late = await openStore({
      publish: throwError(() => ({
        status: 409,
        detail: 'Дата должна быть позже даты прежней версии',
        messageKey: 'error.upl.fnd_version_not_after_previous',
      })),
    });
    late.store.openPublish();
    late.store.confirmPublish();
    expect(late.store.publishDateError()).toBe('error.upl.fnd_version_not_after_previous');
    expect(late.store.isPublishOpen()).toBe(true);

    TestBed.resetTestingModule();
    const { store, api, navigate } = await openStore();
    store.openPublish();
    store.validFrom.set('2026-10-01');
    store.confirmPublish();
    expect(api.publish).toHaveBeenCalledWith('7', '2', '2026-10-01');
    expect(store.isPublishOpen()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/upl/sources', '7']);
  });
});
