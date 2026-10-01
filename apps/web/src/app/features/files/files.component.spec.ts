import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { AuthService } from '@core/services/auth.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { FileDetail, FilesComponent, StorageStats } from './files.component';
import { QueryListMeta } from '@core/models/query-meta.models';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ListViewsApi } from '@shared/list-views/list-views';

const field = (
  key: string,
  labelKey: string,
  type: QueryListMeta['fields'][number]['type'],
  extra: Partial<QueryListMeta['fields'][number]> = {},
) =>
  ({
    key,
    labelKey,
    type,
    ops: ['eq'],
    sortable: false,
    nullable: false,
    defaultVisible: true,
    enumValues: [],
    enumLabelPrefix: null,
    ...extra,
  }) as QueryListMeta['fields'][number];

/** What `query-meta/mf.files` answers. */
const FILES_META: QueryListMeta = {
  code: 'mf.files',
  defaultSort: '-createdAt',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    field('originalName', 'files.imya_fayla', 'text', { sortable: true }),
    field('sizeBytes', 'files.razmer', 'number', { sortable: true }),
    field('mimeType', 'files.tip_mime', 'text'),
    field('creatorName', 'files.zagruzil', 'text', { nullable: true }),
    field('createdAt', 'files.data_zagruzki', 'instant', { sortable: true }),
  ],
};

const REGISTRY_PROVIDERS = [
  { provide: QueryMetaService, useValue: { get: () => of(FILES_META) } },
  { provide: ListViewsApi, useValue: { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } },
];

function keyset<T>(items: T[], nextCursor: string | null = null) {
  return { items, nextCursor, hasMore: nextCursor !== null, totalEstimated: items.length };
}

/*
 * The screen's own contract: which requests it sends and keeps, and the delete flow.
 * The toolbar, the table, the quota cards and the upload dialog have their own specs.
 */
