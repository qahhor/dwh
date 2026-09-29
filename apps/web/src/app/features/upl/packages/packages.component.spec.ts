import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { KeysetPage } from '@core/models/common.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { metaField, registryProviders } from '@testing/registry-meta';
import { UplSource, UplSourceItem } from '../upl-api';
import { PackageCardComponent } from './package-card.component';
import { PackagesComponent } from './packages.component';
import { UplPackageItem, UplPackageUpload, UplPackagesApiService } from './packages-api';

// How a refusal is spread over the fields is pinned by packages-errors.spec, the card by its own spec.

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
    ...patch,
  };
}

const RECEIVED: Partial<UplPackageItem> = {
  status: 'received',
  rowsTotal: null,
  rowsAccepted: null,
  rowsRejected: null,
};
const DEFAULT_QUERY = { sort: { field: 'uploadedAt', descending: true }, conditions: [], match: 'all' };

function page<T>(items: T[], hasMore = false, nextCursor: string | null = null): KeysetPage<T> {
  return { items, nextCursor, hasMore, totalEstimated: items.length } as unknown as KeysetPage<T>;
}

/** What `query-meta/upl.packages` answers. */
const PACKAGES_META = {
  code: 'upl.packages',
  defaultSort: '-uploadedAt',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    metaField('uploadedAt', 'upl.pkg.col.uploaded_at', 'instant', { sortable: true, ops: ['gte'] }),
    metaField('sourceName', 'upl.pkg.col.source', 'text', { sortable: true }),
    metaField('sourceCode', 'upl.list.col.code', 'text', { defaultVisible: false }),
    metaField('periodFrom', 'upl.pkg.col.period', 'date', { sortable: true, ops: ['gte'] }),
    metaField('fileName', 'upl.pkg.col.file', 'text'),
    metaField('status', 'upl.pkg.col.status', 'enum', {
      enumValues: ['received', 'verified', 'rejected', 'applied'],
      enumLabelPrefix: 'upl.pkg.status.',
    }),
    metaField('rowsTotal', 'upl.pkg.col.rows', 'number', { nullable: true }),
  ],
} as QueryListMeta;

const sourceList: UplSourceItem[] = [
  { id: 3, code: 'cement.output', name: 'Nalogi TEST', periodicity: 'month', lastPublishedVersion: 2, hasDraft: false },
  { id: 5, code: 'brick.output', name: 'Baza TEST', periodicity: 'quarter', lastPublishedVersion: 1, hasDraft: false },
];

interface FixtureOptions {
  pages?: Array<Observable<KeysetPage<UplPackageItem>>>;
  uploads?: Array<Observable<UplPackageItem>>;
  sources?: Array<Observable<KeysetPage<UplSourceItem>>>;
  rights?: string[];
  query?: Record<string, string>;
}

/** Answers the n-th call with the n-th result, the last one ever after. */
function inTurn<T>(results: T[]): () => T {
  let call = 0;
  return () => results[Math.min(call++, results.length - 1)];
}

async function createFixture(options: FixtureOptions = {}) {
  const [nextPage, nextUpload] = [
    inTurn(options.pages ?? [of(page([item()]))]),
    inTurn(options.uploads ?? [of(item())]),
  ];
  const nextSources = inTurn(options.sources ?? [of(page(sourceList))]);
  const api = {
    list: vi.fn(() => nextPage()),
    upload: vi.fn((_request: UplPackageUpload) => nextUpload()),
    errors: vi.fn(() => of({ total: 0, shown: 0, items: [] })),
    get: vi.fn((id: string) => of({ ...item(), id })),
    searchSources: vi.fn((..._args: unknown[]) => nextSources()),
    source: vi.fn(() => of({ ...sourceList[1], id: 9, name: 'Created TEST' } as unknown as UplSource)),
  };
  const rights = options.rights ?? ['upload'];
  const toast = { success: vi.fn(), error: vi.fn() };
  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [PackagesComponent],
    providers: [
      provideRouter([]),
      ...registryProviders(PACKAGES_META),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(options.query ?? {}) } } },
      { provide: UplPackagesApiService, useValue: api },
      {
        provide: PermissionService,
        useValue: { hasPermission: (_form: string, action: string) => rights.includes(action) },
      },
      { provide: ToastService, useValue: toast },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(PackagesComponent);
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  fixture.detectChanges();
  return { fixture, api, toast, navigate, component: fixture.componentInstance };
}

