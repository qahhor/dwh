import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { NEVER, Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { KeysetPage, ProblemDetail } from '../../../core/models/common.models';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { PermissionService } from '../../../core/services/permission.service';
import { QueryMetaService } from '../../../core/services/query-meta.service';
import { ToastService } from '../../../core/services/toast.service';
import { PACKAGED_RUSSIAN } from '../../../core/i18n/packaged-russian';
import { UplApiService, UplSource, UplSourceItem } from '../upl-api';
import { SourcesListComponent } from './sources-list.component';
import { ListViewsApi, SavedListView } from '../../../shared/list-views/list-views';

function page(items: UplSourceItem[], hasMore = false, nextCursor: string | null = null, total = items.length): KeysetPage<UplSourceItem> {
  return { items, nextCursor, hasMore, totalEstimated: total } as unknown as KeysetPage<UplSourceItem>;
}

/** What `query-meta/upl.sources` answers (ADR-0016). */
const META: QueryListMeta = {
  code: 'upl.sources',
  defaultSort: 'code',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    { key: 'code', labelKey: 'upl.list.col.code', type: 'text', ops: ['eq'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'name', labelKey: 'upl.list.col.name', type: 'text', ops: ['eq'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'periodicity', labelKey: 'upl.list.col.periodicity', type: 'enum', ops: ['in'], sortable: false, nullable: false, defaultVisible: true, enumValues: ['month', 'quarter', 'year', 'adhoc'], enumLabelPrefix: 'upl.periodicity.' },
    { key: 'lastPublishedVersion', labelKey: 'upl.list.col.published_version', type: 'number', ops: ['gt'], sortable: false, nullable: true, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'hasDraft', labelKey: 'upl.list.col.draft', type: 'boolean', ops: ['eq'], sortable: false, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null }
  ]
};

const firstItem: UplSourceItem = {
  id: 1,
  code: 'cement.output',
  name: 'Vypusk cementa',
  periodicity: 'month',
  lastPublishedVersion: 2,
  hasDraft: false
};

const secondItem: UplSourceItem = {
  id: 5,
  code: 'brick.output',
  name: 'Vypusk kirpicha',
  periodicity: 'quarter',
  lastPublishedVersion: null,
  hasDraft: true
};

const createdSource = { id: 7, code: 'cement.output', name: 'Vypusk cementa' } as UplSource;

interface FixtureOptions {
  pages?: Array<Observable<KeysetPage<UplSourceItem>>>;
  createResult?: Observable<UplSource>;
  canCreate?: boolean;
  meta?: Array<Observable<QueryListMeta>>;
  views?: Observable<SavedListView[]>;
  query?: Record<string, string>;
}

async function createFixture(options: FixtureOptions = {}) {
  const pages = options.pages ?? [of(page([firstItem, secondItem]))];
  const metas = options.meta ?? [of(META)];
  let call = 0;
  let metaCall = 0;
  const api = {
    listSources: vi.fn((..._args: unknown[]) => pages[Math.min(call++, pages.length - 1)]),
    createSource: vi.fn(() => options.createResult ?? of(createdSource))
  };
  const queryMeta = { get: vi.fn(() => metas[Math.min(metaCall++, metas.length - 1)]) };
  const listViews = { list: vi.fn(() => options.views ?? of([])), create: vi.fn(), update: vi.fn(), remove: vi.fn() };
  const permissions = { hasPermission: vi.fn(() => options.canCreate !== false) };
  const toast = { success: vi.fn(), error: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [SourcesListComponent],
    providers: [
      provideRouter([]),
      { provide: UplApiService, useValue: api },
      { provide: QueryMetaService, useValue: queryMeta },
      { provide: ListViewsApi, useValue: listViews },
      ...(options.query ? [{ provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(options.query) } } }] : []),
      { provide: PermissionService, useValue: permissions },
      { provide: ToastService, useValue: toast }
    ]
  }).compileComponents();
  const fixture = TestBed.createComponent(SourcesListComponent);
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  fixture.detectChanges();
  fixture.detectChanges();
  return { fixture, api, queryMeta, listViews, permissions, toast, navigate };
}

