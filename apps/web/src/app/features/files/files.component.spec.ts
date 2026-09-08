import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { User } from '../../core/models/auth.models';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { FileDetail, FilesComponent, StorageStats } from './files.component';

describe('FilesComponent UI contracts', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [FilesComponent],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(() => of([])), delete: vi.fn(() => of({})) } },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(FilesComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('labels file scope, search, table and row actions', async () => {
    const fixture = await createFixture();
    const file: FileDetail = {
      id: 'abc',
      sha256: '0123456789abcdef',
      originalName: 'report.pdf',
      sizeBytes: 1024,
      mimeType: 'application/pdf',
      storageBucket: 'files',
      storageKey: 'abc',
      createdAt: '2026-08-30T00:00:00Z'
    };
    fixture.componentInstance.files.set([file]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.scope-tabs[role="group"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="file-search"]')).not.toBeNull();
    const region = fixture.nativeElement.querySelector('.table-container[role="region"]') as HTMLElement;
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('table')?.getAttribute('aria-label')).toBe('Список файлов');
    expect(fixture.nativeElement.querySelector('.file-name-cell')?.tagName).toBe('BUTTON');
    expect(fixture.nativeElement.querySelector('button[aria-label="Удалить файл report.pdf"]')).not.toBeNull();
  });

  it('exposes storage quotas as progress bars', async () => {
    const fixture = await createFixture();
    const stats: StorageStats = {
      companyQuotaBytes: 100,
      companyUsedBytes: 75,
      companyAvailableBytes: 25,
      userQuotaBytes: 100,
      userUsedBytes: 40,
      userAvailableBytes: 60,
      totalFilesCount: 2,
      userFilesCount: 1
    };
    fixture.componentInstance.stats.set(stats);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="progressbar"][aria-label="Использование хранилища компании"]')?.getAttribute('aria-valuenow')).toBe('75');
    expect(fixture.nativeElement.querySelector('[role="progressbar"][aria-label="Использование персональной квоты"]')?.getAttribute('aria-valuenow')).toBe('40');
  });
});

