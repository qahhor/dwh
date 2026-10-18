import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { ReportDatasetsApi, type ReportDataset } from './report-datasets.api';
import { ReportDatasetDialogComponent } from './report-dataset-dialog.component';

const FIELDS = queryMetaFixture('studio.orders', [
  metaField('region', '', 'enum', { label: 'Регион', enumValues: ['north'] }),
  metaField('lineAmount', '', 'number', { label: 'Сумма строки', ops: ['gt'] }),
]);

/* The dialog of a report dataset (ADR-0044): source, lines, fields, fixed filter and computed measures. */
describe('ReportDatasetDialogComponent', () => {
  const api = {
    lines: vi.fn(() => of([{ code: 'items', labelKey: 'Строки' }])),
    fields: vi.fn(() => of(FIELDS)),
    create: vi.fn((body: unknown) => of({ ...(body as object), revision: 1 } as unknown as ReportDataset)),
    update: vi.fn(() => of({} as ReportDataset)),
  };

  beforeEach(() => {
    api.fields.mockClear();
    api.create.mockClear();
    api.update.mockClear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: ReportDatasetsApi, useValue: api }],
    });
  });

  function render(dataset: ReportDataset | null = null) {
    const fixture = TestBed.createComponent(ReportDatasetDialogComponent);
    fixture.componentRef.setInput('dataset', dataset);
    fixture.componentRef.setInput('sources', [{ code: 'studio.orders', labelKey: 'Заказы' }]);
    const saved: ReportDataset[] = [];
    fixture.componentInstance.saved.subscribe((value) => saved.push(value));
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, saved };
  }

  it('creates a dataset of lines with its fields, filter and a computed measure', () => {
    const { component, saved } = render();
    component.code.set('order_lines');
    component.setName('ru', 'Строки');
    component.setName('uz', 'Qatorlar');
    component.setName('en', 'Lines');
    component.setEntity('studio.orders');
    expect(component.lineOptions().map((option) => option.id)).toEqual(['items']);
    component.setLines('items');
    expect(api.fields).toHaveBeenLastCalledWith('studio.orders', 'items');
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['region', 'lineAmount']);
    component.fields.set(['region', 'lineAmount']);
    component.setFilter([{ field: 'lineAmount', op: 'gt', value: 0 }], 'all');
    component.addMeasure();
    component.setMeasure(0, 'key', 'avgLine');
    component.setMeasure(0, 'ru', 'Средняя');
    component.setMeasure(0, 'formula', 'sum_lineAmount / count');
    component.submit();
    expect(api.create).toHaveBeenCalledWith({
      code: 'order_lines',
      name: { ru: 'Строки', uz: 'Qatorlar', en: 'Lines' },
      entity: 'studio.orders',
      lines: 'items',
      fields: ['region', 'lineAmount'],
      filter: [{ field: 'lineAmount', op: 'gt', value: 0 }],
      measures: [{ key: 'avgLine', labels: { ru: 'Средняя', uz: '', en: '' }, formula: 'sum_lineAmount / count' }],
    });
    expect(saved).toHaveLength(1);
    component.removeMeasure(0);
    expect(component.measures()).toEqual([]);
  });

  it('changes a dataset at its revision and shows the server message under the part to fix', () => {
    const dataset = {
      code: 'order_lines',
      name: { ru: 'Строки', uz: 'Qatorlar', en: 'Lines' },
      entity: 'studio.orders',
      lines: null,
      fields: ['region'],
      filter: [{ field: 'region', op: 'eq', value: 'north' }],
      measures: [],
      revision: 4,
      createdAt: '',
      modifiedAt: '',
    } as unknown as ReportDataset;
    api.update.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        detail: 'Проверьте набор данных',
        errors: [{ field: 'fields[0]', message: 'Нет такого поля' }],
      })),
    );
    const { component } = render(dataset);
    expect(component.creating()).toBe(false);
    expect(component.conditions()).toEqual([{ field: 'region', op: 'eq', value: 'north' }]);
    component.submit();
    expect(api.update).toHaveBeenCalledWith('order_lines', 4, expect.objectContaining({ fields: ['region'] }));
    expect(component.error('fields[0]')).toBe('Нет такого поля');
    expect(component.alert()).toContain('Проверьте набор данных');
  });
});
