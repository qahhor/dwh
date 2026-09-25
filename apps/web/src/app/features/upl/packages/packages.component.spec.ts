import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NEVER, Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '../../../core/i18n/packaged-russian';
import { KeysetPage, ProblemDetail } from '../../../core/models/common.models';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { UplSource, UplSourceItem } from '../upl-api';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { QueryMetaService } from '../../../core/services/query-meta.service';
import { ListViewsApi } from '../../../shared/list-views/list-views';
import { PackageCardComponent } from './package-card.component';
import { PackagesComponent } from './packages.component';
import { UplPackageErrors, UplPackageItem, UplPackageUpload, UplPackagesApiService } from './packages-api';

function item(patch: Partial<UplPackageItem> = {}): UplPackageItem {
  return {
    id: '6f1b0d1e-0000-4000-8000-000000000001',
    sourceId: 3,
    sourceCode: 'cement.output',
    sourceName: 'Nalogi TEST',
    formatVersion: 2,
    periodFrom: '2026-01-01',
    periodTo: '2026-01-31',
    fileName: 'a_jan.xlsx',
    fileSizeBytes: 2048,
    uploadedBy: 'Ivanov TEST',
    uploadedAt: '2026-09-21T08:40:00Z',
    status: 'verified',
    rowsTotal: 120,
    rowsAccepted: 117,
    rowsRejected: 3,
    errorsTotal: 3,
    rejectCode: null,
    rejectParams: null,
    loadId: null,
    rawRows: null,
    ...patch
  };
}

function page(items: UplPackageItem[], hasMore = false, nextCursor: string | null = null): KeysetPage<UplPackageItem> {
  return { items, nextCursor, hasMore, totalEstimated: items.length } as unknown as KeysetPage<UplPackageItem>;
}

const field = (key: string, labelKey: string, type: QueryListMeta['fields'][number]['type'], extra: Partial<QueryListMeta['fields'][number]> = {}) =>
  ({ key, labelKey, type, ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null, ...extra }) as QueryListMeta['fields'][number];

/** What `query-meta/upl.packages` answers. */
const PACKAGES_META: QueryListMeta = {
  code: 'upl.packages',
  defaultSort: '-uploadedAt',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    field('uploadedAt', 'upl.pkg.col.uploaded_at', 'instant', { sortable: true, ops: ['gte'] }),
    field('sourceName', 'upl.pkg.col.source', 'text', { sortable: true }),
    field('sourceCode', 'upl.list.col.code', 'text', { defaultVisible: false }),
    field('periodFrom', 'upl.pkg.col.period', 'date', { sortable: true, ops: ['gte'] }),
    field('fileName', 'upl.pkg.col.file', 'text'),
    field('status', 'upl.pkg.col.status', 'enum', { enumValues: ['received', 'verified', 'rejected', 'applied'], enumLabelPrefix: 'upl.pkg.status.' }),
    field('rowsTotal', 'upl.pkg.col.rows', 'number', { nullable: true })
  ]
};

const noErrors: UplPackageErrors = { total: 0, shown: 0, items: [] };

const sourceList: UplSourceItem[] = [
  { id: 3, code: 'cement.output', name: 'Nalogi TEST', periodicity: 'month', lastPublishedVersion: 2, hasDraft: false },
  { id: 5, code: 'brick.output', name: 'Baza TEST', periodicity: 'quarter', lastPublishedVersion: 1, hasDraft: false }
];

interface FixtureOptions {
  pages?: Array<Observable<KeysetPage<UplPackageItem>>>;
  uploadResult?: Observable<UplPackageItem>;
  sourcesResult?: Array<Observable<KeysetPage<UplSourceItem>>>;
  canUpload?: boolean;
  canCreateSource?: boolean;
  query?: Record<string, string>;
  canApply?: boolean;
}

