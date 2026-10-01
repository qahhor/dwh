import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';
import { vi } from 'vitest';
import { KeysetPage } from '@core/models/common.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { PermissionService } from '@core/services/permission.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ToastService } from '@core/services/toast.service';
import { UplSource, UplSourceItem } from '@features/upl/upl-api';
import { PackageCardComponent } from '@features/upl/packages/package-card.component';
import { PackagesComponent } from '@features/upl/packages/packages.component';
import { UplPackageItem, UplPackageUpload, UplPackagesApiService } from '@features/upl/packages/packages-api';
import { metaField, registryProviders } from '@testing/registry-meta';

/** An upload as the list answers it; tests override only what they check. */
export function uplPackage(patch: Partial<UplPackageItem> = {}): UplPackageItem {
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

/** An upload still being checked: no counters yet. */
export const UPL_RECEIVED: Partial<UplPackageItem> = {
  status: 'received',
  rowsTotal: null,
  rowsAccepted: null,
  rowsRejected: null,
};

export function keysetPage<T>(items: T[], hasMore = false, nextCursor: string | null = null): KeysetPage<T> {
  return { items, nextCursor, hasMore, totalEstimated: items.length } as unknown as KeysetPage<T>;
}

/** What `query-meta/upl.packages` answers. */
export const UPL_PACKAGES_META = {
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

export const UPL_SOURCE_ITEMS: UplSourceItem[] = [
  { id: 3, code: 'cement.output', name: 'Nalogi TEST', periodicity: 'month', lastPublishedVersion: 2, hasDraft: false },
  { id: 5, code: 'brick.output', name: 'Baza TEST', periodicity: 'quarter', lastPublishedVersion: 1, hasDraft: false },
];

export interface PackagesFixtureOptions {
  /** Answers of the list, the upload and the source search, each in turn and the last one ever after. */
  pages?: Array<Observable<KeysetPage<UplPackageItem>>>;
  uploads?: Array<Observable<UplPackageItem>>;
  sources?: Array<Observable<KeysetPage<UplSourceItem>>>;
  /** Answers of `query-meta/upl.packages`; the metadata at once by default. */
  metas?: Array<Observable<QueryListMeta>>;
  /** Actions the viewer may take; `upload` by default. */
  rights?: string[];
  query?: Record<string, string>;
}

function inTurn<T>(results: T[]): () => T {
  let call = 0;
  return () => results[Math.min(call++, results.length - 1)];
}

/** The uploads screen over a stubbed server, rendered once. */
export async function createPackagesFixture(options: PackagesFixtureOptions = {}) {
  const nextPage = inTurn(options.pages ?? [of(keysetPage([uplPackage()]))]);
  const nextUpload = inTurn(options.uploads ?? [of(uplPackage())]);
  const nextSources = inTurn(options.sources ?? [of(keysetPage(UPL_SOURCE_ITEMS))]);
  const queryMeta = { get: vi.fn(inTurn(options.metas ?? [of(UPL_PACKAGES_META)])) };
  const api = {
    list: vi.fn(() => nextPage()),
    upload: vi.fn((_request: UplPackageUpload) => nextUpload()),
    errors: vi.fn(() => of({ total: 0, shown: 0, items: [] })),
    get: vi.fn((id: string) => of({ ...uplPackage(), id })),
    searchSources: vi.fn((..._args: unknown[]) => nextSources()),
    source: vi.fn(() => of({ ...UPL_SOURCE_ITEMS[1], id: 9, name: 'Created TEST' } as unknown as UplSource)),
  };
  const rights = options.rights ?? ['upload'];
  const toast = { success: vi.fn(), error: vi.fn() };
  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [PackagesComponent],
    providers: [
      provideRouter([]),
      ...registryProviders(UPL_PACKAGES_META),
      { provide: QueryMetaService, useValue: queryMeta },
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
  return {
    fixture,
    api,
    queryMeta,
    toast,
    navigate,
    component: fixture.componentInstance,
    screen: packagesScreen(fixture),
  };
}

/** What a person sees and does on the uploads screen. */
export function packagesScreen(fixture: ComponentFixture<PackagesComponent>) {
  const root = fixture.nativeElement as HTMLElement;
  const all = (selector: string) => [...root.querySelectorAll<HTMLElement>(selector)];
  const testId = (id: string) => all(`[data-testid="${id}"]`);
  const redraw = () => fixture.detectChanges();
  /** The text field inside an smt-date-picker (or the element itself when it is one). */
  const dateField = (id: string) => (testId(id)[0].querySelector('input') ?? testId(id)[0]) as HTMLInputElement;
  /** Fields are filled the way a person does it, with events; otherwise `OnPush` does not redraw the form. */
  const openSources = () => {
    const trigger = testId('upl-pkg-source')[0].querySelector('button[role="combobox"]') as HTMLButtonElement;
    if (trigger.getAttribute('aria-expanded') !== 'true') trigger.click();
    redraw();
    return [...document.querySelectorAll<HTMLElement>('[role="option"]')];
  };
  /** Types a date the way a person does: text, then Enter to commit it. */
  const typeDate = (id: string, value: string) => {
    const input = dateField(id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    redraw();
  };
  /** `input type="file"` cannot be filled the usual way in jsdom, so the file list is replaced. */
  const attachFile = () => {
    const input = testId('upl-pkg-file')[0];
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'a_jan.xlsx')], configurable: true });
    input.dispatchEvent(new Event('change'));
    redraw();
  };
  return {
    all,
    redraw,
    testId,
    dateField,
    openSources,
    typeDate,
    attachFile,
    text: () => root.textContent ?? '',
    card: () => fixture.debugElement.query(By.directive(PackageCardComponent)),
    rows: () => all('[role="rowgroup"] > [role="row"]'),
    submit: () => testId('upl-pkg-submit').find((node) => node.tagName === 'BUTTON') as HTMLButtonElement,
    /** Clicks through the template's handler; a full tick also runs the after-render phase, where smt-control links errors. */
    click: (id: string) => {
      fixture.debugElement.query(By.css(`[data-testid="${id}"]`)).triggerEventHandler('click', new MouseEvent('click'));
      redraw();
      TestBed.tick();
    },
    clickRow: () => {
      all('[role="rowgroup"] > [role="row"]')[0].click();
      redraw();
    },
    /** Chooses the second source, both dates and, unless told otherwise, the file. */
    fillForm: (withFile = true) => {
      openSources()[1].click();
      redraw();
      typeDate('upl-pkg-period-from', '2026-01-01');
      typeDate('upl-pkg-period-to', '2026-01-31');
      if (withFile) attachFile();
    },
    /** The error smt-control shows for a field, found as assistive technology finds it: through aria-describedby. */
    fieldError: (fieldId: string) => {
      const ids = (root.querySelector('#' + fieldId)?.getAttribute('aria-describedby') ?? '')
        .split(/\s+/)
        .filter(Boolean);
      return ids
        .map((id) => root.querySelector<HTMLElement>('#' + id))
        .find((node) => node?.classList.contains('smt-control__error'))?.textContent;
    },
  };
}
