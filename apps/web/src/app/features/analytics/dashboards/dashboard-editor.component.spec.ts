import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { DashboardEditorComponent } from './dashboard-editor.component';
import { DashboardStore } from './dashboard.store';
describe('dashboard editor catalog', () => {
  it('allows paging through an empty visible report window', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), DashboardStore] });
    const store = TestBed.inject(DashboardStore);
    store.draft.set({ name: 'My dashboard', widgets: [] });
    const fixture = TestBed.createComponent(DashboardEditorComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/report-views?limit=20').flush({ items: [], hasMore: true, nextCursor: 'window2' });
    fixture.detectChanges();
    const more = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="dashboard-reports-more"]',
    ) as HTMLButtonElement;
    expect(more).not.toBeNull();
    more.click();
    http.expectOne('/api/v1/report-views?limit=20&cursor=window2').flush({ items: [], hasMore: false });
    http.verify();
  });
});