describe('FilesComponent request and deletion mechanics', () => {
  const user: User = {
    id: 17, name: 'File Owner', login: 'file-owner', email: 'owner@example.test',
    state: 'A', language: 'ru', timezone: 'UTC', attributes: {}, is2faEnabled: false,
    forcePasswordChange: false, createdAt: '2026-09-07T00:00:00Z', modifiedAt: '2026-09-07T00:00:00Z'
  };
  const file = (id: number, createdBy = 17): FileDetail => ({
    id: String(id), sha256: '0123456789abcdef', originalName: `report-${id}.pdf`,
    sizeBytes: 1024, mimeType: 'application/pdf', storageBucket: 'files', storageKey: String(id),
    createdAt: '2026-09-07T00:00:00Z', createdBy, creatorName: 'File Owner', creatorLogin: 'file-owner'
  });
  const stats: StorageStats = {
    companyQuotaBytes: 10000, companyUsedBytes: 2048, companyAvailableBytes: 7952,
    userQuotaBytes: 5000, userUsedBytes: 1024, userAvailableBytes: 3976,
    totalFilesCount: 2, userFilesCount: 1
  };
  let http: HttpTestingController;

  afterEach(() => http?.verify());

  async function createFixture(permissions = ['platform.files.delete'], initialFiles?: FileDetail[]) {
    await TestBed.configureTestingModule({
      imports: [FilesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).currentUser.set(user);
    TestBed.inject(PermissionService).setPermissions(permissions);
    const fixture = TestBed.createComponent(FilesComponent);
    fixture.detectChanges();
    const initialStats = http.expectOne('/api/v1/files/storage/stats');
    const initialList = http.expectOne(request => request.url === '/api/v1/files');
    if (initialFiles) {
      initialStats.flush(stats);
      initialList.flush(initialFiles);
      fixture.detectChanges();
    }
    return {
      fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement,
      toast: TestBed.inject(ToastService), initialList, initialStats
    };
  }

  it('keeps the latest scope result when an older list response arrives last', async () => {
    const { component, fixture, host, initialList, initialStats } = await createFixture();
    initialStats.flush(stats);
    component.setScope('mine');
    const latest = http.expectOne(request => request.url === '/api/v1/files' && request.params.get('scope') === 'mine');
    latest.flush([file(2)]);
    if (!initialList.cancelled) initialList.flush([file(1)]);
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
    const latest = http.expectOne(request => request.url === '/api/v1/files' && request.params.get('q') === 'latest');
    if (!initialList.cancelled) initialList.flush({ detail: 'Obsolete failure' }, { status: 503, statusText: 'Unavailable' });

    expect(component.isLoading()).toBe(true);
    expect(toast.toasts()).toEqual([]);
    latest.flush([file(2)]);
    expect(component.files().map(item => item.id)).toEqual(['2']);
  });

  it('keeps the newest quota response when refresh requests overlap', async () => {
    const { component, initialList, initialStats } = await createFixture();
    initialList.flush([]);
    component.loadStats();
    http.expectOne('/api/v1/files/storage/stats').flush({ ...stats, totalFilesCount: 3 });
    if (!initialStats.cancelled) initialStats.flush(stats);

    expect(component.stats()?.totalFilesCount).toBe(3);
  });

  it('cancels list and quota reads when the page is destroyed', async () => {
    const { fixture, initialList, initialStats } = await createFixture();
    fixture.destroy();

    expect(initialList.cancelled).toBe(true);
    expect(initialStats.cancelled).toBe(true);
  });

  it('starts a submitted search on page one even when both result sets have several pages', async () => {
    const rows = Array.from({ length: 31 }, (_, index) => file(index + 1));
    const { component, fixture, host } = await createFixture(undefined, rows);
    component.currentPage = 2;
    fixture.detectChanges();
    const input = host.querySelector('#file-search') as HTMLInputElement;
    input.value = 'report';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    http.expectOne(request => request.url === '/api/v1/files' && request.params.get('q') === 'report').flush(rows);
    fixture.detectChanges();

    expect(component.currentPage).toBe(1);
    expect(host.querySelector('.primary-name')?.textContent).toBe('report-1.pdf');
  });

  it('clearing search returns to page one and omits the previous query', async () => {
    const rows = Array.from({ length: 31 }, (_, index) => file(index + 1));
    const { component, fixture, host } = await createFixture(undefined, rows);
    component.searchQuery = 'report';
    component.currentPage = 2;
    fixture.detectChanges();
    (host.querySelector('.clear-btn') as HTMLButtonElement).click();
    const request = http.expectOne(request => request.url === '/api/v1/files');
    expect(request.request.params.has('q')).toBe(false);
    request.flush(rows);
    fixture.detectChanges();

    expect(component.currentPage).toBe(1);
    expect(host.querySelector('.primary-name')?.textContent).toBe('report-1.pdf');
  });

  it('clamps a refreshed page after rows disappear and returns empty results to page one', async () => {
    const rows = Array.from({ length: 31 }, (_, index) => file(index + 1));
    const { component, fixture, host } = await createFixture(undefined, rows);
    component.currentPage = 3;
    component.loadFiles();
    http.expectOne(request => request.url === '/api/v1/files').flush(rows.slice(0, 16));
    fixture.detectChanges();

    expect(component.currentPage).toBe(2);
    expect(host.querySelector('.primary-name')?.textContent).toBe('report-16.pdf');
    component.loadFiles();
    http.expectOne(request => request.url === '/api/v1/files').flush([]);
    fixture.detectChanges();
    expect(component.currentPage).toBe(1);
    expect(host.querySelector('.empty-state-box')).not.toBeNull();
  });

  it('sends one DELETE for repeated confirmation before the view has updated', async () => {
    const { fixture, host } = await createFixture(undefined, [file(1)]);
    (host.querySelector('.delete-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    const confirm = host.querySelector('.modal-footer .btn-danger') as HTMLButtonElement;
    confirm.click();
    confirm.click();
    const requests = http.match({ method: 'DELETE', url: '/api/v1/files/1' });

    expect(requests).toHaveLength(1);
    fixture.detectChanges();
    expect(confirm.disabled).toBe(true);
    expect(confirm.getAttribute('aria-busy')).toBe('true');
    requests[0].flush({ detail: 'Retry later' }, { status: 503, statusText: 'Unavailable' });
  });

  it.each(['cancel', 'escape', 'backdrop', 'target change'])(
    'keeps the original deletion visible while pending after %s', async action => {
      const { component, fixture, host } = await createFixture(undefined, [file(1), file(2)]);
      (host.querySelector('.delete-btn') as HTMLButtonElement).click();
      fixture.detectChanges();
      (host.querySelector('.modal-footer .btn-danger') as HTMLButtonElement).click();
      const request = http.expectOne({ method: 'DELETE', url: '/api/v1/files/1' });
      fixture.detectChanges();
      if (action === 'cancel') (host.querySelector('.modal-footer .btn-secondary') as HTMLButtonElement).click();
      if (action === 'escape') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      if (action === 'backdrop') (host.querySelector('.modal-backdrop') as HTMLElement).click();
      if (action === 'target change') component.confirmDeleteFile(file(2));
      fixture.detectChanges();

      expect(component.fileToDelete?.id).toBe('1');
      expect(host.querySelector('[role="dialog"] .modal-body strong')?.textContent).toBe('report-1.pdf');
      expect(host.querySelector('.modal-close')).toBeNull();
      request.flush({ detail: 'Retry later' }, { status: 503, statusText: 'Unavailable' });
    }
  );

  it('shows one server error, preserves the target and permits a successful retry', async () => {
    const { component, fixture, host, toast } = await createFixture(undefined, [file(1)]);
    (host.querySelector('.delete-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelector('.modal-footer .btn-danger') as HTMLButtonElement).click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/files/1' })
      .flush({ detail: 'Deletion temporarily unavailable' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    expect(toast.toasts().map(item => ({ type: item.type, message: item.message }))).toEqual([
      { type: 'error', message: 'Deletion temporarily unavailable' }
    ]);
    expect(host.querySelector('.modal-body strong')?.textContent).toBe('report-1.pdf');
    expect((host.querySelector('.modal-footer .btn-danger') as HTMLButtonElement).disabled).toBe(false);
    (host.querySelector('.modal-footer .btn-danger') as HTMLButtonElement).click();
    http.expectOne({ method: 'DELETE', url: '/api/v1/files/1' }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/files/storage/stats').flush({ ...stats, totalFilesCount: 0 });
    http.expectOne(request => request.url === '/api/v1/files').flush([]);
    fixture.detectChanges();

    expect(component.fileToDelete).toBeNull();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(host.querySelector('.primary-name')).toBeNull();
    expect(toast.toasts().filter(item => item.type === 'success')).toHaveLength(1);
  });

  it.each([
    { permissions: ['platform.files.manage_quotas'], visible: [] },
    { permissions: ['platform.files.delete'], visible: ['Удалить файл report-1.pdf'] },
    { permissions: ['platform.files.delete', 'platform.files.manage_quotas'], visible: ['Удалить файл report-1.pdf', 'Удалить файл report-2.pdf', 'Удалить файл report-3.pdf'] }
  ])('matches server deletion rights for $permissions', async ({ permissions, visible }) => {
    const { host } = await createFixture(permissions, [file(1), file(2, 99), { ...file(3), createdBy: undefined }]);

    expect(Array.from(host.querySelectorAll('.delete-btn'), button => button.getAttribute('aria-label'))).toEqual(visible);
  });

  it('does not dispatch a deletion if permission was revoked after confirmation opened', async () => {
    const { component, fixture, host } = await createFixture(undefined, [file(1)]);
    (host.querySelector('.delete-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    TestBed.inject(PermissionService).setPermissions([]);
    component.executeDeleteFile();

    expect(http.match(request => request.method === 'DELETE')).toHaveLength(0);
    expect(component.fileToDelete?.id).toBe('1');
  });
});
