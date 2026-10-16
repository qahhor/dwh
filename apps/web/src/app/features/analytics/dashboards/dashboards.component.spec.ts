import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { BehaviorSubject, Subject } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { DashboardsComponent } from './dashboards.component';
describe('named dashboards', () => {
  it('restores the current URL when browser history changes the panel during a write', async () => {
    const params = new BehaviorSubject(convertToParamMap({}));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: params, snapshot: { queryParamMap: params.value } } },
      ],
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(DashboardsComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/dashboards?limit=20').flush({ items: [], hasMore: false });
    await new Promise((resolve) => setTimeout(resolve));
    http.match('/api/v1/report-widgets').forEach((req) => req.flush([]));
    fixture.componentInstance.store.selectedId.set(9);
    fixture.componentInstance.store.busy.set(true);
    params.next(convertToParamMap({ dashboard: '10' }));
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { dashboard: 9 }, replaceUrl: true }),
    );
    expect(fixture.componentInstance.store.selectedId()).toBe(9);
    http.verify();
  });
  it('ignores an older discard decision after a second history navigation', async () => {
    const params = new BehaviorSubject(convertToParamMap({ dashboard: '9' }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: params, snapshot: { queryParamMap: params.value } } },
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const decisions: Subject<boolean>[] = [];
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockImplementation(() => {
      const decision = new Subject<boolean>();
      decisions.push(decision);
      return decision;
    });
    const fixture = TestBed.createComponent(DashboardsComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/dashboards?limit=20').flush({ items: [], hasMore: false });
    http
      .expectOne('/api/v1/dashboards/9')
      .flush({ id: 9, name: 'Mine', revision: 3, canEdit: true, canShare: false, widgets: [], widgetCount: 0 });
    await new Promise((resolve) => setTimeout(resolve));
    http.match('/api/v1/report-widgets').forEach((req) => req.flush([]));
    fixture.detectChanges();
    fixture.componentInstance.store.edit();
    fixture.componentInstance.store.rename('Unsaved');
    params.next(convertToParamMap({ dashboard: '10' }));
    fixture.detectChanges();
    params.next(convertToParamMap({ dashboard: '11' }));
    fixture.detectChanges();
    expect(decisions).toHaveLength(2);
    decisions[1].next(false);
    decisions[1].complete();
    decisions[0].next(true);
    decisions[0].complete();
    const late = http.match('/api/v1/dashboards/10');
    late.forEach((req) => req.flush({ id: 10, name: 'Wrong', revision: 1, widgets: [] }));
    http.match('/api/v1/report-views?limit=20').forEach((req) => req.flush({ items: [], hasMore: false }));
    expect(late).toHaveLength(0);
    expect(fixture.componentInstance.store.selectedId()).toBe(9);
    http.verify();
  });
  it('exposes create and paging without replacing an empty catalog by an error', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    const fixture = TestBed.createComponent(DashboardsComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/dashboards?limit=20').flush({ items: [], hasMore: true, nextCursor: 'next' });
    await new Promise((resolve) => setTimeout(resolve));
    http.match('/api/v1/report-widgets').forEach((req) => req.flush([]));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="dashboard-create"]')).not.toBeNull();
    const more = fixture.nativeElement.querySelector('[data-testid="dashboards-more"]') as HTMLButtonElement;
    expect(more).not.toBeNull();
    more.click();
    http.expectOne('/api/v1/dashboards?limit=20&cursor=next').flush({ items: [], hasMore: false });
    http.verify();
  });
});
