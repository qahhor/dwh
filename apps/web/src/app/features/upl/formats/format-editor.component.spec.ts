import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { UplApiService, UplFormatDraftRequest, UplFormatVersion, UplSource } from '../upl.api';
import { FormatEditorComponent } from './format-editor.component';
import { emptyColumn } from './upl-format-model';
import { inScreen } from '@testing/in-screen';
import { SMTModalService } from '@shared/ui-kit/components/modal';

// What each step draws and edits is pinned by the step specs, the store's rules by its own spec.

const SOURCE: UplSource = {
  id: 7,
  code: 'SRC',
  name: 'Source A',
  ownerOrg: 'TEST',
  ownerContact: null,
  periodicity: 'month',
  slaDays: 5,
  sourceType: 'file',
  reconciliationStrictness: 'error',
  lockVersion: 2,
  lastPublishedVersion: null,
  hasDraft: true,
  createdAt: '2026-09-01T00:00:00Z',
  modifiedAt: '2026-09-01T00:00:00Z',
};

const COLUMN = { ...emptyColumn(), id: 21, required: true };

function draftVersion(): UplFormatVersion {
  return {
    sourceId: 7,
    version: 1,
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
    sheets: [
      {
        id: 11,
        ordinal: 1,
        sheetName: 'Sheet1',
        headerRow: 1,
        totalRowMarker: null,
        columns: [
          {
            ...COLUMN,
            ordinal: 1,
            nameInFile: 'STIR',
            targetField: 'stir',
            dataType: 'object_key',
            keyMask: '^[0-9]{9}$',
          },
          {
            ...COLUMN,
            id: 22,
            ordinal: 2,
            nameInFile: 'Volume',
            targetField: 'volume',
            dataType: 'number',
            required: false,
            keyMask: null,
          },
        ],
      },
    ],
  };
}

const PUBLISHED: UplFormatVersion = { ...draftVersion(), status: 'published', validFrom: '2026-01-01' };

/** A refusal of the server's validation with the given addressed errors. */
function invalid(...errors: Array<[field: string, code: string, message?: string]>) {
  return {
    status: 422,
    code: 'validation_failed',
    detail: 'Анкета не прошла проверку',
    messageKey: 'error.upl.format_invalid',
    errors: errors.map(([field, code, message = 'x']) => ({ field, code, message })),
  };
}

interface FixtureOptions {
  version?: UplFormatVersion;
  source?: UplSource;
  actions?: string[];
  saveError?: unknown;
  loadError?: unknown;
  publishError?: unknown;
}

function today(): string {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

async function createFixture(options: FixtureOptions = {}) {
  const version = options.version ?? draftVersion();
  const actions = options.actions ?? ['view', 'edit', 'publish'];
  const api = {
    getSource: vi.fn(() => of(structuredClone(options.source ?? SOURCE))),
    getVersion: vi.fn(() => (options.loadError ? throwError(() => options.loadError) : of(structuredClone(version)))),
    listVersions: vi.fn(() => of([])),
    listUnits: vi.fn(() => of([{ code: 'ton', name: 'Tonna', baseUnitCode: 'kg' }])),
    saveDraft: vi.fn((_id: string, _v: string, body: UplFormatDraftRequest) =>
      options.saveError
        ? throwError(() => options.saveError)
        : of({
            ...structuredClone(version),
            lockVersion: version.lockVersion + 1,
            sheets: structuredClone(body.sheets),
          }),
    ),
    publish: vi.fn(() => (options.publishError ? throwError(() => options.publishError) : of(undefined))),
  };
  const toast = { success: vi.fn(), info: vi.fn(), error: vi.fn() };
  const permissions = { hasPermission: vi.fn((_form: string, action: string) => actions.includes(action)) };

  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [FormatEditorComponent],
    providers: [
      provideRouter([]),
      { provide: UplApiService, useValue: api },
      { provide: PermissionService, useValue: permissions },
      { provide: ToastService, useValue: toast },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: '7', v: '1' })) } },
    ],
  }).compileComponents();

  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(FormatEditorComponent);
  /** Renders what the version resource brought after a load or a reload. */
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  return { fixture, api, toast, navigate, settle, component: fixture.componentInstance };
}

