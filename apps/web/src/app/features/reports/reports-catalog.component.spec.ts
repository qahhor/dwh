import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NavigationService } from '@core/services/navigation.service';
import { ReportCatalogApi, type ReportViewItem } from '@shared/entity/report/report-catalog.api';
import { ReportsCatalogComponent } from './reports-catalog.component';

function report(id: number, change: Partial<ReportViewItem> = {}): ReportViewItem {
  return {
    id,
    entity: 'studio.clients',
    listCode: 'studio.clients',
    kind: 'report',
    name: 'По регионам ' + id,
    state: { groupBy: [{ field: 'region' }], measures: [{ op: 'count' }], filter: [], chart: 'bar' },
    lockVersion: 1,
    modifiedAt: '2026-10-18T08:00:00Z',
    canEdit: true,
    canShare: true,
    shared: false,
    owned: true,
    ...change,
  };
}

describe('ReportsCatalogComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  async function render(
    search = vi.fn(() =>
      of({
        items: [report(1), report(2, { owned: false, canEdit: false, canShare: false, shared: true })],
        hasMore: false,
        nextCursor: null,
        totalEstimated: 2,
        totalExact: true,
      }),
    ),
  ) {
    await TestBed.configureTestingModule({
      imports: [ReportsCatalogComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ReportCatalogApi, useValue: { search, sharing: vi.fn(), share: vi.fn(), roles: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(NavigationService).entityItems.set([
      {
        code: 'studio.clients',
        form: 'studio.clients',
        route: '/e/studio.clients',
        labelKey: 'studio.live.clients.title',
        icon: 'storefront',
        section: 'workspace',
        order: 1,
      },
    ] as never);
    const fixture = TestBed.createComponent(ReportsCatalogComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, search };
  }

  it('lists the reports the person may open with their ownership and links each to its list report tab', async () => {
    const { root, search } = await render();
    expect(search).toHaveBeenCalledWith(
      { q: '', listCode: null, owner: null, kind: 'report' },
      null,
      expect.any(Number),
    );
    const items = Array.from(root.querySelectorAll('[data-testid="reports-item"]'));
    expect(items.map((item) => item.querySelector('h2')!.textContent!.trim())).toEqual([
      'По регионам 1',
      'По регионам 2',
    ]);
    const open = items[0].querySelector<HTMLAnchorElement>('[data-testid="reports-open"]')!;
    expect(open.getAttribute('href')).toBe('/e/studio.clients?mode=report&report=1');
    expect(open.getAttribute('aria-label')).toContain('По регионам 1');
    expect(items[0].querySelector('[data-testid="report-share"]')).not.toBeNull();
    expect(items[1].querySelector('[data-testid="report-share"]')).toBeNull();
    expect(items[1].querySelectorAll('a').length).toBe(1);
  });

  it('narrows the catalog by name after typing stops, by list and by ownership', async () => {
    vi.useFakeTimers();
    const { fixture, component, search } = await render();
    component.setSearch('регион');
    expect(search).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(400);
    fixture.detectChanges();
    expect(search).toHaveBeenLastCalledWith(
      { q: 'регион', listCode: null, owner: null, kind: 'report' },
      null,
      expect.any(Number),
    );
    component.setList('studio.clients');
    component.setOwner('shared');
    fixture.detectChanges();
    expect(search).toHaveBeenLastCalledWith(
      { q: 'регион', listCode: 'studio.clients', owner: 'shared', kind: 'report' },
      null,
      expect.any(Number),
    );
    component.setOwner('everyone');
    component.setList('');
    fixture.detectChanges();
    expect(component.filter()).toEqual({ q: 'регион', listCode: null, owner: null, kind: 'report' });
    expect(component.entityOptions()).toEqual([{ id: 'studio.clients', label: expect.any(String) }]);
    expect(component.entityName(report(3, { entity: 'unknown.entity' }))).toBe('unknown.entity');
  });

  it('says when nothing is found and offers a retry when the catalog fails', async () => {
    const empty = await render(
      vi.fn(() => of({ items: [], hasMore: false, nextCursor: null, totalEstimated: 0, totalExact: true })),
    );
    expect(empty.root.querySelector('[data-testid="reports-empty"]')).not.toBeNull();
    TestBed.resetTestingModule();
    const failing = await render(vi.fn(() => throwError(() => new Error('down'))));
    expect(failing.root.querySelector('[data-testid="reports-error"]')).not.toBeNull();
  });
});