async function createFixture(options: FixtureOptions = {}) {
  const pages = options.pages ?? [of(page([item()]))];
  const sourcesResults = options.sourcesResult ?? [of({ items: sourceList, nextCursor: null, hasMore: false, totalEstimated: 2 } as unknown as KeysetPage<UplSourceItem>)];
  let listCall = 0;
  let sourcesCall = 0;
  const api = {
    list: vi.fn(() => pages[Math.min(listCall++, pages.length - 1)]),
    upload: vi.fn((request: UplPackageUpload) => options.uploadResult ?? of(item({ sourceId: request.sourceId }))),
    errors: vi.fn(() => of(noErrors)),
    searchSources: vi.fn((..._args: unknown[]) => sourcesResults[Math.min(sourcesCall++, sourcesResults.length - 1)]),
    source: vi.fn(() => of({ ...sourceList[1], id: 9, name: 'Created TEST' } as unknown as UplSource))
  };
  const permissions = {
    hasPermission: vi.fn((form: string, action: string) =>
      action === 'apply' ? options.canApply === true : action === 'create' ? options.canCreateSource === true : options.canUpload !== false
    )
  };
  const toast = { success: vi.fn(), error: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [PackagesComponent],
    providers: [
      provideRouter([]),
      { provide: QueryMetaService, useValue: { get: vi.fn(() => of(PACKAGES_META)) } },
      { provide: ListViewsApi, useValue: { list: vi.fn(() => of([])), create: vi.fn(), update: vi.fn(), remove: vi.fn() } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(options.query ?? {}) } } },
      { provide: UplPackagesApiService, useValue: api },
      { provide: PermissionService, useValue: permissions },
      { provide: ToastService, useValue: toast }
    ]
  }).compileComponents();
  const fixture = TestBed.createComponent(PackagesComponent);
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  fixture.detectChanges();
  return { fixture, api, permissions, toast, navigate };
}

function testId(fixture: ComponentFixture<PackagesComponent>, id: string): HTMLElement[] {
  return fixture.debugElement.queryAll(By.css(`[data-testid="${id}"]`)).map(node => node.nativeElement as HTMLElement);
}

