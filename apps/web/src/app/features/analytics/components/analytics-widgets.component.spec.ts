import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { metaField, queryMetaFixture } from '@testing/entity-page';
import type { ReportWidget } from '@shared/entity/report/entity-reports';
import { AnalyticsWidgetsComponent } from './analytics-widgets.component';

const WIDGET: ReportWidget = {
  id: 7,
  kind: 'widget',
  listCode: 'example.orders',
  entity: 'example.orders',
  name: 'Заказы по статусам',
  state: { groupBy: [{ field: 'status' }], measures: [{ op: 'count' }], filter: [], chart: 'bar' },
  lockVersion: 2,
};

const LIST = queryMetaFixture('example.orders', [
  metaField('number', '', 'text', { label: 'Номер', sortable: true }),
  metaField('status', '', 'enum', { label: 'Статус', enumValues: ['draft', 'posted'] }),
]);

/*
 * The viewer's widgets on the analytics dashboard (ADR-0032 10.2; plan 10/10, item 5.8): saved reports, each run under
 * the entity's own rights, each card failing on its own.
 */
describe('AnalyticsWidgetsComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AnalyticsWidgetsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(AnalyticsWidgetsComponent);
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  async function drain(fixture: { detectChanges(): void }) {
    for (let round = 0; round < 3; round += 1) {
      await new Promise((resolve) => setTimeout(resolve));
      fixture.detectChanges();
    }
  }

  it('says how to add a widget when the viewer has none', async () => {
    const { fixture, host } = await render();
    http.expectOne('/api/v1/report-widgets').flush([]);
    await drain(fixture);

    expect(host.querySelector('[data-testid="analytics-widgets-empty"]')).not.toBeNull();
    expect(host.querySelector('h2')?.textContent).toContain('Мои виджеты');
  });

  it('runs each saved widget and draws its chart; a refused one fails in its own card', async () => {
    const { fixture, host } = await render();
    http.expectOne('/api/v1/report-widgets').flush([WIDGET, { ...WIDGET, id: 8, name: 'Сломанный' }]);
    await drain(fixture);

    http.match('/api/v1/query-meta/example.orders').forEach((request) => request.flush(LIST));
    http.expectOne('/api/v1/entities/example.orders/reports/7').flush({
      groups: [{ field: 'status', implicit: false }],
      measures: [{ op: 'count' }],
      rows: [
        { groups: ['draft'], values: [2] },
        { groups: ['posted'], values: [1] },
      ],
      truncated: false,
    });
    http
      .expectOne('/api/v1/entities/example.orders/reports/8')
      .flush({ detail: 'Нет такого поля в списке: total' }, { status: 422, statusText: 'Unprocessable' });
    await drain(fixture);

    const cards = host.querySelectorAll('[data-testid="analytics-widget"]');
    expect(cards).toHaveLength(2);
    expect(cards[0].textContent).toContain('Заказы по статусам');
    expect(cards[0].querySelectorAll('[data-testid="bar-chart-bar"]')).toHaveLength(2);
    expect(cards[0].querySelector('a')?.getAttribute('href')).toBe('/e/example.orders');
    expect(cards[1].querySelector('[data-testid="dash-card-error"]')).not.toBeNull();
  });

  it('takes a widget off the dashboard: it becomes a report of its list', async () => {
    const { fixture, host } = await render();
    http.expectOne('/api/v1/report-widgets').flush([WIDGET]);
    await drain(fixture);
    http.match('/api/v1/query-meta/example.orders').forEach((request) => request.flush(LIST));
    http.expectOne('/api/v1/entities/example.orders/reports/7').flush({
      groups: [],
      measures: [{ op: 'count' }],
      rows: [{ groups: [], values: [3] }],
      truncated: false,
    });
    await drain(fixture);

    (host.querySelector('[data-testid="analytics-widget"] button[aria-label]') as HTMLButtonElement).click();
    const update = http.expectOne('/api/v1/list-views/example.orders/7');
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      name: 'Заказы по статусам',
      kind: 'report',
      state: WIDGET.state,
      isDefault: false,
      lockVersion: 2,
    });
    update.flush({ ...WIDGET, kind: 'report', lockVersion: 3 });
    await drain(fixture);

    expect(host.querySelectorAll('[data-testid="analytics-widget"]')).toHaveLength(0);
  });
});