function one(fixture: ComponentFixture<FormatEditorComponent>, testId: string): HTMLElement | null {
  return inScreen(fixture.nativeElement).querySelector(`[data-testid="${testId}"]`);
}

function many(fixture: ComponentFixture<FormatEditorComponent>, testId: string): HTMLElement[] {
  return Array.from(inScreen(fixture.nativeElement).querySelectorAll(`[data-testid="${testId}"]`));
}

/** Clicks the element (or the button inside it) and redraws. */
function press(fixture: ComponentFixture<FormatEditorComponent>, element: HTMLElement | null): void {
  if (!element) {
    throw new Error('element to click not found');
  }
  (element.querySelector('button') ?? element).click();
  fixture.detectChanges();
}

/** Submits the publish dialog as its primary button or Enter in the date does. */
function confirmPublish(fixture: ComponentFixture<FormatEditorComponent>): void {
  const form = inScreen(fixture.nativeElement).querySelector('form#upl-publish') as HTMLFormElement | null;
  if (!form) throw new Error('publish form not found');
  form.requestSubmit();
  fixture.detectChanges();
}

function publishDateError(fixture: ComponentFixture<FormatEditorComponent>): HTMLElement | null {
  return one(fixture, 'upl-valid-from-control')?.querySelector('.smt-control__error') ?? null;
}