type Fixture = ComponentFixture<PackagesComponent>;
const all = (fixture: Fixture, selector: string) =>
  [...fixture.nativeElement.querySelectorAll(selector)] as HTMLElement[];
const testId = (fixture: Fixture, id: string) => all(fixture, `[data-testid="${id}"]`);
const text = (fixture: Fixture) => (fixture.nativeElement as HTMLElement).textContent ?? '';
const card = (fixture: Fixture) => fixture.debugElement.query(By.directive(PackageCardComponent));
const tableRows = (fixture: Fixture) => all(fixture, '[role="rowgroup"] > [role="row"]');
const submitButton = (fixture: Fixture) => all(fixture, 'button[data-testid="upl-pkg-submit"]')[0] as HTMLButtonElement;
/** The text field inside an smt-date-picker (or the element itself when it is one). */
const dateField = (fixture: Fixture, id: string) =>
  testId(fixture, id)[0].querySelector('input') ?? testId(fixture, id)[0];

function click(fixture: Fixture, id: string): void {
  fixture.debugElement.query(By.css(`[data-testid="${id}"]`)).triggerEventHandler('click', new MouseEvent('click'));
  fixture.detectChanges();
  // A full tick also runs the after-render phase, where smt-control links its error to the field.
  TestBed.tick();
}

function clickRow(fixture: Fixture): void {
  tableRows(fixture)[0].click();
  fixture.detectChanges();
}

/** Поля заполняем как человек — событиями, иначе `OnPush` не перерисует форму. */
function openSources(fixture: Fixture): HTMLElement[] {
  const trigger = testId(fixture, 'upl-pkg-source')[0].querySelector('button[role="combobox"]') as HTMLButtonElement;
  if (trigger.getAttribute('aria-expanded') !== 'true') trigger.click();
  fixture.detectChanges();
  return [...document.querySelectorAll('[role="option"]')] as HTMLElement[];
}

/** Types a date the way a person does: text, then Enter to commit it. */
function typeDate(fixture: Fixture, id: string, value: string): void {
  const input = dateField(fixture, id) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
  fixture.detectChanges();
}

