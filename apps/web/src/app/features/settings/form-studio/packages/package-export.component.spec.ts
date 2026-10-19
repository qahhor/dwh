import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { translateTest } from '@testing/i18n-test.stub';
import { PackageExportComponent } from './package-export.component';
import { PackagesApi, type SolutionExportPlan } from './packages.api';

const ORDERS = {
  entityCode: 'studio.orders',
  title: { ru: 'Заказы', uz: 'Buyurtmalar', en: 'Orders' },
  status: 'active',
};
const PLAN: SolutionExportPlan = {
  included: [
    {
      kind: 'list',
      key: 'regions',
      title: { ru: 'Регионы', uz: 'Hududlar', en: 'Regions' },
      reason: 'dependency',
      of: 'form:studio.orders',
    },
    { kind: 'form', key: 'studio.orders', title: ORDERS.title, reason: 'selected' },
  ],
  related: [
    {
      kind: 'report',
      key: '12',
      title: { ru: 'По регионам', uz: 'Hududlar', en: 'Regions' },
      reason: 'related',
      of: 'form:studio.orders',
    },
    { kind: 'dataset', key: 'sales', title: { ru: 'Продажи', uz: 'Savdo', en: 'Sales' }, reason: 'related' },
  ],
  placeholders: [
    { key: 'role-1', kind: 'role', labels: { ru: 'Согласующий', uz: 'Tasdiqlovchi', en: 'Approver' }, required: false },
  ],
};

/* The export of a solution package (ADR-0047): its form, the plan of its contents, additions and the download. */
describe('PackageExportComponent', () => {
  const api = {
    forms: vi.fn(() => of({ items: [ORDERS] })),
    plan: vi.fn(() => of(PLAN)),
    export: vi.fn(() => of({ code: 'orders', checksum: 'x' })),
  };
  const toast = { success: vi.fn(), error: vi.fn() };

  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockClear());
    toast.success.mockClear();
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PackagesApi, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  function render() {
    const fixture = TestBed.createComponent(PackageExportComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  function fill(component: PackageExportComponent): void {
    component.model.set({
      code: 'orders',
      version: '1.2.0',
      ru: 'Заказы',
      uz: 'Buyurtmalar',
      en: 'Orders',
      listItems: true,
    });
    component.toggleForm('studio.orders', true);
  }

  it('asks for the fields and a form before anything is asked of the server', () => {
    const { component, fixture, host } = render();
    expect(host.querySelector('[data-testid="package-export-form-studio.orders"]')?.textContent).toContain('Заказы');
    component.show();
    fixture.detectChanges();
    expect(api.plan).not.toHaveBeenCalled();
    expect(component.selectionError()).toBe(translateTest('studio.packages.export.forms_required'));
    expect(component.summary().length).toBeGreaterThan(0);
    component.model.update((model) => ({ ...model, code: 'Orders', version: '1' }));
    component.download();
    expect(api.export).not.toHaveBeenCalled();
    expect(component.error('code')).not.toBe('');
  });

  it('shows the contents with their reasons and adds a related item', () => {
    const { component, fixture, host } = render();
    fill(component);
    component.show();
    fixture.detectChanges();
    expect(api.plan).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'orders', forms: ['studio.orders'], reports: [] }),
    );
    const included = host.querySelectorAll('[data-testid="package-export-included"]');
    expect(included.length).toBe(2);
    expect(component.reason(PLAN.included[0])).toBe(
      translateTest('studio.packages.reason.of', { of: 'form:studio.orders' }),
    );
    expect(component.reason(PLAN.included[1])).toBe(translateTest('studio.packages.reason.selected'));
    expect(host.querySelector('[data-testid="package-export-plan"]')?.textContent).toContain('Согласующий');
    component.toggleRelated(PLAN.related[0], true);
    component.toggleRelated(PLAN.related[1], true);
    expect(component.isAdded(PLAN.related[0])).toBe(true);
    component.toggleRelated(PLAN.related[1], false);
    component.download();
    expect(api.export).toHaveBeenCalledWith(expect.objectContaining({ reports: [12], datasets: [], listItems: true }));
    expect(toast.success).toHaveBeenCalledWith(translateTest('studio.packages.exported'));
    component.toggleForm('studio.orders', false);
    expect(component.plan()).toBeNull();
  });

  it('shows why the server refused the export', () => {
    api.export.mockReturnValueOnce(
      throwError(() => ({
        detail: 'В пакет нельзя положить значения',
        errors: [{ field: 'reports.12', message: 'Значение поля «client» ссылается на запись' }],
      })),
    );
    const { component, fixture, host } = render();
    fill(component);
    component.download();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="package-export-error"]')?.textContent).toContain('ссылается на запись');
    api.plan.mockReturnValueOnce(throwError(() => ({})));
    component.show();
    expect(component.problems()[0]).toBe(translateTest('studio.packages.action_failed'));
    expect(component.planning()).toBe(false);
  });
});