describe('FilesComponent request and deletion mechanics', () => {
  const user: User = {
    id: 17,
    name: 'File Owner',
    login: 'file-owner',
    email: 'owner@example.test',
    state: 'A',
    language: 'ru',
    timezone: 'UTC',
    attributes: {},
    is2faEnabled: false,
    forcePasswordChange: false,
    createdAt: '2026-09-07T00:00:00Z',
    modifiedAt: '2026-09-07T00:00:00Z',
  };
  const file = (id: number, createdBy = 17): FileDetail => ({
    id: String(id),
    originalName: `report-${id}.pdf`,
    sizeBytes: 1024,
    mimeType: 'application/pdf',
    createdAt: '2026-09-07T00:00:00Z',
    createdBy,
    creatorName: 'File Owner',
    creatorLogin: 'file-owner',
  });
  const stats: StorageStats = {
    companyQuotaBytes: 10000,
    companyUsedBytes: 2048,
    companyAvailableBytes: 7952,
    userQuotaBytes: 5000,
    userUsedBytes: 1024,
    userAvailableBytes: 3976,
    totalFilesCount: 2,
    userFilesCount: 1,
  };
  let http: HttpTestingController;

  afterEach(() => http?.verify());

  async function createFixture(permissions = ['mf.files.delete'], initialFiles?: FileDetail[]) {
    await TestBed.configureTestingModule({
      imports: [FilesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), ...REGISTRY_PROVIDERS],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).currentUser.set(user);
    TestBed.inject(PermissionService).setPermissions(permissions);
    const fixture = TestBed.createComponent(FilesComponent);
    fixture.detectChanges();
    const initialStats = http.expectOne('/api/v1/files/storage/stats');
    const initialList = http.expectOne((request) => request.url === '/api/v1/files');
    if (initialFiles) {
      initialStats.flush(stats);
      initialList.flush(keyset(initialFiles));
      fixture.detectChanges();
    }
    return {
      fixture,
      component: fixture.componentInstance,
      host: fixture.nativeElement as HTMLElement,
      toast: TestBed.inject(ToastService),
      initialList,
      initialStats,
    };
  }

  it('keeps the latest scope result when an older list response arrives last', async () => {
    const { component, fixture, host, initialList, initialStats } = await createFixture();
    initialStats.flush(stats);
    component.setScope('mine');
    const latest = http.expectOne(
      (request) => request.url === '/api/v1/files' && request.params.get('scope') === 'mine',
    );
    latest.flush(keyset([file(2)]));
    if (!initialList.cancelled) initialList.flush(keyset([file(1)]));
    fixture.detectChanges();

    expect(host.querySelector('.primary-name')?.textContent).toBe('report-2.pdf');
    expect(component.isLoading()).toBe(false);
    expect(initialList.cancelled).toBe(true);
  });

  it('does not let an obsolete failed search stop loading or notify over the new request', async () => {
    const { component, initialList, initialStats, toast } = await createFixture();
    initialStats.flush(stats);
    component.searchQuery = 'latest';
    component.loadFiles();
    const latest = http.expectOne((request) => request.url === '/api/v1/files' && request.params.get('q') === 'latest');
    if (!initialList.cancelled)
      initialList.flush({ detail: 'Obsolete failure' }, { status: 503, statusText: 'Unavailable' });

    expect(component.isLoading()).toBe(true);
    expect(toast.toasts()).toEqual([]);
    latest.flush(keyset([file(2)]));
    expect(component.files().map((item) => item.id)).toEqual(['2']);
  });

  it('keeps the newest quota response when refresh requests overlap', async () => {
    const { component, fixture, initialList, initialStats } = await createFixture();
    initialList.flush(keyset([]));
    component.loadStats();
    fixture.detectChanges();
    http.expectOne('/api/v1/files/storage/stats').flush({ ...stats, totalFilesCount: 3 });
    if (!initialStats.cancelled) initialStats.flush(stats);
    await fixture.whenStable();

    expect(component.stats()?.totalFilesCount).toBe(3);
  });

  it('cancels list and quota reads when the page is destroyed', async () => {
    const { fixture, initialList, initialStats } = await createFixture();
    fixture.destroy();

    expect(initialList.cancelled).toBe(true);
    expect(initialStats.cancelled).toBe(true);
  });

  it('a submitted search asks the server for the first page with q and no cursor', async () => {
    const { fixture, host } = await createFixture(undefined, [file(1)]);
    const input = host.querySelector('#file-search') as HTMLInputElement;
    input.value = 'report';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    const request = http.expectOne((req) => req.url === '/api/v1/files' && req.params.get('q') === 'report');
    expect(request.request.params.has('cursor')).toBe(false);
    expect(request.request.params.get('sort')).toBe('-createdAt');
    request.flush(keyset([file(7)]));
    fixture.detectChanges();

    expect(host.querySelector('.primary-name')?.textContent).toBe('report-7.pdf');
  });

  it('clearing search omits the previous query', async () => {
    const { component, fixture, host } = await createFixture(undefined, [file(1)]);
    // Typed, as a person does: the OnPush screen hears it through the toolbar's output.
    const input = host.querySelector('#file-search') as HTMLInputElement;
    input.value = 'report';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(component.searchQuery).toBe('report');
    await fixture.whenStable();
    fixture.detectChanges();
    // The field's own clear button; clearing lists every file again at once.
    (
      host.querySelector('#file-search')!.closest('smt-input')!.querySelector('.smt-input__action') as HTMLButtonElement
    ).click();
    const request = http.expectOne((req) => req.url === '/api/v1/files');
    expect(request.request.params.has('q')).toBe(false);
    request.flush(keyset([]));
    fixture.detectChanges();
    expect(host.querySelector('.empty-state-box')).not.toBeNull();
  });

  it('pages through the whole list with the server cursor and sorts it by a header click', async () => {
    const { fixture, host } = await createFixture(undefined, []);
    fixture.componentInstance.loadFiles();
    http.expectOne((req) => req.url === '/api/v1/files').flush(keyset([file(1)], 'cursor-2'));
    fixture.detectChanges();

    (host.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).click();
    const next = http.expectOne((req) => req.url === '/api/v1/files' && req.params.get('cursor') === 'cursor-2');
    next.flush(keyset([file(2)]));
    fixture.detectChanges();
    expect(host.querySelector('.primary-name')?.textContent).toBe('report-2.pdf');

    const sizeHeader = [...host.querySelectorAll('[role="columnheader"]')].find((cell) =>
      cell.textContent?.includes('Размер'),
    );
    sizeHeader?.querySelector('smt-cell-header')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const sorted = http.expectOne((req) => req.url === '/api/v1/files' && req.params.get('sort') === 'sizeBytes');
    expect(sorted.request.params.has('cursor')).toBe(false);
    sorted.flush(keyset([file(3)]));
  });

  /** Opens the delete question for the first file; the dialog lives in the overlay, outside the page. */
  async function openDelete(
    fixture: { detectChanges(): void; whenStable(): Promise<unknown> },
    host: HTMLElement,
  ): Promise<HTMLElement> {
    (host.querySelector('.delete-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    return document.querySelector('.smt-modal-confirm') as HTMLElement;
  }
  const dialogButtons = () => [...document.querySelectorAll<HTMLButtonElement>('.smt-modal-confirm button')];
  const yes = () => dialogButtons().at(-1)!;

  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove()));

  it('asks in an alert dialog and sends one DELETE however often Yes is pressed', async () => {
    const { fixture, host } = await createFixture(undefined, [file(1)]);
    const dialog = await openDelete(fixture, host);

    const pane = dialog.closest('[role="alertdialog"]') as HTMLElement;
    expect(document.getElementById(pane.getAttribute('aria-describedby')!)?.textContent).toContain(
      'Удалить файл «report-1.pdf»?',
    );
    yes().click();
    yes().click();
    const requests = http.match({ method: 'DELETE', url: '/api/v1/files/1' });

    expect(requests).toHaveLength(1);
    fixture.detectChanges();
    expect(yes().disabled).toBe(true);
    expect(yes().getAttribute('aria-busy')).toBe('true');
    requests[0].flush({ detail: 'Retry later' }, { status: 503, statusText: 'Unavailable' });
  });

  it.each(['decline', 'escape', 'backdrop'])('keeps the dialog while the deletion runs after %s', async (action) => {
    const { fixture, host } = await createFixture(undefined, [file(1), file(2)]);
    const dialog = await openDelete(fixture, host);
    yes().click();
    const request = http.expectOne({ method: 'DELETE', url: '/api/v1/files/1' });
    fixture.detectChanges();

    if (action === 'decline') dialogButtons()[0].click();
    if (action === 'escape')
      dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    if (action === 'backdrop') (document.querySelector('.smt-modal-backdrop') as HTMLElement).click();
    fixture.detectChanges();

    expect(document.querySelector('.smt-modal-confirm')).not.toBeNull();
    request.flush({ detail: 'Retry later' }, { status: 503, statusText: 'Unavailable' });
  });

  it('shows the server reason in the dialog instead of a toast and deletes on retry', async () => {
    const { fixture, host, toast } = await createFixture(undefined, [file(1)]);
    await openDelete(fixture, host);
    yes().click();
    http
      .expectOne({ method: 'DELETE', url: '/api/v1/files/1' })
      .flush({ detail: 'Deletion temporarily unavailable' }, { status: 503, statusText: 'Unavailable' });
    TestBed.tick(); // the dialog is attached to the application, not to this fixture

    expect(document.querySelector('.smt-modal-confirm [role="alert"]')?.textContent?.trim()).toBe(
      'Deletion temporarily unavailable',
    );
    expect(toast.toasts()).toEqual([]);
    expect(yes().disabled).toBe(false);

    yes().click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/files/1' }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne((request) => request.url === '/api/v1/files').flush(keyset([]));
    fixture.detectChanges();
    http.expectOne('/api/v1/files/storage/stats').flush({ ...stats, totalFilesCount: 0 });
    await fixture.whenStable();

    expect(document.querySelector('.smt-modal-confirm')).toBeNull();
    expect(host.querySelector('.primary-name')).toBeNull();
    expect(toast.toasts().filter((item) => item.type === 'success')).toHaveLength(1);
  });

  it.each([
    { permissions: ['mf.files.manage_quotas'], visible: [] },
    { permissions: ['mf.files.delete'], visible: ['Удалить файл report-1.pdf'] },
    {
      permissions: ['mf.files.delete', 'mf.files.manage_quotas'],
      visible: ['Удалить файл report-1.pdf', 'Удалить файл report-2.pdf', 'Удалить файл report-3.pdf'],
    },
  ])('matches server deletion rights for $permissions', async ({ permissions, visible }) => {
    const { host } = await createFixture(permissions, [file(1), file(2, 99), { ...file(3), createdBy: undefined }]);

    expect(Array.from(host.querySelectorAll('.delete-btn'), (button) => button.getAttribute('aria-label'))).toEqual(
      visible,
    );
  });

  it('does not delete when the right was revoked while the question was open', async () => {
    const { fixture, host } = await createFixture(undefined, [file(1)]);
    await openDelete(fixture, host);
    TestBed.inject(PermissionService).setPermissions([]);
    yes().click();
    fixture.detectChanges();

    expect(http.match((request) => request.method === 'DELETE')).toHaveLength(0);
    expect(document.querySelector('.smt-modal-confirm [role="alert"]')?.textContent?.trim()).toBe(
      'У вас больше нет права удалить этот файл.',
    );
  });
});
