import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { QueryListMeta } from '../../core/models/query-meta.models';
import { ExportsService } from '../../core/services/exports.service';
import { ToastService } from '../../core/services/toast.service';
import { ListViewsApi, ListViewState } from '../list-views/list-views';
import { UiExportButtonComponent } from './ui-export-button.component';

const META: QueryListMeta = {
  code: 'mf.files', defaultSort: '-createdAt', defaultLimit: 50, maxLimit: 200, maxConditions: 20, maxInValues: 100,
  fields: [
    { key: 'originalName', labelKey: 'a', type: 'text', ops: ['eq'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'sizeBytes', labelKey: 'b', type: 'number', ops: ['gt'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'createdAt', labelKey: 'c', type: 'instant', ops: ['gt'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'mimeType', labelKey: 'd', type: 'text', ops: ['eq'], sortable: false, nullable: false, defaultVisible: false, enumValues: [], enumLabelPrefix: null }
  ]
};

async function render(request: ReturnType<typeof vi.fn>) {
  const toast = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [UiExportButtonComponent],
    providers: [
      { provide: ExportsService, useValue: { request } },
      { provide: ToastService, useValue: toast }
    ]
  }).compileComponents();
  const views = new ListViewState('mf.files', { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() } as unknown as ListViewsApi,
    { defaultSort: () => ({ field: 'createdAt', descending: true }), onApply: vi.fn() });
  const fixture = TestBed.createComponent(UiExportButtonComponent);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('views', views);
  fixture.componentRef.setInput('search', 'report');
  fixture.componentRef.setInput('options', { scope: 'mine' });
  fixture.detectChanges();
  const button = fixture.nativeElement.querySelector('[data-testid="export-button"] button') as HTMLButtonElement;
  return { fixture, views, toast, button };
}

describe('ui-export-button', () => {
  it('queues the list as on screen: filter, sort, search, options and the shown columns in their order', async () => {
    const request = vi.fn(() => of({ id: 'x', list: 'mf.files', state: 'queued', truncated: false, createdAt: '', expiresAt: '' }));
    const { fixture, views, toast, button } = await render(request);
    views.sort.set({ field: 'sizeBytes', descending: true });
    views.filter.set([{ field: 'sizeBytes', op: 'gt', value: 100 }]);
    views.columns.set({ order: ['createdAt', 'originalName'], hidden: ['sizeBytes'], widths: {} });

    expect(button.getAttribute('aria-label')).toBe('Выгрузить список в Excel');
    button.click();
    fixture.detectChanges();

    expect(request).toHaveBeenCalledWith({
      list: 'mf.files',
      filter: JSON.stringify([{ field: 'sizeBytes', op: 'gt', value: 100 }]),
      sort: '-sizeBytes',
      q: 'report',
      columns: ['createdAt', 'originalName'],
      options: { scope: 'mine' },
      lang: 'ru'
    });
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Моих выгрузках'));
  });

  it('says so when three exports are already being prepared, and shows other refusals by their words', async () => {
    const request = vi.fn()
      .mockReturnValueOnce(throwError(() => ({ status: 409, detail: 'EXPORT_BUSY' })))
      .mockReturnValueOnce(throwError(() => ({ status: 422, detail: 'Filter does not fit' })));
    const { fixture, toast, button } = await render(request);

    button.click();
    fixture.detectChanges();
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('три выгрузки'));

    button.click();
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalledWith('Filter does not fit');
  });
});