/** The error smt-control shows for a field, found the way assistive technology finds it: through aria-describedby. */
function fieldError(root: HTMLElement, fieldId: string): HTMLElement | null {
  const field = root.querySelector('#' + fieldId);
  const ids = (field?.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids.map(id => root.querySelector<HTMLElement>('#' + id)).find(node => node?.classList.contains('smt-control__error')) ?? null;
}

function testId(fixture: ComponentFixture<SourcesListComponent>, id: string): HTMLElement[] {
  return fixture.debugElement.queryAll(By.css(`[data-testid="${id}"]`)).map(node => node.nativeElement as HTMLElement);
}

function click(fixture: ComponentFixture<SourcesListComponent>, id: string): void {
  fixture.debugElement.query(By.css(`[data-testid="${id}"]`)).triggerEventHandler('onClick', null);
  fixture.detectChanges();
}

function headers(fixture: ComponentFixture<SourcesListComponent>): HTMLElement[] {
  return [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="columnheader"]')] as HTMLElement[];
}

/** Submits the create form as it was opened, keeping what was prefilled. */
function submitPrefilled(fixture: ComponentFixture<SourcesListComponent>, values: Partial<SourcesListComponent['form']>): void {
  fixture.detectChanges();
  Object.assign(fixture.componentInstance.form, values);
  fixture.debugElement.query(By.css('#upl-source-create')).triggerEventHandler('ngSubmit', null);
  fixture.detectChanges();
}

async function openCreateForm(
  fixture: ComponentFixture<SourcesListComponent>,
  values: Partial<SourcesListComponent['form']>
): Promise<void> {
  fixture.componentInstance.openCreate();
  fixture.detectChanges();
  Object.assign(fixture.componentInstance.form, values);
  fixture.debugElement.query(By.css('#upl-source-create')).triggerEventHandler('ngSubmit', null);
  fixture.detectChanges();
}

describe('SourcesListComponent', () => {
  afterEach(() => localStorage.clear());

  it('строит колонки по метаданным списка и показывает строки со ссылкой на карточку и бейджем черновика', async () => {
    const { fixture, queryMeta, api } = await createFixture();

    expect(queryMeta.get).toHaveBeenCalledWith('upl.sources');
    expect(api.listSources).toHaveBeenCalledWith(50, null, { sort: { field: 'code', descending: false }, conditions: [] });
    expect(headers(fixture).map(cell => cell.querySelector('span.truncate')?.textContent?.trim())).toEqual([
      PACKAGED_RUSSIAN['upl.list.col.code'],
      PACKAGED_RUSSIAN['upl.list.col.name'],
      PACKAGED_RUSSIAN['upl.list.col.periodicity'],
      PACKAGED_RUSSIAN['upl.list.col.published_version'],
      PACKAGED_RUSSIAN['upl.list.col.draft']
    ]);
    expect(headers(fixture)[0].getAttribute('aria-sort')).toBe('ascending');
    expect(testId(fixture, 'upl-source-row')).toHaveLength(2);
    const links = [...fixture.nativeElement.querySelectorAll('a.upl-link')] as HTMLAnchorElement[];
    expect(links.map(link => link.getAttribute('href'))).toEqual(['/upl/sources/1', '/upl/sources/5']);
    expect(fixture.nativeElement.querySelectorAll('[role="rowgroup"] ui-badge')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain(PACKAGED_RUSSIAN['upl.periodicity.quarter']);
    expect(testId(fixture, 'upl-count')[0].textContent?.trim()).toBe('2');
  });

  it('сортирует весь список на сервере по клику на заголовок', async () => {
    const { fixture, api } = await createFixture();

    headers(fixture)[1].querySelector('smt-cell-header')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(api.listSources).toHaveBeenLastCalledWith(50, null, { sort: { field: 'name', descending: false }, conditions: [] });
    expect(headers(fixture)[1].getAttribute('aria-sort')).toBe('ascending');
    expect(headers(fixture)[0].getAttribute('aria-sort')).toBe('none');
  });

  it('листает страницы курсором того же запроса', async () => {
    const { fixture, api } = await createFixture({
      pages: [of(page([firstItem], true, 'cursor-1', 2)), of(page([secondItem], false, null, 2))]
    });

    (fixture.nativeElement.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.listSources).toHaveBeenLastCalledWith(50, 'cursor-1', { sort: { field: 'code', descending: false }, conditions: [] });
    expect(testId(fixture, 'upl-source-row').map(row => row.textContent)).toEqual(['brick.output']);
  });

  it('показывает пустое состояние, когда источников нет', async () => {
    const { fixture } = await createFixture({ pages: [of(page([]))] });

    expect(testId(fixture, 'upl-empty')).toHaveLength(1);
    expect(testId(fixture, 'upl-source-row')).toHaveLength(0);
  });

  it('без метаданных показывает ошибку, «Повторить» запрашивает их и список', async () => {
    const { fixture, api, queryMeta } = await createFixture({
      meta: [throwError(() => ({ status: 503 })), of(META)]
    });

    expect(testId(fixture, 'upl-load-error')).toHaveLength(1);
    expect(api.listSources).not.toHaveBeenCalled();

    click(fixture, 'upl-retry');
    fixture.detectChanges();

    expect(queryMeta.get).toHaveBeenCalledTimes(2);
    expect(testId(fixture, 'upl-load-error')).toHaveLength(0);
    expect(testId(fixture, 'upl-source-row')).toHaveLength(2);
  });

  it('ошибку страницы показывает таблица с повтором именно этого запроса', async () => {
    const { fixture, api } = await createFixture({
      pages: [throwError(() => ({ status: 503 })), of(page([firstItem]))]
    });

    const alert = fixture.nativeElement.querySelector('ui-server-table [role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain(PACKAGED_RUSSIAN['upl.list.load_error']);
    (alert.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.listSources).toHaveBeenCalledTimes(2);
    expect(testId(fixture, 'upl-source-row')).toHaveLength(1);
  });

  it('позволяет настроить колонки, кроме названия', async () => {
    const { fixture } = await createFixture();

    (fixture.nativeElement.querySelector('.smt-columns__trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    const checks = [...document.querySelectorAll('[role="dialog"] input[type="checkbox"]')] as HTMLInputElement[];
    expect(checks).toHaveLength(5);
    expect(checks[1].disabled).toBe(true);
    checks[3].click();
    fixture.detectChanges();

    expect(headers(fixture)).toHaveLength(4);
    expect(JSON.parse(localStorage.getItem('dwh.table-columns.v1.upl.sources')!).hidden).toEqual(['lastPublishedVersion']);
  });

  it('открывается представлением по умолчанию: его колонки и сортировка', async () => {
    const byName: SavedListView = {
      id: 3,
      name: 'По названию',
      state: { columns: { order: [], hidden: ['periodicity'], widths: {} }, sort: '-name', filter: [] },
      isDefault: true,
      lockVersion: 0,
      modifiedAt: '2026-09-25T00:00:00Z'
    };
    const { fixture, api, listViews } = await createFixture({ views: of([byName]) });

    expect(listViews.list).toHaveBeenCalledWith('upl.sources');
    expect(api.listSources).toHaveBeenCalledTimes(1);
    expect(api.listSources).toHaveBeenCalledWith(50, null, { sort: { field: 'name', descending: true }, conditions: [] });
    expect(headers(fixture)).toHaveLength(4);
    expect(headers(fixture)[1].getAttribute('aria-sort')).toBe('descending');
    expect(testId(fixture, 'views-trigger')[0].textContent).toContain('По названию');
  });

  it('фильтр из представления уходит в запрос, а снятое с чипа условие перезагружает список', async () => {
    const monthly: SavedListView = {
      id: 4,
      name: 'Месячные',
      state: { columns: { order: [], hidden: [], widths: {} }, sort: null, filter: [{ field: 'periodicity', op: 'in', value: ['month'] }] },
      isDefault: true,
      lockVersion: 0,
      modifiedAt: '2026-09-25T00:00:00Z'
    };
    const { fixture, api } = await createFixture({ views: of([monthly]) });

    expect(api.listSources).toHaveBeenCalledWith(50, null, {
      sort: { field: 'code', descending: false },
      conditions: [{ field: 'periodicity', op: 'in', value: ['month'] }]
    });
    const chip = fixture.nativeElement.querySelector('[data-testid="filter-chip"] .filter-chip-text') as HTMLElement;
    expect(chip.textContent).toContain(PACKAGED_RUSSIAN['upl.periodicity.month']);

    (fixture.nativeElement.querySelector('.filter-chip-remove') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.listSources).toHaveBeenLastCalledWith(50, null, { sort: { field: 'code', descending: false }, conditions: [] });
    expect(testId(fixture, 'views-trigger')[0].textContent).toContain(PACKAGED_RUSSIAN['ui.views.changed']);
    expect(fixture.nativeElement.querySelector('[data-testid="filter-chip"]')).toBeNull();
  });

  it('без представлений открывается стандартным, даже если их не удалось загрузить', async () => {
    const { fixture, api } = await createFixture({ views: throwError(() => ({ status: 503 })) });

    expect(api.listSources).toHaveBeenCalledWith(50, null, { sort: { field: 'code', descending: false }, conditions: [] });
    expect(testId(fixture, 'views-trigger')[0].textContent).toContain(PACKAGED_RUSSIAN['ui.views.standard']);
    expect(testId(fixture, 'upl-source-row')).toHaveLength(2);
  });

  it('«создать из поля» другой формы: окно открыто с названием, после создания — обратно в форму', async () => {
    const { fixture, navigate, api } = await createFixture({ query: { create: '  Выпуск стекла ', returnTo: 'packages' } });

    expect(fixture.componentInstance.isCreateOpen()).toBe(true);
    expect(fixture.componentInstance.form.name).toBe('Выпуск стекла');
    submitPrefilled(fixture, { code: 'glass.output', ownerOrg: 'Org' });

    expect(api.createSource).toHaveBeenCalledWith(expect.objectContaining({ name: 'Выпуск стекла', code: 'glass.output' }));
    expect(navigate).toHaveBeenCalledWith(['/upl/packages'], { queryParams: { source: 7 } });
  });

  it('возвращается только в известные места, а без права create окно не открывает', async () => {
    const foreign = await createFixture({ query: { create: 'Стекло', returnTo: 'https://evil.example' } });
    submitPrefilled(foreign.fixture, { code: 'glass.output', ownerOrg: 'Org' });
    expect(foreign.navigate).toHaveBeenCalledWith(['/upl/sources', 7]);

    TestBed.resetTestingModule();
    const viewer = await createFixture({ query: { create: 'Стекло', returnTo: 'packages' }, canCreate: false });
    expect(viewer.fixture.componentInstance.isCreateOpen()).toBe(false);
  });

  it('пока список грузится, сообщает об этом и не показывает пустое состояние', async () => {
    const { fixture } = await createFixture({ pages: [NEVER] });

    expect(fixture.nativeElement.querySelector('[data-server-table-status]')).not.toBeNull();
    expect(testId(fixture, 'upl-empty')).toHaveLength(0);
    expect(testId(fixture, 'upl-source-row')).toHaveLength(0);
  });

  it('кнопку «Новый источник» показывает только при праве create', async () => {
    const withoutRight = await createFixture({ canCreate: false });
    expect(testId(withoutRight.fixture, 'upl-new-source')).toHaveLength(0);

    TestBed.resetTestingModule();
    const withRight = await createFixture({ canCreate: true });
    expect(testId(withRight.fixture, 'upl-new-source')).toHaveLength(1);
    expect(withRight.permissions.hasPermission).toHaveBeenCalledWith('upl.sources', 'create');
  });

  it('не отправляет запрос при неверном коде', async () => {
    const { fixture, api } = await createFixture();

    await openCreateForm(fixture, { code: 'Cement Output', name: 'Vypusk', ownerOrg: 'Org' });

    expect(api.createSource).not.toHaveBeenCalled();
    expect(fieldError(fixture.nativeElement, 'upl-source-code')).not.toBeNull();
  });

  it('создаёт источник и переходит на карточку', async () => {
    const { fixture, api, toast, navigate } = await createFixture();

    await openCreateForm(fixture, { code: 'cement.output', name: ' Vypusk ', ownerOrg: ' Org ', ownerContact: '  ' });

    expect(api.createSource).toHaveBeenCalledWith({
      code: 'cement.output',
      name: 'Vypusk',
      ownerOrg: 'Org',
      ownerContact: null,
      periodicity: 'month',
      slaDays: 0,
      sourceType: 'file',
      reconciliationStrictness: 'error',
      lockVersion: null
    });
    expect(navigate).toHaveBeenCalledWith(['/upl/sources', 7]);
    expect(toast.success).toHaveBeenCalled();
    expect(fixture.componentInstance.isCreateOpen()).toBe(false);
  });

  it('показывает занятый код под полем «Код», окно остаётся открытым', async () => {
    const problem: ProblemDetail = {
      title: 'Bad Request',
      status: 400,
      code: 'code_already_exists',
      detail: 'UPL_SOURCE_CODE_TAKEN'
    };
    const { fixture } = await createFixture({ createResult: throwError(() => problem) });

    await openCreateForm(fixture, { code: 'cement.output', name: 'Vypusk', ownerOrg: 'Org' });

    expect(fieldError(fixture.nativeElement, 'upl-source-code')).not.toBeNull();
    expect(fixture.componentInstance.isCreateOpen()).toBe(true);
  });

  it('«код занят» узнаётся по коду каркаса, даже если detail переведён', async () => {
    const problem: ProblemDetail = {
      title: 'Bad Request',
      status: 400,
      code: 'code_already_exists',
      detail: 'Такой код уже существует'
    };
    const { fixture } = await createFixture({ createResult: throwError(() => problem) });

    await openCreateForm(fixture, { code: 'cement.output', name: 'Vypusk', ownerOrg: 'Org' });

    expect(fieldError(fixture.nativeElement, 'upl-source-code')).not.toBeNull();
    expect(fixture.componentInstance.isCreateOpen()).toBe(true);
  });

  it('раскладывает ошибки 422 по полям', async () => {
    const problem: ProblemDetail = {
      title: 'Unprocessable Entity',
      status: 422,
      code: 'validation_failed',
      detail: 'VALIDATION_FAILED: name',
      errors: [{ field: 'name', code: 'Size', message: 'x' }]
    };
    const { fixture } = await createFixture({ createResult: throwError(() => problem) });

    await openCreateForm(fixture, { code: 'cement.output', name: 'Vypusk', ownerOrg: 'Org' });

    expect(fieldError(fixture.nativeElement, 'upl-source-name')?.textContent).toContain(PACKAGED_RUSSIAN['upl.err.Size']);
    expect(fixture.nativeElement.querySelector('#upl-source-name')?.getAttribute('aria-invalid')).toBe('true');
    expect(testId(fixture, 'upl-create-error')).toHaveLength(1);
  });


  it('в пустом списке без права create нет кнопки «Новый источник»', async () => {
    const withoutRight = await createFixture({ pages: [of(page([]))], canCreate: false });

    expect(testId(withoutRight.fixture, 'upl-empty')).toHaveLength(1);
    expect(testId(withoutRight.fixture, 'upl-empty-new')).toHaveLength(0);
    expect(testId(withoutRight.fixture, 'upl-new-source')).toHaveLength(0);

    TestBed.resetTestingModule();
    const withRight = await createFixture({ pages: [of(page([]))], canCreate: true });

    expect(testId(withRight.fixture, 'upl-empty-new')).toHaveLength(1);
  });

  it('отказ в праве при создании показывает текстом, окно остаётся открытым', async () => {
    const problem: ProblemDetail = {
      title: 'Forbidden',
      status: 403,
      code: 'permission_denied',
      detail: 'PERMISSION_DENIED'
    };
    const { fixture, navigate } = await createFixture({ createResult: throwError(() => problem) });

    await openCreateForm(fixture, { code: 'cement.output', name: 'Vypusk', ownerOrg: 'Org' });

    const errors = testId(fixture, 'upl-create-error');
    expect(errors).toHaveLength(1);
    expect(errors[0].textContent).toContain(PACKAGED_RUSSIAN['upl.err.PERMISSION_DENIED']);
    expect(fixture.componentInstance.isCreateOpen()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('неизвестную ошибку сервера не прячет', async () => {
    const problem: ProblemDetail = {
      title: 'Bad Request',
      status: 400,
      code: 'bad_request',
      detail: 'UPL_SOMETHING_NEW'
    };
    const { fixture, navigate } = await createFixture({ createResult: throwError(() => problem) });

    await openCreateForm(fixture, { code: 'cement.output', name: 'Vypusk', ownerOrg: 'Org' });

    const errors = testId(fixture, 'upl-create-error');
    expect(errors).toHaveLength(1);
    expect(errors[0].textContent).toContain('UPL_SOMETHING_NEW (bad_request)');
    expect(fixture.componentInstance.isCreateOpen()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

});
