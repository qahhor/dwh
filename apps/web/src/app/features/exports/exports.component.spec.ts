import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ExportItem, ExportsService } from '../../core/services/exports.service';
import { ExportsComponent } from './exports.component';

const item = (patch: Partial<ExportItem>): ExportItem => ({
  id: 'e1', list: 'upl.packages', state: 'done', rowsCount: 12, truncated: false, fileName: 'upl_packages.xlsx',
  sizeBytes: 2048, createdAt: '2026-09-25T09:00:00Z', expiresAt: '2026-10-02T09:00:00Z', ...patch
});

async function render(journal: ReturnType<typeof vi.fn>) {
  await TestBed.configureTestingModule({
    imports: [ExportsComponent],
    providers: [{ provide: ExportsService, useValue: { journal, fileUrl: (id: string) => `/api/v1/exports/${id}/file` } }]
  }).compileComponents();
  const fixture = TestBed.createComponent(ExportsComponent);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

const rows = (host: HTMLElement) => [...host.querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[];

describe('ExportsComponent', () => {
  afterEach(() => vi.useRealTimers());

  it('lists exports with their list, state and rows, and offers the file of a finished one', async () => {
    const { host } = await render(vi.fn(() => of([
      item({}),
      item({ id: 'e2', list: 'mf.files', state: 'failed', rowsCount: null, fileName: null, errorCode: 'EXPORT_FORBIDDEN' }),
      item({ id: 'e3', list: 'upl.sources', truncated: true, rowsCount: 50000 })
    ])));

    const list = rows(host);
    expect(list).toHaveLength(3);
    expect(list[0].textContent).toContain('Загрузки');
    expect(list[0].textContent).toContain('Готово');
    const download = list[0].querySelector('[data-testid="exports-download"]') as HTMLAnchorElement;
    expect(download.getAttribute('href')).toBe('/api/v1/exports/e1/file');
    expect(download.getAttribute('aria-label')).toBe('Скачать upl_packages.xlsx');
    expect(list[1].textContent).toContain('Не удалась');
    expect(list[1].textContent).toContain('Нет права на этот список');
    expect(list[1].querySelector('[data-testid="exports-download"]')).toBeNull();
    expect(list[2].textContent).toContain('Только первые строки');
  });

  it('looks again while an export is unfinished and stops when all are done', async () => {
    vi.useFakeTimers();
    const journal = vi.fn()
      .mockReturnValueOnce(of([item({ state: 'running', rowsCount: null })]))
      .mockReturnValueOnce(of([item({})]));
    const { fixture, host } = await render(journal);
    expect(rows(host)[0].textContent).toContain('Готовится');

    vi.advanceTimersByTime(3000);
    fixture.detectChanges();
    expect(journal).toHaveBeenCalledTimes(2);
    expect(rows(host)[0].textContent).toContain('Готово');

    vi.advanceTimersByTime(10000);
    expect(journal).toHaveBeenCalledTimes(2);
  });

  it('says when there are no exports and when the journal could not be loaded', async () => {
    const empty = await render(vi.fn(() => of([])));
    expect(empty.host.textContent).toContain('Выгрузок пока нет');

    TestBed.resetTestingModule();
    const pending = new Subject<ExportItem[]>();
    const journal = vi.fn().mockReturnValueOnce(throwError(() => ({ status: 500 }))).mockReturnValueOnce(pending);
    const failed = await render(journal);
    expect(failed.host.querySelector('[data-testid="exports-error"]')?.getAttribute('role')).toBe('alert');
  });
});