/** `input type="file"` в jsdom не заполнить обычным путём — подменяем список файлов. */
function attachFile(fixture: Fixture): void {
  const input = testId(fixture, 'upl-pkg-file')[0];
  Object.defineProperty(input, 'files', { value: [new File(['x'], 'a_jan.xlsx')], configurable: true });
  input.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

function fillForm(fixture: Fixture, parts = 4): void {
  openSources(fixture)[1].click();
  fixture.detectChanges();
  typeDate(fixture, 'upl-pkg-period-from', '2026-01-01');
  typeDate(fixture, 'upl-pkg-period-to', '2026-01-31');
  if (parts > 3) attachFile(fixture);
}

/** The error smt-control shows for a field, found the way assistive technology finds it: through aria-describedby. */
function fieldError(fixture: Fixture, fieldId: string): string | null | undefined {
  const field = fixture.nativeElement.querySelector('#' + fieldId) as HTMLElement;
  const ids = (field.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids
    .map((id: string) => fixture.nativeElement.querySelector('#' + id) as HTMLElement | null)
    .find((node: HTMLElement | null) => node?.classList.contains('smt-control__error'))?.textContent;
}

describe('PackagesComponent', () => {
  it('без права загрузки формы нет, источники не запрашиваются, пустой текст короткий', async () => {
    const { fixture, api } = await createFixture({ rights: [], pages: [of(page([]))] });

    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(0);
    expect(api.searchSources).not.toHaveBeenCalled();
    expect(text(fixture)).toContain(PACKAGED_RUSSIAN['upl.pkg.empty']);
    expect(text(fixture)).not.toContain(PACKAGED_RUSSIAN['upl.pkg.empty_hint']);
  });

  it('загрузка: источник ищется при открытии, шаблон его версии, кнопка оживает с четырьмя полями, отправка', async () => {
    const sent = new Subject<UplPackageItem>();
    const { fixture, api, toast, component } = await createFixture({ pages: [of(page([]))], uploads: [sent] });
    expect(text(fixture)).toContain(PACKAGED_RUSSIAN['upl.pkg.empty_hint']);
    expect(api.searchSources).not.toHaveBeenCalled();
    TestBed.tick(); // smt-control points its label at the field after render
    const label = fixture.nativeElement.querySelector('label[for="upl-pkg-source-field"]') as HTMLLabelElement;
    expect(document.getElementById(label.htmlFor)?.getAttribute('role')).toBe('combobox');

    const options = openSources(fixture);
    expect(api.searchSources).toHaveBeenCalledWith('', null, 20);
    expect(options[0].textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.form.source_placeholder']);
    const column = (key: string, value: string) => `${PACKAGED_RUSSIAN[key]}: ${value}`;
    expect(options[1].getAttribute('aria-label')).toBe(
      `Nalogi TEST, ${column('upl.list.col.code', 'cement.output')}, ${column('upl.list.col.periodicity', PACKAGED_RUSSIAN['upl.periodicity.month'])}, ${column('upl.list.col.published_version', '2')}`,
    );
    expect(testId(fixture, 'upl-pkg-template')).toHaveLength(0);
    fillForm(fixture, 3);
    const link = testId(fixture, 'upl-pkg-template')[0] as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/v1/upl/sources/3/format-versions/2/template?lang=ru');
    expect(link.hasAttribute('download')).toBe(true);
    expect(link.textContent).toContain('анкета версии 2');
    expect(submitButton(fixture).disabled).toBe(true);
    attachFile(fixture);
    expect(submitButton(fixture).disabled).toBe(false);

    click(fixture, 'upl-pkg-submit');
    // While the file is on its way, nothing in the form can change.
    const source = testId(fixture, 'upl-pkg-source')[0].querySelector('button[role="combobox"]');
    const [from, to] = [dateField(fixture, 'upl-pkg-period-from'), dateField(fixture, 'upl-pkg-period-to')];
    const locked = [submitButton(fixture), source, from, to, testId(fixture, 'upl-pkg-file')[0]] as HTMLInputElement[];
    expect(locked.map((control) => control.disabled)).toEqual([true, true, true, true, true]);
    sent.next(item());
    sent.complete();

    expect(api.upload.mock.calls[0][0]).toMatchObject({
      sourceId: 3,
      periodFrom: '2026-01-01',
      periodTo: '2026-01-31',
    });
    expect(api.upload.mock.calls[0][0].file.name).toBe('a_jan.xlsx');
    expect(toast.success).toHaveBeenCalledWith(PACKAGED_RUSSIAN['upl.pkg.toast.accepted']);
    // Only the file is cleared: the source and the period serve the next file.
    expect(component.form).toEqual(expect.objectContaining({ file: null, sourceId: 3, periodFrom: '2026-01-01' }));
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('поиск на сервере по коду или названию; «создать из поля» только с правом и с набранным названием', async () => {
    vi.useFakeTimers();
    const typeInSearch = (fixture: Fixture, value: string) => {
      openSources(fixture);
      const search = document.querySelector('.smt-select__search-input') as HTMLInputElement;
      search.value = value;
      search.dispatchEvent(new Event('input'));
      vi.advanceTimersByTime(400);
      fixture.detectChanges();
      return document.querySelector('[role="option"][id$="-create"]') as HTMLElement | null;
    };
    try {
      const without = await createFixture({ sources: [throwError(() => ({ status: 503 })), of(page(sourceList))] });
      // A failed search says so inside the list, and its retry asks again.
      openSources(without.fixture);
      (document.querySelector('.smt-select__error[role="alert"] button') as HTMLButtonElement).click();
      without.fixture.detectChanges();
      expect(document.querySelector('.smt-select__error')).toBeNull();
      expect(document.querySelectorAll('[role="option"]')).toHaveLength(3);
      expect(typeInSearch(without.fixture, 'cem')).toBeNull();
      expect(without.api.searchSources).toHaveBeenCalledTimes(3);
      expect(without.api.searchSources).toHaveBeenLastCalledWith('cem', null, 20);
      document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());

      const { fixture, navigate } = await createFixture({ rights: ['upload', 'create'] });
      const create = typeInSearch(fixture, 'Новый')!;
      expect(create.textContent).toContain('Создать «Новый»');
      create.click();
      expect(navigate).toHaveBeenCalledWith(['/upl/sources'], {
        queryParams: { create: 'Новый', returnTo: 'packages' },
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('источник, созданный из формы, приходит выбранным; ссылка из обзора данных открывает карточку', async () => {
    const created = await createFixture({ query: { source: '9' } });
    expect(created.api.source).toHaveBeenCalledWith('9');
    expect(created.component.form.sourceId).toBe(9);
    created.fixture.detectChanges();
    expect(testId(created.fixture, 'upl-pkg-source')[0].textContent).toContain('Created TEST');

    const linked = await createFixture({ query: { open: 'pkg-42' } });
    expect(linked.api.get).toHaveBeenCalledWith('pkg-42');
    expect(linked.component.selected()?.id).toBe('pkg-42');
  });

  it('отказ показан у своих полей, все разом, а неизвестный код — полосой над формой вместе с кодом', async () => {
    const errors = [
      { field: 'sourceId', code: 'UPL_PKG_SOURCE_REQUIRED', message: 'source' },
      { field: 'periodTo', code: 'UPL_PKG_PERIOD_ORDER', message: 'period' },
      { field: 'file', code: 'UPL_PKG_FILE_NOT_XLSX', message: 'file' },
    ];
    const { fixture } = await createFixture({
      uploads: [
        throwError(() => ({ title: 'error', status: 422, code: 'validation_error', detail: 'invalid', errors })),
        throwError(() => ({ title: 'error', status: 503, code: 'service_unavailable', detail: 'UPL_PKG_FROM_FUTURE' })),
      ],
    });
    fillForm(fixture);

    click(fixture, 'upl-pkg-submit');

    expect(fieldError(fixture, 'upl-pkg-source-field')).toContain(PACKAGED_RUSSIAN['upl.err.UPL_PKG_SOURCE_REQUIRED']);
    expect(fieldError(fixture, 'upl-pkg-period-to-field')).toContain(PACKAGED_RUSSIAN['upl.err.UPL_PKG_PERIOD_ORDER']);
    expect(fieldError(fixture, 'upl-pkg-file-field')).toContain(PACKAGED_RUSSIAN['upl.err.UPL_PKG_FILE_NOT_XLSX']);
    expect(testId(fixture, 'upl-pkg-err-form')).toHaveLength(0);

    click(fixture, 'upl-pkg-submit');

    expect(testId(fixture, 'upl-pkg-err-form')[0].textContent).toContain('UPL_PKG_FROM_FUTURE (service_unavailable)');
    expect(fieldError(fixture, 'upl-pkg-source-field')).toBeUndefined();
  });

  it('колонки из метаданных, по умолчанию сначала новые; строка со всеми полями, у «получен» подсказка', async () => {
    const { fixture, api } = await createFixture({ pages: [of(page([item(), item({ ...RECEIVED, id: 'c' })]))] });

    expect(all(fixture, '[role="columnheader"] span.truncate').map((cell) => cell.textContent?.trim())).toEqual(
      ['uploaded_at', 'source', 'period', 'file', 'status', 'rows'].map(
        (key) => PACKAGED_RUSSIAN[`upl.pkg.col.${key}`],
      ),
    );
    expect(api.list).toHaveBeenCalledWith(50, null, DEFAULT_QUERY);
    expect(testId(fixture, 'filter-trigger')).toHaveLength(1);
    const [verified, received] = tableRows(fixture);
    for (const part of ['Nalogi TEST', '01.01.2026–31.01.2026', 'a_jan.xlsx', '120 / 117 / 3']) {
      expect(verified.textContent).toContain(part);
    }
    expect(verified.textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.status.verified']);
    expect(verified.querySelector('smt-badge')!.getAttribute('title')).toBeNull();
    const hint = PACKAGED_RUSSIAN['upl.pkg.status.received_hint'];
    expect(received.querySelector('smt-badge')!.getAttribute('title')).toBe(hint);
  });

  it('сбой списка: повтор именно этого запроса; следующая страница — курсором того же запроса', async () => {
    const second = item({ id: '6f1b0d1e-0000-4000-8000-000000000002', fileName: 'b_feb.xlsx' });
    const { fixture, api } = await createFixture({
      pages: [throwError(() => ({ status: 503 })), of(page([item()], true, 'cursor-2')), of(page([second]))],
    });

    const alert = all(fixture, 'ui-server-table [role="alert"]')[0];
    expect(alert.textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.load_error']);
    (alert.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(1);

    all(fixture, 'button[aria-label="Следующая страница"]')[0].click();
    fixture.detectChanges();
    expect(api.list).toHaveBeenLastCalledWith(50, 'cursor-2', DEFAULT_QUERY);
    expect(tableRows(fixture).map((row) => row.textContent)).toEqual([expect.stringContaining('b_feb.xlsx')]);
  });

  it('карточка вместо формы и списка; «Обновить» и «Применить» перечитывают список; «К списку» возвращает', async () => {
    const withoutRight = await createFixture();
    clickRow(withoutRight.fixture);
    expect(card(withoutRight.fixture).componentInstance.canApply()).toBe(false);

    const other = item({ id: 'other', fileName: 'b_q1.xlsx' });
    const { fixture, api } = await createFixture({
      rights: ['upload', 'apply'],
      pages: [of(page([item(RECEIVED)])), of(page([item()])), of(page([other]))],
    });
    clickRow(fixture);
    expect(card(fixture).componentInstance.canApply()).toBe(true);
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(0);
    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(0);
    expect(testId(fixture, 'upl-pkg-card-meta')[0].textContent).toContain('Nalogi TEST');
    expect(testId(fixture, 'upl-pkg-checking')).toHaveLength(1);

    // The open card takes the fresh row of the reloaded list.
    click(fixture, 'upl-pkg-card-refresh');
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(testId(fixture, 'upl-pkg-checking')).toHaveLength(0);
    expect(testId(fixture, 'upl-pkg-counters')).toHaveLength(1);

    // The answer of «Apply» shows at once; an upload gone from the reloaded page stays as it was.
    card(fixture).triggerEventHandler('applied', item({ status: 'applied', loadId: 42, rawRows: 120 }));
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(3);
    expect(card(fixture).componentInstance.item().status).toBe('applied');
    expect(text(fixture)).toContain('a_jan.xlsx');
    expect(text(fixture)).not.toContain('b_q1.xlsx');

    click(fixture, 'upl-pkg-back');
    expect(card(fixture)).toBeNull();
    expect(testId(fixture, 'upl-pkg-row')).toHaveLength(1);
    expect(testId(fixture, 'upl-pkg-form')).toHaveLength(1);
  });
});
