import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PermissionService } from '@core/services/permission.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { ReportDatasetsApi, type ReportDataset } from './report-datasets.api';
import { ReportDatasetsComponent } from './report-datasets.component';

const DATASET: ReportDataset = {
  code: 'order_lines',
  name: { ru: 'Строки заказов', uz: 'Buyurtma qatorlari', en: 'Order lines' },
  entity: 'studio.orders',
  lines: 'items',
  fields: ['region', 'lineAmount'],
  filter: [] as never,
  measures: [
    { key: 'avgLine', labels: { ru: 'Средняя', uz: "O'rtacha", en: 'Average' }, formula: 'sum_lineAmount / count' },
  ],
  revision: 3,
  createdAt: '2026-10-18T00:00:00Z',
  modifiedAt: '2026-10-18T00:00:00Z',
};

/* The report datasets page (ADR-0044): the datasets, each to change or delete, a new one for a manager. */
describe('ReportDatasetsComponent', () => {
  const api = {
    list: vi.fn(() => of([DATASET])),
    entities: vi.fn(() => of([{ code: 'studio.orders', labelKey: 'Заказы' }])),
    remove: vi.fn(() => of(undefined)),
    lines: vi.fn(() => of([])),
    fields: vi.fn(() => of({ code: 'studio.orders', fields: [] })),
  };
  const modal = { confirm: vi.fn(() => of(true)) };
  let manage = true;

  beforeEach(() => {
    api.list.mockClear();
    api.remove.mockClear();
    manage = true;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ReportDatasetsApi, useValue: api },
        { provide: SMTModalService, useValue: modal },
        {
          provide: PermissionService,
          useValue: { hasPermission: (_form: string, action: string) => action === 'view' || manage },
        },
      ],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(ReportDatasetsComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('lists the datasets with their source, lines, fields and measures', () => {
    const { host, component } = render();
    const item = host.querySelector('[data-testid="report-datasets-item"]')!;
    expect(item.textContent).toContain('Строки заказов');
    expect(item.textContent).toContain('ds.order_lines');
    expect(item.textContent).toContain(translateTest('md.report_datasets.field_count', { n: 2 }));
    expect(component.source(DATASET)).toBe('Заказы');
    expect(host.querySelector('[data-testid="report-datasets-new"]')).not.toBeNull();
  });

  it('deletes a dataset at its revision once confirmed and opens the dialog to change one', () => {
    const { component } = render();
    component.remove(DATASET);
    expect(api.remove).toHaveBeenCalledWith('order_lines', 3);
    expect(api.list).toHaveBeenCalledTimes(2);
    component.editing.set(DATASET);
    expect(component.editing()).toBe(DATASET);
    component.saved(DATASET);
    expect(component.editing()).toBeUndefined();
  });

  it('shows why the datasets could not be read, and nothing to manage without the right', () => {
    api.list.mockReturnValueOnce(throwError(() => ({ detail: 'Нет связи' })));
    manage = false;
    const { host } = render();
    expect(host.querySelector('[data-testid="report-datasets-error"]')?.textContent).toContain('Нет связи');
    expect(host.querySelector('[data-testid="report-datasets-new"]')).toBeNull();
  });
});
