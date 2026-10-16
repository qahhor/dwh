import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { describe, expect, it } from 'vitest';
import { ListViewState, type ListViewsApi } from '@shared/list-views/list-views';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { EntityReportsApi, type ReportState } from './entity-reports';
import { SMTEntityReportBuilderComponent } from './smt-entity-report-builder.component';

const BASE = queryMetaFixture('example.requests', [metaField('qty', '', 'number', { label: 'Quantity' })]);
const PROJECTED = queryMetaFixture('example.requests', [
  ...BASE.fields,
  metaField('productUnit', '', 'enum', { label: 'Unit', enumValues: ['pcs', 'kg'] }),
]);
const VALUE = {
  groups: [],
  measures: [{ op: 'count' as const }],
  rows: [{ groups: [], values: [3] }],
  truncated: false,
};
const STATE = {
  groupBy: [{ field: 'productUnit' }],
  measures: [{ op: 'count' }],
  filter: [{ field: 'productUnit', op: 'eq', value: 'kg' }],
  chart: 'horizontalBar',
  dataset: 'example.requests.products',
} as ReportState;

async function setup() {
  const reports = { run: vi.fn(() => of(VALUE)), saved: vi.fn(() => of(VALUE)), list: () => of([]) };
  const api = { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } as unknown as ListViewsApi;
  const views = new ListViewState('example.requests', api, { defaultSort: () => null, onApply: () => undefined });
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: EntityReportsApi, useValue: reports }],
  });
  const fixture = TestBed.createComponent(SMTEntityReportBuilderComponent);
  fixture.componentRef.setInput('code', 'example.requests');
  fixture.componentRef.setInput('meta', BASE);
  fixture.componentRef.setInput('views', views);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  return { fixture, builder: fixture.componentInstance, http, views, reports };
}
async function settle(fixture: { detectChanges(): void }) {
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve, 320));
  fixture.detectChanges();
}

describe('report dataset selection (ADR-0039)', () => {
  it('loads dataset metadata and carries the source to run, save and export without changing the list filter', async () => {
    const { fixture, builder, http, views, reports } = await setup();
    http
      .expectOne('/api/v1/entities/example.requests/report-datasets')
      .flush({ items: [{ code: 'example.requests.products', titleKey: 'example.dataset' }] });
    builder.open(STATE);
    fixture.detectChanges();
    http.expectOne('/api/v1/entities/example.requests/report-meta?dataset=example.requests.products').flush(PROJECTED);
    await settle(fixture);
    expect(builder.groupOptions().some((option) => option.id === 'productUnit')).toBe(true);
    expect(reports.run).toHaveBeenLastCalledWith(
      'example.requests',
      expect.objectContaining({ dataset: 'example.requests.products' }),
    );
    expect(builder.state()).toMatchObject({ dataset: 'example.requests.products' });
    expect(builder.exportSource()).toMatchObject({ kind: 'query', state: { dataset: 'example.requests.products' } });
    expect(views.filter()).toEqual([]);
    http.verify();
  });

  it('does not fall back to the root query when dataset access is revoked', async () => {
    const { fixture, builder, http, reports } = await setup();
    http.expectOne('/api/v1/entities/example.requests/report-datasets').flush({ items: [] });
    builder.open(STATE);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/entities/example.requests/report-meta?dataset=example.requests.products')
      .flush({ detail: 'Source unavailable' }, { status: 404, statusText: 'Not Found' });
    reports.run.mockClear();
    await settle(fixture);
    expect(reports.run).not.toHaveBeenCalled();
    expect(builder.run().result).toBeNull();
    expect(builder.exportSource()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Source unavailable');
    http.verify();
  });
});
