import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { translateTest } from '@testing/i18n-test.stub';
import { PackageImportComponent } from './package-import.component';
import { PackagesApi, type SolutionImport } from './packages.api';

const TITLE = { ru: 'Заказ со строками и согласованием', uz: 'Buyurtma', en: 'Order' };
function view(changes: Partial<SolutionImport> = {}): SolutionImport {
  return {
    id: 9,
    code: 'order-approval',
    version: '1.0.0',
    title: TITLE,
    description: { ru: 'Заказ клиента', uz: 'Buyurtma', en: 'Order' },
    checksum: 'a'.repeat(64),
    source: 'template',
    status: 'preview',
    producedBy: 'template',
    placeholders: [
      {
        key: 'approver',
        kind: 'role',
        labels: { ru: 'Согласующий', uz: 'Tasdiqlovchi', en: 'Approver' },
        required: false,
      },
      {
        key: 'mailing-recipients',
        kind: 'users',
        labels: { ru: 'Получатели рассылки', uz: 'Oluvchilar', en: 'Recipients' },
        required: true,
      },
    ],
    mapping: {},
    items: [
      {
        kind: 'form',
        key: 'studio.orders',
        title: { ru: 'Заказы', uz: 'Buyurtmalar', en: 'Orders' },
        outcome: 'create',
        problems: [],
        notes: [],
      },
      {
        kind: 'automation',
        key: 'order-approval',
        title: { ru: 'Согласование заказа', uz: 'Tasdiqlash', en: 'Approval' },
        outcome: 'create',
        problems: [],
        notes: ['studio.packages.note.paused'],
      },
      {
        kind: 'report',
        key: 'orders-by-region',
        title: { ru: 'Заказы по регионам', uz: 'Hududlar', en: 'Regions' },
        outcome: 'refused',
        problems: [{ message: 'error.studio.solution_owner' }],
        notes: [],
      },
    ],
    counts: { create: 2, upgrade: 0, unchanged: 0, skip: 0, refused: 1 },
    createdAt: '2026-10-19T08:00:00Z',
    createdBy: 1,
    checkedAt: '2026-10-19T08:00:00Z',
    ...changes,
  };
}

/* An import of a solution package (ADR-0047): outcomes and reasons, the mapping, a check and the application. */
describe('PackageImportComponent', () => {
  const api = {
    get: vi.fn(() => of(view())),
    roles: vi.fn(() =>
      of([
        { id: 4, name: 'Согласующий', state: 'A' },
        { id: 5, name: 'Закрытая', state: 'P' },
      ]),
    ),
    apply: vi.fn(() => of(view({ counts: { create: 3, upgrade: 0, unchanged: 0, skip: 0, refused: 0 } }))),
  };
  const modal = { confirm: vi.fn(() => of(true)) };
  const toast = { success: vi.fn(), error: vi.fn() };

  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockClear());
    toast.success.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '9' }) } } },
        { provide: PackagesApi, useValue: api },
        { provide: SMTModalService, useValue: modal },
        { provide: ToastService, useValue: toast },
      ],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(PackageImportComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('shows each item with its outcome and reasons, and the placeholders to map', () => {
    const { host, component } = render();
    expect(api.get).toHaveBeenCalledWith(9);
    expect(host.querySelector('[data-testid="package-item-report-orders-by-region"]')?.textContent).toContain(
      translateTest('error.studio.solution_owner'),
    );
    expect(host.querySelector('[data-testid="package-item-automation-order-approval"]')?.textContent).toContain(
      translateTest('studio.packages.note.paused'),
    );
    expect(host.querySelector('[data-testid="package-import-refused"]')).not.toBeNull();
    expect(component.roleOptions()).toEqual([{ id: 4, label: 'Согласующий' }]);
    expect(host.querySelector('[data-testid="package-import-mapping"]')?.textContent).toContain('Получатели рассылки');
    expect(component.counts()).toContain(translateTest('studio.packages.count.refused', { n: 1 }));
  });

  it('checks the mapping, then applies it after a confirmation', () => {
    const { component } = render();
    component.setRole('approver', 4);
    component.setUsers('mailing-recipients', [7, 8]);
    expect(component.role('approver')).toBe(4);
    expect(component.users('mailing-recipients')).toEqual([7, 8]);
    component.check();
    expect(api.apply).toHaveBeenCalledWith(9, { approver: [4], 'mailing-recipients': [7, 8] }, true);
    component.setRole('approver', null);
    api.apply.mockReturnValueOnce(of(view({ status: 'applied', appliedAt: '2026-10-19T09:00:00Z' })));
    component.apply();
    expect(modal.confirm).toHaveBeenCalled();
    expect(api.apply).toHaveBeenLastCalledWith(9, { approver: [], 'mailing-recipients': [7, 8] }, false);
    expect(toast.success).toHaveBeenCalledWith(translateTest('studio.packages.applied'));
    expect(component.applied()).toBe(true);
  });

  it('shows the errors of a refused mapping under its placeholder and reads the import again', () => {
    api.apply.mockReturnValueOnce(
      throwError(() => ({
        detail: 'Сопоставление заполнено с ошибками',
        errors: [{ field: 'mapping.mailing-recipients', message: 'Подстановка обязательна' }],
      })),
    );
    const { component, fixture, host } = render();
    component.apply();
    fixture.detectChanges();
    expect(component.mappingErrors()['mailing-recipients']).toBe('Подстановка обязательна');
    expect(host.querySelector('[data-testid="package-import-failure"]')?.textContent).toContain('ошибками');
    expect(api.get).toHaveBeenCalledTimes(2);
    component.setUsers('mailing-recipients', [7]);
    expect(component.mappingErrors()['mailing-recipients']).toBeUndefined();
  });

  it('shows an applied import with its links and without the mapping', () => {
    api.get.mockReturnValueOnce(of(view({ status: 'applied', mapping: { approver: [4] } })));
    const { host, component } = render();
    expect(host.querySelector('[data-testid="package-import-applied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="package-import-mapping"]')).toBeNull();
    expect(component.hasAutomations()).toBe(true);
    expect(component.role('approver')).toBe(4);
    modal.confirm.mockReturnValueOnce(of(false));
    component.apply();
    expect(api.apply).not.toHaveBeenCalled();
  });

  it('reports an import that does not load', () => {
    api.get.mockReturnValueOnce(throwError(() => ({})));
    const { host } = render();
    expect(host.querySelector('[data-testid="package-import-error"]')?.textContent).toContain(
      translateTest('studio.packages.load_failed'),
    );
  });
});
