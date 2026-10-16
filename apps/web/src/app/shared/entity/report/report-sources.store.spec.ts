import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { queryMetaFixture } from '@testing/entity-page';
import { ReportSourcesStore } from './report-sources.store';

describe('report source lifecycle', () => {
  const api = { get: vi.fn() };
  let source: ReportSourcesStore;
  beforeEach(() => {
    api.get.mockReset().mockReturnValue(of({ items: [] }));
    TestBed.configureTestingModule({ providers: [ReportSourcesStore, { provide: ApiService, useValue: api }] });
    source = TestBed.inject(ReportSourcesStore);
  });
  it('does not fetch before configuration or refetch for the same entity', () => {
    source.reloadCatalog();
    expect(api.get).not.toHaveBeenCalled();
    source.configure('example.requests');
    source.configure('example.requests');
    expect(api.get).toHaveBeenCalledTimes(1);
  });
  it('shows a catalog failure and retries without selecting another source', () => {
    api.get.mockReturnValueOnce(throwError(() => ({ detail: 'Unavailable catalog' })));
    source.configure('example.requests');
    expect(source.catalogError()).toBe('Unavailable catalog');
    api.get.mockReturnValue(of({ items: [{ code: 'dataset', titleKey: 'ui.report.dataset' }] }));
    source.reloadCatalog();
    expect(source.catalogError()).toBe('');
    expect(source.options().map((item) => item.id)).toEqual(['', 'dataset']);
  });
  it('cancels old metadata when a different projection is selected', () => {
    source.configure('example.requests');
    const old = new Subject<ReturnType<typeof queryMetaFixture>>();
    const current = new Subject<ReturnType<typeof queryMetaFixture>>();
    api.get.mockReturnValueOnce(old).mockReturnValueOnce(current);
    source.select('old');
    source.select('current');
    old.next(queryMetaFixture('old', []));
    old.complete();
    expect(source.projected()).toBeNull();
    expect(source.loading()).toBe(true);
    current.next(queryMetaFixture('current', []));
    current.complete();
    expect(source.projected()?.code).toBe('current');
    expect(source.loading()).toBe(false);
  });
  it('retains the requested dataset on denial and clears projection only on explicit base selection', () => {
    source.configure('example.requests');
    api.get.mockReturnValueOnce(throwError(() => ({ status: 404 })));
    source.select('denied');
    expect(source.dataset()).toBe('denied');
    expect(source.projected()).toBeNull();
    expect(source.error()).not.toBe('');
    source.select(null);
    expect(source.dataset()).toBeNull();
    expect(source.error()).toBe('');
  });
});