function setInputValue(input: HTMLInputElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

/** A dirty draft: a new column filled in so that the local check lets it through. */
function addValidColumn(fixture: ComponentFixture<FormatEditorComponent>, name = 'Новая', target = 'new_col'): void {
  press(fixture, one(fixture, 'upl-add-column'));
  const row = many(fixture, 'upl-column-row').at(-1)!;
  setInputValue(row.querySelector('[data-testid="upl-cell-name-in-file"]') as HTMLInputElement, name);
  setInputValue(row.querySelector('[data-testid="upl-cell-target-field"]') as HTMLInputElement, target);
  fixture.detectChanges();
}

const stepButton = (fixture: ComponentFixture<FormatEditorComponent>, id: string) =>
  one(fixture, 'upl-steps')!.querySelector(`button[data-step="${id}"]`) as HTMLButtonElement;
const visibleSteps = (fixture: ComponentFixture<FormatEditorComponent>) =>
  ['file', 'sheets', 'publish'].filter((id) => !(one(fixture, `upl-step-${id}`) as HTMLElement).hidden);

describe('FormatEditorComponent', () => {
  // Dialogs render into the CDK overlay on document.body; each test starts without the last one's.
  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove()));
  it('loads source, version, versions and units by the route and shows the sheets with their columns', async () => {
    const { fixture, api } = await createFixture();

    expect(api.getSource).toHaveBeenCalledWith('7');
    expect(api.getVersion).toHaveBeenCalledWith('7', '1');
    expect(api.listVersions).toHaveBeenCalledWith('7');
    expect(api.listUnits).toHaveBeenCalledTimes(1);
    expect(many(fixture, 'upl-sheet-tab').length).toBe(1);
    expect(many(fixture, 'upl-column-row').length).toBe(2);
    expect(one(fixture, 'upl-actions')).not.toBeNull();
  });

  it('shows not found with a link to the list on 404 and a load error on a failure', async () => {
    const missing = await createFixture({
      loadError: {
        status: 404,
        code: 'not_found',
        detail: 'Источник или версия не найдены',
        messageKey: 'error.upl.fnd_version_unknown',
      },
    });
    const notFound = one(missing.fixture, 'upl-not-found');
    expect(notFound!.querySelector('a')!.getAttribute('href')).toBe('/upl/sources');
    expect(one(missing.fixture, 'upl-load-error')).toBeNull();
    expect(one(missing.fixture, 'upl-actions')).toBeNull();

    const failed = await createFixture({ loadError: { status: 503 } });
    expect(one(failed.fixture, 'upl-load-error')).not.toBeNull();
    expect(one(failed.fixture, 'upl-not-found')).toBeNull();
  });

  it('walks a published or superseded version through the steps read only', async () => {
    const superseded = {
      ...draftVersion(),
      status: 'superseded' as const,
      validFrom: '2026-01-01',
      validTo: '2026-03-31',
    };
    for (const version of [PUBLISHED, superseded]) {
      const { fixture } = await createFixture({ version, source: { ...SOURCE, hasDraft: false } });
      expect(one(fixture, 'upl-readonly-note')).not.toBeNull();
      expect(one(fixture, 'upl-actions')).toBeNull();
      expect((many(fixture, 'upl-cell-name-in-file')[0] as HTMLInputElement).disabled).toBe(true);
    }
    const { fixture } = await createFixture({ version: PUBLISHED, source: { ...SOURCE, hasDraft: false } });

    press(fixture, stepButton(fixture, 'file'));
    expect(one(fixture, 'upl-file-kind')!.querySelector<HTMLButtonElement>('[role="combobox"]')!.disabled).toBe(true);
    press(fixture, stepButton(fixture, 'publish'));
    expect(stepButton(fixture, 'publish').textContent).toContain(PACKAGED_RUSSIAN['ui.stepper.complete']);
    expect(one(fixture, 'upl-review')!.textContent).toContain('01.01.2026');
    expect(one(fixture, 'upl-review-state')!.textContent).toContain(PACKAGED_RUSSIAN['upl.format.review.not_draft']);
  });

  it('offers a new draft from a read only version only with edit right and without a draft', async () => {
    const offered = async (hasDraft: boolean, actions?: string[]) => {
      const { fixture } = await createFixture({ version: PUBLISHED, source: { ...SOURCE, hasDraft }, actions });
      return one(fixture, 'upl-readonly-note')!.querySelector('a') !== null;
    };

    expect(await offered(false)).toBe(true);
    expect(await offered(true)).toBe(false);
    expect(await offered(false, ['view'])).toBe(false);
  });

  it('shows save with the edit right, publish with the publish right, and a viewer no edit controls', async () => {
    const withoutEdit = await createFixture({ actions: ['view', 'publish'] });
    expect((many(withoutEdit.fixture, 'upl-cell-name-in-file')[0] as HTMLInputElement).disabled).toBe(true);
    expect(one(withoutEdit.fixture, 'upl-save')).toBeNull();

    const withoutPublish = await createFixture({ actions: ['view', 'edit'] });
    expect(one(withoutPublish.fixture, 'upl-publish')).toBeNull();
    expect(one(withoutPublish.fixture, 'upl-save')).not.toBeNull();

    const viewer = await createFixture({ actions: ['view'] });
    for (const testId of ['upl-actions', 'upl-save', 'upl-publish', 'upl-revert', 'upl-add-column', 'upl-add-sheet']) {
      expect(one(viewer.fixture, testId)).toBeNull();
    }
    expect(many(viewer.fixture, 'upl-column-row').length).toBe(2);
  });

  it('saves the whole draft with the lock version and ordinals', async () => {
    const { fixture, api, toast, component } = await createFixture();

    addValidColumn(fixture);
    press(fixture, one(fixture, 'upl-save'));

    expect(api.saveDraft).toHaveBeenCalledTimes(1);
    const [id, v, body] = api.saveDraft.mock.calls[0];
    expect([id, v]).toEqual(['7', '1']);
    expect(body.lockVersion).toBe(4);
    expect(body.encoding).toBeNull();
    expect(body.delimiter).toBeNull();
    expect(body.sheets.map((sheet) => sheet.ordinal)).toEqual([1]);
    expect(body.sheets[0].columns.map((column) => column.ordinal)).toEqual([1, 2, 3]);
    expect(body.sheets[0].columns.map((column) => column.required)).toEqual([true, false, false]);
    expect(component.store.isDirty()).toBe(false);
    expect(toast.success).toHaveBeenCalled();
  });

  it('does not send a column without names and marks its cells', async () => {
    const { fixture, api } = await createFixture();

    press(fixture, one(fixture, 'upl-add-column'));
    press(fixture, one(fixture, 'upl-save'));

    expect(api.saveDraft).not.toHaveBeenCalled();
    expect(one(fixture, 'upl-errors-summary')!.textContent).toContain(PACKAGED_RUSSIAN['upl.err.NotBlank']);
    const added = many(fixture, 'upl-column-row')[2];
    for (const cell of ['upl-cell-name-in-file', 'upl-cell-target-field']) {
      expect(added.querySelector(`[data-testid="${cell}"]`)!.closest('td')!.classList.contains('upl-cell-error')).toBe(
        true,
      );
    }
  });

  it('shows every server error at once in the summary, on the tab, in the cell and at the sheet field', async () => {
    const { fixture } = await createFixture({
      saveError: invalid(
        ['sheets[0].headerRow', 'UPL_HEADER_ROW_INVALID'],
        ['sheets[0].columns[0].targetField', 'Size', 'size must be between 0 and 200'],
        ['sheets[0].columns[1].sourceUnit', 'UPL_UNIT_UNKNOWN'],
      ),
    });

    addValidColumn(fixture);
    press(fixture, one(fixture, 'upl-save'));

    const summary = one(fixture, 'upl-errors-summary')!;
    expect(summary.querySelectorAll('li').length).toBe(3);
    // The address names the field by the table header; a validator code gets its Russian text, not the server's.
    expect(summary.textContent).toContain(PACKAGED_RUSSIAN['upl.format.col.target_field']);
    expect(summary.textContent).toContain('колонка 1');
    expect(summary.textContent).toContain(PACKAGED_RUSSIAN['upl.err.Size']);
    expect(summary.textContent).not.toContain('size must be');
    // smt-control marks the field invalid after rendering.
    TestBed.tick();
    expect(inScreen(fixture.nativeElement).querySelector('#upl-header-row').getAttribute('aria-invalid')).toBe('true');
    expect(many(fixture, 'upl-tab-error').length).toBe(1);
    const unit = many(fixture, 'upl-column-row')[1].querySelector('[data-testid="upl-cell-source-unit"]')!;
    expect(unit.closest('td')!.classList.contains('upl-cell-error')).toBe(true);

    // A step that changes the columns drops the addresses, which no longer fit.
    press(fixture, many(fixture, 'upl-column-remove')[1]);
    expect(one(fixture, 'upl-errors-summary')).toBeNull();
    expect(one(fixture, 'upl-tab-error')).toBeNull();
  });

  it('shows a refusal without an address as text and keeps the edits', async () => {
    const { fixture, component } = await createFixture({
      saveError: { status: 403, code: 'permission_denied', detail: 'PERMISSION_DENIED' },
    });

    addValidColumn(fixture);
    press(fixture, one(fixture, 'upl-save'));

    expect(one(fixture, 'upl-action-error')!.textContent).toContain(PACKAGED_RUSSIAN['upl.err.PERMISSION_DENIED']);
    expect(component.store.model().sheets[0].columns.length).toBe(3);
    expect(one(fixture, 'upl-errors-summary')).toBeNull();
    expect(one(fixture, 'upl-conflict')).toBeNull();
  });

  it('keeps unsaved edits on a stale version and drops them when the reload is confirmed', async () => {
    const { fixture, api, component, settle } = await createFixture({
      saveError: {
        status: 409,
        code: 'CONFLICT',
        detail: 'Запись изменена другим пользователем. Обновите',
        messageKey: 'error.upl.stale_version',
      },
    });

    addValidColumn(fixture);
    press(fixture, one(fixture, 'upl-save'));
    expect(one(fixture, 'upl-conflict')).not.toBeNull();
    expect(component.store.model().sheets[0].columns.length).toBe(3);

    press(fixture, one(fixture, 'upl-conflict'));
    await settle();

    expect(api.getVersion).toHaveBeenCalledTimes(2);
    expect(one(fixture, 'upl-conflict')).toBeNull();
    expect(component.store.model().sheets[0].columns.length).toBe(2);
  });

  it('publishes a clean draft from today, saves a dirty one first and needs a date', async () => {
    const clean = await createFixture();
    press(clean.fixture, one(clean.fixture, 'upl-publish'));
    expect(clean.component.store.isPublishOpen()).toBe(true);

    confirmPublish(clean.fixture);
    expect(clean.api.publish).toHaveBeenCalledWith('7', '1', today());
    expect(clean.navigate).toHaveBeenCalledWith(['/upl/sources', '7']);
    expect(clean.api.saveDraft).not.toHaveBeenCalled();

    const dirty = await createFixture();
    addValidColumn(dirty.fixture);
    press(dirty.fixture, one(dirty.fixture, 'upl-publish'));
    expect(dirty.api.saveDraft).toHaveBeenCalledTimes(1);
    expect(dirty.component.store.isPublishOpen()).toBe(true);

    dirty.component.store.validFrom.set('');
    confirmPublish(dirty.fixture);
    expect(dirty.api.publish).not.toHaveBeenCalled();
    expect(dirty.component.store.isPublishOpen()).toBe(true);
    expect(publishDateError(dirty.fixture)!.textContent).toContain(PACKAGED_RUSSIAN['upl.err.NotNull']);
    const actions = one(dirty.fixture, 'upl-publish-actions')!;
    expect(actions.querySelector('[data-testid="form-submit"]')?.textContent?.trim()).toBe(
      PACKAGED_RUSSIAN['upl.format.publish'],
    );
    expect(actions.querySelector('[data-testid="form-cancel"]')?.textContent?.trim()).toBe(
      PACKAGED_RUSSIAN['common.cancel'],
    );
  });

  it('reports a date that does not fit under the field and a validation refusal in the summary', async () => {
    const late = await createFixture({
      publishError: {
        status: 409,
        code: 'CONFLICT',
        detail: 'Дата должна быть позже даты прежней версии',
        messageKey: 'error.upl.fnd_version_not_after_previous',
      },
    });
    press(late.fixture, one(late.fixture, 'upl-publish'));
    confirmPublish(late.fixture);
    expect(publishDateError(late.fixture)).not.toBeNull();
    expect(late.component.store.isPublishOpen()).toBe(true);

    const empty = await createFixture({
      version: { ...draftVersion(), sheets: [] },
      publishError: invalid(['sheets', 'UPL_NO_SHEETS']),
    });
    press(empty.fixture, one(empty.fixture, 'upl-publish'));
    confirmPublish(empty.fixture);
    expect(empty.component.store.isPublishOpen()).toBe(false);
    expect(one(empty.fixture, 'upl-errors-summary')!.textContent).toContain(PACKAGED_RUSSIAN['upl.err.UPL_NO_SHEETS']);
    expect(one(empty.fixture, 'upl-no-sheets')).not.toBeNull();
  });

  it('asks before leaving with unsaved changes, and not after a revert', async () => {
    const { fixture, component } = await createFixture();
    expect(component.canLeaveRecordPage()).toBe(true);

    press(fixture, one(fixture, 'upl-add-column'));
    expect(many(fixture, 'upl-column-row').length).toBe(3);
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(true));
    const decision = component.canLeaveRecordPage();
    let allowed: boolean | null = null;
    (decision as Observable<boolean>).subscribe((value) => (allowed = value));
    expect(confirm).toHaveBeenCalledWith(
      expect.objectContaining({ destructive: true, title: PACKAGED_RUSSIAN['upl.format.leave_title'] }),
    );
    expect(allowed).toBe(true);

    press(fixture, one(fixture, 'upl-revert'));
    expect(many(fixture, 'upl-column-row').length).toBe(2);
    expect(component.canLeaveRecordPage()).toBe(true);
  });

  describe('steps', () => {
    it('opens a draft with sheets on its sheets and shows one step at a time, in any order', async () => {
      const { fixture } = await createFixture();

      expect(visibleSteps(fixture)).toEqual(['sheets']);
      expect(stepButton(fixture, 'sheets').getAttribute('aria-current')).toBe('step');

      press(fixture, stepButton(fixture, 'publish'));
      expect(visibleSteps(fixture)).toEqual(['publish']);

      press(fixture, stepButton(fixture, 'file'));
      expect(visibleSteps(fixture)).toEqual(['file']);
      expect(stepButton(fixture, 'file').getAttribute('aria-current')).toBe('step');
      expect(stepButton(fixture, 'sheets').hasAttribute('aria-current')).toBe(false);
    });

    it('opens an empty draft on the file step', async () => {
      const { fixture } = await createFixture({ version: { ...draftVersion(), sheets: [] } });

      expect(visibleSteps(fixture)).toEqual(['file']);
      expect(stepButton(fixture, 'sheets').textContent).not.toContain(PACKAGED_RUSSIAN['ui.stepper.complete']);
    });

    it('shows on each step what another step changed in the shared draft', async () => {
      const { fixture, component } = await createFixture();
      expect(one(fixture, 'upl-review-sheets')!.textContent!.trim()).toBe('1');
      expect(one(fixture, 'upl-sheet-name')).not.toBeNull();

      press(fixture, one(fixture, 'upl-add-sheet'));
      press(fixture, stepButton(fixture, 'publish'));
      expect(one(fixture, 'upl-review-sheets')!.textContent!.trim()).toBe('2');
      expect(one(fixture, 'upl-review-unsaved')).not.toBeNull();

      // The file step edits the draft in place; the sheets step shows it once it is on screen again.
      press(fixture, stepButton(fixture, 'file'));
      component.store.model().fileKind = 'csv';
      component.store.model().matchColumnsBy = 'position';
      press(fixture, stepButton(fixture, 'sheets'));
      expect(one(fixture, 'upl-sheet-name')).toBeNull();
    });

    it('marks the step that holds errors and leads to it from the summary on any step', async () => {
      const { fixture } = await createFixture();
      addValidColumn(fixture, 'A', 'Поле 1');
      press(fixture, stepButton(fixture, 'file'));

      press(fixture, one(fixture, 'upl-save'));
      expect(visibleSteps(fixture)).toEqual(['sheets']);
      expect(one(fixture, 'upl-errors-summary')!.textContent).toContain(PACKAGED_RUSSIAN['upl.err.Pattern']);
      const sheets = stepButton(fixture, 'sheets');
      expect(sheets.textContent).toContain(PACKAGED_RUSSIAN['ui.stepper.error']);
      expect(sheets.textContent).toContain('Ошибок: 1');
      expect(stepButton(fixture, 'file').textContent).toContain(PACKAGED_RUSSIAN['ui.stepper.complete']);

      press(fixture, stepButton(fixture, 'publish'));
      expect(one(fixture, 'upl-review-state')!.textContent).toContain(
        PACKAGED_RUSSIAN['upl.format.review.draft_errors'],
      );

      press(fixture, one(fixture, 'upl-errors-summary')!.querySelector('button'));
      expect(visibleSteps(fixture)).toEqual(['sheets']);
    });

    it('sends a file level error to the file step', async () => {
      const { fixture } = await createFixture({ saveError: invalid(['delimiter', 'NotBlank']) });
      addValidColumn(fixture);

      press(fixture, one(fixture, 'upl-save'));

      expect(visibleSteps(fixture)).toEqual(['file']);
      expect(stepButton(fixture, 'file').textContent).toContain(PACKAGED_RUSSIAN['ui.stepper.error']);
    });
  });
});
