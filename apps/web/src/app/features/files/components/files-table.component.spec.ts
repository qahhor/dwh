import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { metaField } from '@testing/registry-meta';
import { FileDetail } from '../files.models';
import { FilesTableComponent } from './files-table.component';

/** What `query-meta/mf.files` answers. */
const FILES_META: QueryListMeta = {
  code: 'mf.files',
  defaultSort: '-createdAt',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    metaField('originalName', 'files.list.file_name', 'text', { sortable: true }),
    metaField('sizeBytes', 'files.list.size', 'number', { sortable: true }),
    metaField('mimeType', 'files.list.mime_type', 'text'),
    metaField('creatorName', 'files.list.uploaded_by', 'text', { nullable: true }),
    metaField('createdAt', 'files.list.upload_date', 'instant', { sortable: true }),
  ],
};

const file = (overrides: Partial<FileDetail>): FileDetail => ({
  id: 'f-1',
  originalName: 'report.pdf',
  sizeBytes: 1536,
  mimeType: 'application/pdf',
  createdAt: '2026-09-01T10:00:00Z',
  ...overrides,
});

const FILES = [
  file({ id: 'f-1', originalName: 'report.pdf', createdBy: 1, creatorName: 'Анна', creatorLogin: 'anna' }),
  file({ id: 'f-2', originalName: 'photo.png', mimeType: 'image/png', createdBy: 2 }),
];

function render(options: { files?: FileDetail[]; canDelete?: (file: FileDetail) => boolean; deleting?: boolean } = {}) {
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: { get: vi.fn(() => of([])), post: vi.fn() } },
      { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
  const fixture = TestBed.createComponent(FilesTableComponent);
  const pager = new KeysetPager<FileDetail>(() => of({ items: options.files ?? FILES, nextCursor: null }));
  pager.first();
  fixture.componentRef.setInput('pager', pager);
  fixture.componentRef.setInput('meta', FILES_META);
  fixture.componentRef.setInput('canDeleteFn', options.canDelete ?? (() => false));
  fixture.componentRef.setInput('isDeleting', options.deleting ?? false);
  const component = fixture.componentInstance;
  const asked: [string, string][] = [];
  component.download.subscribe((item) => asked.push(['download', item.id]));
  component.preview.subscribe((item) => asked.push(['preview', item.id]));
  component.delete.subscribe((item) => asked.push(['delete', item.id]));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => [...host.querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];
  const button = (label: string) => host.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
  return { fixture, host, rows, button, asked };
}

describe('FilesTableComponent', () => {
  it('lists the files in a named table inside a region a keyboard can reach', () => {
    const { host, rows } = render();

    const region = host.querySelector('.table-container') as HTMLElement;
    expect(region.getAttribute('role')).toBe('region');
    expect(region.getAttribute('aria-label')).toBe('Таблица файлов');
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(host.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Список файлов');
    expect(rows().map((row) => row.querySelector('.primary-name')?.textContent)).toEqual(['report.pdf', 'photo.png']);
  });

  it('shows a readable size and who uploaded the file, but no content hash', () => {
    const { rows } = render();

    // The server no longer sends the hash: it would tell whether a given file exists in the system.
    expect(rows()[0].querySelector('.sha-sub')).toBeNull();
    expect(rows()[0].querySelector('.size-pill')?.textContent).toMatch(/^1,5\sкб$/i);
    expect(rows()[0].querySelector('.creator-name')?.textContent).toBe('Анна');
    expect(rows()[0].querySelector('.creator-login')?.textContent).toBe('@anna');
    expect(rows()[1].querySelector('[data-smt-col-key="creatorName"]')?.textContent?.trim()).toBe('—');
  });

  it('downloads a file from its name or its named button', () => {
    const { rows, button, asked } = render();

    (rows()[0].querySelector('.file-name-cell') as HTMLButtonElement).click();
    (rows()[0].querySelector('.download-btn') as HTMLButtonElement).click();

    expect(button('Скачать файл report.pdf')).not.toBeNull();
    expect(asked).toEqual([
      ['download', 'f-1'],
      ['download', 'f-1'],
    ]);
  });

  it('offers a preview only for images', () => {
    const { button, asked } = render();

    expect(button('Посмотреть «report.pdf»')).toBeNull();
    button('Посмотреть «photo.png»')!.click();

    expect(asked).toEqual([['preview', 'f-2']]);
  });

  it('offers deleting only the files the person may delete, and not while a delete runs', () => {
    const { fixture, button, asked } = render({ canDelete: (item) => item.createdBy === 1 });

    expect(button('Удалить файл photo.png')).toBeNull();
    button('Удалить файл report.pdf')!.click();
    expect(asked).toEqual([['delete', 'f-1']]);

    fixture.componentRef.setInput('isDeleting', true);
    fixture.detectChanges();
    expect(button('Удалить файл report.pdf')!.disabled).toBe(true);
  });

  it('says no file was found when the list is empty', () => {
    const { host, rows } = render({ files: [] });

    expect(rows()).toHaveLength(0);
    expect(host.querySelector('.empty-state-box h3')?.textContent).toBe('Файлы не найдены');
  });
});