function text(fixture: ComponentFixture<PackagesComponent>): string {
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

function click(fixture: ComponentFixture<PackagesComponent>, id: string): void {
  fixture.debugElement.query(By.css(`[data-testid="${id}"]`)).triggerEventHandler('onClick', null);
  fixture.detectChanges();
  // A full tick also runs the after-render phase, where smt-control links its error to the field.
  TestBed.tick();
}

function tableRows(fixture: ComponentFixture<PackagesComponent>): HTMLElement[] {
  return [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];
}

function clickRow(fixture: ComponentFixture<PackagesComponent>, index = 0): void {
  tableRows(fixture)[index].click();
  fixture.detectChanges();
}

function submitButton(fixture: ComponentFixture<PackagesComponent>): HTMLButtonElement {
  return fixture.debugElement.query(By.css('[data-testid="upl-pkg-submit"] button')).nativeElement as HTMLButtonElement;
}

/** Поля заполняем как человек — событиями, иначе `OnPush` не перерисует форму. */
function openSources(fixture: ComponentFixture<PackagesComponent>): HTMLElement[] {
  const trigger = testId(fixture, 'upl-pkg-source')[0].querySelector('button[role="combobox"]') as HTMLButtonElement;
  if (trigger.getAttribute('aria-expanded') !== 'true') trigger.click();
  fixture.detectChanges();
  return [...document.querySelectorAll('[role="option"]')] as HTMLElement[];
}

function selectSource(fixture: ComponentFixture<PackagesComponent>, index: number): void {
  openSources(fixture)[index].click();
  fixture.detectChanges();
}

/** The text field inside an smt-date-picker (or the element itself when it is one). */
function dateField(fixture: ComponentFixture<PackagesComponent>, id: string): HTMLInputElement {
  const host = testId(fixture, id)[0];
  return (host.querySelector('input') ?? host) as HTMLInputElement;
}

/** Types a date the way a person does: text, then Enter to commit it. */
function typeDate(fixture: ComponentFixture<PackagesComponent>, id: string, value: string): void {
  const input = dateField(fixture, id);
  input.value = value;
  input.dispatchEvent(new Event('input'));
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
  fixture.detectChanges();
}

/** `input type="file"` в jsdom не заполнить обычным путём — подменяем список файлов. */
function attachFile(fixture: ComponentFixture<PackagesComponent>, name = 'a_jan.xlsx'): void {
  const input = testId(fixture, 'upl-pkg-file')[0] as HTMLInputElement;
  Object.defineProperty(input, 'files', { value: [new File(['x'], name)], configurable: true });
  input.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

function fillForm(fixture: ComponentFixture<PackagesComponent>): void {
  selectSource(fixture, 1);
  typeDate(fixture, 'upl-pkg-period-from', '2026-01-01');
  typeDate(fixture, 'upl-pkg-period-to', '2026-01-31');
  attachFile(fixture);
}

function isDisabled(element: HTMLElement): boolean {
  const control = element.tagName === 'SMT-SELECT' ? element.querySelector('button[role="combobox"]') : element;
  return (control as HTMLInputElement | HTMLSelectElement).disabled;
}

function problem(status: number, code: string, detail: string, errors?: ProblemDetail['errors']): ProblemDetail {
  return { title: 'error', status, code, detail, errors };
}

/** The error smt-control shows for a field, found the way assistive technology finds it: through aria-describedby. */
function fieldError(root: HTMLElement, fieldId: string): HTMLElement | null {
  const field = root.querySelector('#' + fieldId);
  const ids = (field?.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids.map(id => root.querySelector<HTMLElement>('#' + id)).find(node => node?.classList.contains('smt-control__error')) ?? null;
}

describe('PackagesComponent', () => {
  it('без права загрузки формы нет, источники не запрашиваются, пустой текст короткий', async () => {
    const { fixture, api } = await createFixture({ canUpload: false, pages: [of(page([]))] });

    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(0);
    expect(api.searchSources).not.toHaveBeenCalled();
    expect(text(fixture)).toContain(PACKAGED_RUSSIAN['upl.pkg.empty']);
    expect(text(fixture)).not.toContain(PACKAGED_RUSSIAN['upl.pkg.empty_hint']);
  });

  it('с правом загрузки форма есть, источник ищется на сервере при открытии, пустой текст подсказывает что делать', async () => {
    const { fixture, api } = await createFixture({ pages: [of(page([]))] });

    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(1);
    expect(api.searchSources).not.toHaveBeenCalled();
    TestBed.tick(); // smt-control points its label at the field after render
    const label = fixture.nativeElement.querySelector('label[for="upl-pkg-source-field"]') as HTMLLabelElement;
    expect(document.getElementById(label.htmlFor)?.getAttribute('role')).toBe('combobox');

    const options = openSources(fixture);
    expect(api.searchSources).toHaveBeenCalledWith('', null, 20);
    expect(options[0].textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.form.source_placeholder']);
    expect(options[1].getAttribute('aria-label')).toBe(
      `Nalogi TEST, ${PACKAGED_RUSSIAN['upl.list.col.code']}: cement.output, ${PACKAGED_RUSSIAN['upl.list.col.periodicity']}: ${PACKAGED_RUSSIAN['upl.periodicity.month']}, ${PACKAGED_RUSSIAN['upl.list.col.published_version']}: 2`
    );
    expect(text(fixture)).toContain(PACKAGED_RUSSIAN['upl.pkg.empty_hint']);
  });

  it('поиск источника идёт на сервер по коду или названию', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, api } = await createFixture();
      openSources(fixture);
      const search = document.querySelector('.smt-select__search-input') as HTMLInputElement;
      search.value = 'cem';
      search.dispatchEvent(new Event('input'));
      vi.advanceTimersByTime(400);
      fixture.detectChanges();

      expect(api.searchSources).toHaveBeenLastCalledWith('cem', null, 20);
    } finally {
      vi.useRealTimers();
    }
  });

  it('«создать из поля»: только с правом, ведёт к созданию источника с набранным названием', async () => {
    vi.useFakeTimers();
    try {
      const typeInSearch = (fixture: ComponentFixture<PackagesComponent>, value: string) => {
        const search = document.querySelector('.smt-select__search-input') as HTMLInputElement;
        search.value = value;
        search.dispatchEvent(new Event('input'));
        vi.advanceTimersByTime(400);
        fixture.detectChanges();
      };
      const without = await createFixture();
      openSources(without.fixture);
      typeInSearch(without.fixture, 'Новый');
      expect(document.querySelector('[role="option"][id$="-create"]')).toBeNull();
      document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());

      TestBed.resetTestingModule();
      const { fixture, navigate } = await createFixture({ canCreateSource: true });
      openSources(fixture);
      typeInSearch(fixture, 'Новый');
      const create = document.querySelector('[role="option"][id$="-create"]') as HTMLElement;
      expect(create.textContent).toContain('Создать «Новый»');
      create.click();

      expect(navigate).toHaveBeenCalledWith(['/upl/sources'], { queryParams: { create: 'Новый', returnTo: 'packages' } });
    } finally {
      vi.useRealTimers();
    }
  });

  it('источник, созданный из формы, приходит выбранным', async () => {
    const { fixture, api } = await createFixture({ query: { source: '9' } });

    expect(api.source).toHaveBeenCalledWith('9');
    expect(fixture.componentInstance.form.sourceId).toBe(9);
    fixture.detectChanges();
    expect(testId(fixture, 'upl-pkg-source')[0].textContent).toContain('Created TEST');
  });

  it('кнопка «Загрузить» неактивна, пока не заполнены все четыре поля', async () => {
    const { fixture } = await createFixture();

    expect(submitButton(fixture).disabled).toBe(true);
    selectSource(fixture, 1);
    typeDate(fixture, 'upl-pkg-period-from', '2026-01-01');
    typeDate(fixture, 'upl-pkg-period-to', '2026-01-31');
    expect(submitButton(fixture).disabled).toBe(true);

    fillForm(fixture);
    expect(submitButton(fixture).disabled).toBe(false);
  });

  it('успешная отправка: запрос с четырьмя значениями, тост, файл очищен, список перечитан', async () => {
    const { fixture, api, toast } = await createFixture();
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(api.upload).toHaveBeenCalledTimes(1);
    expect(api.upload.mock.calls[0][0]).toMatchObject({
      sourceId: 3,
      periodFrom: '2026-01-01',
      periodTo: '2026-01-31'
    });
    expect(api.upload.mock.calls[0][0].file.name).toBe('a_jan.xlsx');
    expect(toast.success).toHaveBeenCalledWith(PACKAGED_RUSSIAN['upl.pkg.toast.accepted']);
    expect(fixture.componentInstance.form.file).toBeNull();
    expect(fixture.componentInstance.form.sourceId).toBe(3);
    expect(fixture.componentInstance.form.periodFrom).toBe('2026-01-01');
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('во время отправки поля и кнопка заблокированы', async () => {
    const { fixture } = await createFixture({ uploadResult: NEVER });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(submitButton(fixture).disabled).toBe(true);
    expect(isDisabled(testId(fixture, 'upl-pkg-source')[0])).toBe(true);
    expect(isDisabled(dateField(fixture, 'upl-pkg-period-from'))).toBe(true);
    expect(isDisabled(dateField(fixture, 'upl-pkg-period-to'))).toBe(true);
    expect(isDisabled(testId(fixture, 'upl-pkg-file')[0])).toBe(true);
  });

  it('отказ 422 по трём полям: каждая ошибка у своего поля, все разом', async () => {
    const rejection = problem(422, 'validation_error', 'invalid', [
      { field: 'sourceId', code: 'UPL_PKG_SOURCE_REQUIRED', message: 'source' },
      { field: 'periodTo', code: 'UPL_PKG_PERIOD_ORDER', message: 'period' },
      { field: 'file', code: 'UPL_PKG_FILE_NOT_XLSX', message: 'file' }
    ]);
    const { fixture } = await createFixture({ uploadResult: throwError(() => rejection) });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(fieldError(fixture.nativeElement, 'upl-pkg-source-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_PKG_SOURCE_REQUIRED']
    );
    expect(fieldError(fixture.nativeElement, 'upl-pkg-period-to-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_PKG_PERIOD_ORDER']
    );
    expect(fieldError(fixture.nativeElement, 'upl-pkg-file-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_PKG_FILE_NOT_XLSX']
    );
    expect(testId(fixture, 'upl-pkg-err-form')).toHaveLength(0);
  });

  it('источник не найден (404) показан под полем «Источник»', async () => {
    const { fixture } = await createFixture({
      uploadResult: throwError(() => problem(404, 'not_found', 'UPL_SOURCE_NOT_FOUND'))
    });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(fieldError(fixture.nativeElement, 'upl-pkg-source-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_SOURCE_NOT_FOUND']
    );
  });

  it('нет анкеты на дату начала периода (409) показано под полем «Источник»', async () => {
    const { fixture } = await createFixture({
      uploadResult: throwError(() => problem(409, 'conflict', 'UPL_PKG_NO_FORMAT_AT_DATE'))
    });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(fieldError(fixture.nativeElement, 'upl-pkg-source-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_PKG_NO_FORMAT_AT_DATE']
    );
  });

  it('наш отказ по размеру (413) показан под полем «Файл»', async () => {
    const { fixture } = await createFixture({
      uploadResult: throwError(() => problem(413, 'file_size_exceeded', 'UPL_PKG_FILE_TOO_LARGE'))
    });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(fieldError(fixture.nativeElement, 'upl-pkg-file-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_PKG_FILE_TOO_LARGE']
    );
  });

  it('отказ по размеру от каркаса (413 с чужим подкодом) тоже под полем «Файл»', async () => {
    const { fixture } = await createFixture({
      uploadResult: throwError(() => problem(413, 'FILE_SIZE_EXCEEDED', 'file too large'))
    });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(fieldError(fixture.nativeElement, 'upl-pkg-file-field')?.textContent).toContain(
      PACKAGED_RUSSIAN['upl.err.UPL_PKG_FILE_TOO_LARGE']
    );
    expect(testId(fixture, 'upl-pkg-err-form')).toHaveLength(0);
  });

  it('неизвестный код уходит полосой над формой вместе с кодом', async () => {
    const { fixture } = await createFixture({
      uploadResult: throwError(() => problem(503, 'service_unavailable', 'UPL_PKG_FROM_FUTURE'))
    });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    const bar = testId(fixture, 'upl-pkg-err-form')[0].textContent ?? '';
    expect(bar).toContain('UPL_PKG_FROM_FUTURE');
    expect(bar).toContain('(service_unavailable)');
  });

  it('сбой поиска источников: сообщение в списке и «Повторить» запрашивает снова', async () => {
    const { fixture, api } = await createFixture({
      sourcesResult: [
        throwError(() => ({ status: 503 })),
        of({ items: sourceList, nextCursor: null, hasMore: false, totalEstimated: 2 } as unknown as KeysetPage<UplSourceItem>)
      ]
    });

    openSources(fixture);
    const alert = document.querySelector('.smt-select__error[role="alert"]') as HTMLElement;
    expect(alert).not.toBeNull();
    (alert.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.searchSources).toHaveBeenCalledTimes(2);
    expect(document.querySelector('.smt-select__error')).toBeNull();
    expect(document.querySelectorAll('[role="option"]')).toHaveLength(3);
  });

  it('колонки берутся из метаданных реестра; поле-фильтр без колонки; по умолчанию сначала новые', async () => {
    const { fixture, api } = await createFixture();

    const headers = [...fixture.nativeElement.querySelectorAll('[role="columnheader"] span.truncate')].map(cell => (cell as HTMLElement).textContent?.trim());
    expect(headers).toEqual([
      PACKAGED_RUSSIAN['upl.pkg.col.uploaded_at'], PACKAGED_RUSSIAN['upl.pkg.col.source'], PACKAGED_RUSSIAN['upl.pkg.col.period'],
      PACKAGED_RUSSIAN['upl.pkg.col.file'], PACKAGED_RUSSIAN['upl.pkg.col.status'], PACKAGED_RUSSIAN['upl.pkg.col.rows']
    ]);
    expect(api.list).toHaveBeenCalledWith(50, null, { sort: { field: 'uploadedAt', descending: true }, conditions: [] });
    expect(fixture.nativeElement.querySelector('[data-testid="filter-trigger"]')).not.toBeNull();
  });

  it('пока список грузится, это объявляется, строк нет', async () => {
    const { fixture } = await createFixture({ pages: [NEVER] });

    expect(fixture.nativeElement.querySelector('[data-server-table-status]')).not.toBeNull();
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(0);
  });

  it('сбой списка: таблица предлагает повторить именно этот запрос', async () => {
    const { fixture, api } = await createFixture({
      pages: [throwError(() => ({ status: 503 })), of(page([item()]))]
    });

    const alert = fixture.nativeElement.querySelector('ui-server-table [role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.load_error']);
    (alert.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.list).toHaveBeenCalledTimes(2);
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(1);
  });

  it('следующая страница запрашивается курсором того же запроса', async () => {
    const { fixture, api } = await createFixture({
      pages: [
        of(page([item()], true, 'cursor-2')),
        of(page([item({ id: '6f1b0d1e-0000-4000-8000-000000000002', fileName: 'b_feb.xlsx' })]))
      ]
    });

    (fixture.nativeElement.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.list).toHaveBeenLastCalledWith(50, 'cursor-2', { sort: { field: 'uploadedAt', descending: true }, conditions: [] });
    expect(tableRows(fixture).map(row => row.textContent)).toEqual([expect.stringContaining('b_feb.xlsx')]);
  });

  it('строка списка показывает дату, источник, период, файл, статус и строки', async () => {
    const { fixture } = await createFixture();

    const row = tableRows(fixture)[0].textContent ?? '';
    expect(row).toContain('Nalogi TEST');
    expect(row).toContain('01.01.2026–31.01.2026');
    expect(row).toContain('a_jan.xlsx');
    expect(row).toContain(PACKAGED_RUSSIAN['upl.pkg.status.verified']);
    expect(row).toContain('120 / 117 / 3');
  });

  it('у непроверенных и отклонённых загрузок вместо строк прочерк', async () => {
    const unchecked = item({
      id: 'a',
      status: 'received',
      rowsTotal: null,
      rowsAccepted: null,
      rowsRejected: null,
      errorsTotal: null
    });
    const rejected = item({
      id: 'b',
      status: 'rejected',
      rowsTotal: null,
      rowsAccepted: null,
      rowsRejected: null,
      rejectCode: 'UPL_PKG_UNREADABLE'
    });
    const { fixture } = await createFixture({ pages: [of(page([unchecked, rejected]))] });

    const rows = tableRows(fixture);
    expect(rows[0].textContent).toContain('—');
    expect(rows[0].textContent).not.toContain('120');
    expect(rows[1].textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.status.rejected']);
    expect(rows[1].textContent).toContain('—');
  });

  it('у бейджа «получен» подсказка о проверке', async () => {
    const received = item({ status: 'received', rowsTotal: null, rowsAccepted: null, rowsRejected: null });
    const { fixture } = await createFixture({ pages: [of(page([received, item({ id: 'c' })]))] });

    const badges = tableRows(fixture).map(row => row.querySelector('ui-badge') as HTMLElement);
    expect(badges[0].getAttribute('title')).toBe(PACKAGED_RUSSIAN['upl.pkg.status.received_hint']);
    expect(badges[1].getAttribute('title')).toBeNull();
  });

  it('щелчок по строке открывает карточку вместо формы и списка', async () => {
    const { fixture } = await createFixture();

    clickRow(fixture);

    expect(fixture.debugElement.query(By.css('app-upl-package-card'))).not.toBeNull();
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(0);
    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(0);
    expect(testId(fixture, 'upl-pkg-card-meta')[0].textContent).toContain('Nalogi TEST');
  });

  it('«К списку загрузок» возвращает список', async () => {
    const { fixture } = await createFixture();
    clickRow(fixture);

    click(fixture, 'upl-pkg-back');

    expect(fixture.debugElement.query(By.css('app-upl-package-card'))).toBeNull();
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(1);
    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(1);
  });

  it('«Обновить» в карточке перечитывает список и обновляет саму карточку', async () => {
    const { fixture, api } = await createFixture({
      pages: [of(page([item({ status: 'received', rowsTotal: null, rowsAccepted: null, rowsRejected: null })])), of(page([item()]))]
    });
    clickRow(fixture);
    expect(testId(fixture, 'upl-pkg-checking')).toHaveLength(1);

    click(fixture, 'upl-pkg-card-refresh');

    expect(api.list).toHaveBeenCalledTimes(2);
    expect(testId(fixture, 'upl-pkg-checking')).toHaveLength(0);
    expect(testId(fixture, 'upl-pkg-counters')).toHaveLength(1);
  });

  it('пропавшая из порции загрузка остаётся в карточке прежней', async () => {
    const { fixture } = await createFixture({
      pages: [of(page([item()])), of(page([item({ id: 'other', fileName: 'b_q1.xlsx' })]))]
    });
    clickRow(fixture);

    click(fixture, 'upl-pkg-card-refresh');

    expect(fixture.debugElement.query(By.css('app-upl-package-card'))).not.toBeNull();
    expect(text(fixture)).toContain('a_jan.xlsx');
    expect(text(fixture)).not.toContain('b_q1.xlsx');
  });

  it('открытая карточка получает право «Применить» только при праве apply', async () => {
    const withRight = await createFixture({ canApply: true });
    clickRow(withRight.fixture);
    const cardWithRight = withRight.fixture.debugElement.query(By.directive(PackageCardComponent));
    expect(cardWithRight.componentInstance.canApply).toBe(true);

    TestBed.resetTestingModule();
    const withoutRight = await createFixture();
    clickRow(withoutRight.fixture);
    const cardWithoutRight = withoutRight.fixture.debugElement.query(By.directive(PackageCardComponent));
    expect(cardWithoutRight.componentInstance.canApply).toBe(false);
  });

  it('применённый пакет из карточки показывается в ней, а список перечитывается', async () => {
    const appliedAfterReload = item({ status: 'applied', rowsTotal: 10, rawRows: 10 });
    const { fixture, api } = await createFixture({
      canApply: true,
      pages: [of(page([item()])), of(page([appliedAfterReload]))]
    });
    clickRow(fixture);
    const listCallsBefore = api.list.mock.calls.length;
    const card = fixture.debugElement.query(By.directive(PackageCardComponent));

    card.triggerEventHandler('applied', item({ status: 'applied', loadId: 42, rawRows: 120 }));
    fixture.detectChanges();

    const cardAfter = fixture.debugElement.query(By.directive(PackageCardComponent));
    expect(cardAfter.componentInstance.item.status).toBe('applied');
    expect(api.list).toHaveBeenCalledTimes(listCallsBefore + 1);
  });
});
