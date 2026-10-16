import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import { emptyReport } from '@shared/entity/report/entity-reports';
import { DashboardWidgetEditorComponent } from './dashboard-widget-editor.component';
describe('dashboard widget editor', () => {
  it('offers explicit date bindings and keyboard ordering controls', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(DashboardWidgetEditorComponent);
    fixture.componentRef.setInput('item', { viewId: 4, width: 1 });
    fixture.componentRef.setInput('index', 1);
    fixture.componentRef.setInput('count', 3);
    fixture.componentRef.setInput('report', {
      id: 4,
      listCode: 'test.list',
      entity: 'test.list',
      name: 'Sales',
      state: emptyReport(),
    });
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/query-meta/test.list')
      .flush(
        queryMetaFixture('test.list', [
          metaField('title', '', 'text'),
          metaField('day', '', 'date'),
          metaField('moment', '', 'instant'),
        ]),
      );
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('.widget-actions button[aria-label]')).toHaveLength(3);
    expect(host.querySelectorAll('smt-select')).toHaveLength(2);
    http.verify();
  });
});
