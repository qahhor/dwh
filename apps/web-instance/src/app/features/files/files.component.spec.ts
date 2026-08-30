import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { FileDetail, FilesComponent, StorageStats } from './files.component';

describe('FilesComponent UI contracts', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [FilesComponent],
      providers: [
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
