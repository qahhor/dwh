import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ToastService } from '../../core/services/toast.service';
import { LookupSources } from './lookup-sources';

describe('LookupSources', () => {
  let http: HttpTestingController;
  let sources: LookupSources;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    sources = TestBed.inject(LookupSources);
  });

  afterEach(() => http.verify());

  it('asks the users endpoint for active users by search, cursor and page size', () => {
    let items: readonly unknown[] = [];
    sources.activeUsers.page('ann', 'c2', 50).subscribe(page => (items = page?.items ?? []));
    const request = http.expectOne(req => req.url === '/api/v1/iam/users');
    expect(request.request.params.get('state')).toBe('A');
    expect(request.request.params.get('search')).toBe('ann');
    expect(request.request.params.get('cursor')).toBe('c2');
    expect(request.request.params.get('limit')).toBe('50');
    request.flush({ items: [{ id: 4, name: 'Anna', login: 'anna' }], nextCursor: null, hasMore: false, totalReturned: 1 });
    expect(items).toHaveLength(1);
    expect(sources.activeUsers.option(items[0] as never)).toEqual({ label: 'Anna', subLabel: '@anna' });
  });

  it('leaves out an empty search and a first-page cursor', () => {
    sources.activeUsers.page('', null, 20).subscribe();
    const request = http.expectOne(req => req.url === '/api/v1/iam/users');
    expect(request.request.params.has('search')).toBe(false);
    expect(request.request.params.has('cursor')).toBe(false);
    request.flush({ items: [], nextCursor: null, hasMore: false, totalReturned: 0 });
  });

  it('names chosen users by id, drops the ones it cannot read and raises no toast for them', () => {
    let found: readonly unknown[] = [];
    sources.activeUsers.resolve!([4, 9]).subscribe(rows => (found = rows));
    http.expectOne('/api/v1/iam/users/4').flush({ id: 4, name: 'Anna', login: 'anna' });
    http.expectOne('/api/v1/iam/users/9').flush({ detail: 'Not found' }, { status: 404, statusText: 'Not Found' });
    expect(found).toEqual([{ id: 4, name: 'Anna', login: 'anna' }]);
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
  });
});
