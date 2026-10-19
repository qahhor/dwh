import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FormMetaService } from '@core/services/form-meta.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { AUTOMATION, ORDER_META, RUNS, SCHEDULED } from '@testing/automations';
import { translateTest } from '@testing/i18n-test.stub';
import { AutomationsApi } from './automations.api';
import { AutomationsComponent } from './automations.component';

/* The automations of live forms (ADR-0046): listed with their trigger and last run, paused, run now, deleted. */
describe('AutomationsComponent', () => {
  const api = {
    forms: vi.fn(() =>
      of({ items: [{ entityCode: 'studio.orders', title: { ru: 'Заказы', uz: 'Buyurtmalar', en: 'Orders' } }] }),
    ),
    list: vi.fn(() => of({ items: [AUTOMATION, SCHEDULED], hasMore: false, nextCursor: null })),
    update: vi.fn(() => of(AUTOMATION)),
    runNow: vi.fn(() => of({ queued: 2, truncated: false })),
    remove: vi.fn(() => of(undefined)),
    runs: vi.fn(() => of({ items: RUNS, hasMore: false, nextCursor: null })),
  };
  const modal = { confirm: vi.fn(() => of(true)) };
  const toast = { success: vi.fn(), error: vi.fn() };
  let manage = true;

  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockClear());
    toast.success.mockClear();
    toast.error.mockClear();
    manage = true;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ form: 'studio.orders' }) } },
        },
        { provide: AutomationsApi, useValue: api },
        { provide: SMTModalService, useValue: modal },
        { provide: ToastService, useValue: toast },
        { provide: FormMetaService, useValue: { get: vi.fn(() => of(ORDER_META)) } },
        {
          provide: PermissionService,
          useValue: { hasPermission: (_form: string, action: string) => action === 'view' || manage },
        },
      ],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(AutomationsComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance, host: fixture.nativeElement as HTMLElement };
  }

  it('lists the automations of the form with what starts them, their author and last run', () => {
    const { host, component } = render();
    expect(api.list).toHaveBeenCalledWith('studio.orders');
    const cards = host.querySelectorAll('[data-testid="automation"]');
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('Уведомить менеджера');
    expect(cards[0].textContent).toContain('Заказы');
    expect(cards[0].textContent).toContain('Администратор');
    expect(component.trigger(AUTOMATION)).toContain('test.state.pending');
    expect(cards[1].querySelector('[data-testid="automation-run-now"]')).not.toBeNull();
    expect(cards[0].querySelector('[data-testid="automation-run-now"]')).toBeNull();
    expect(component.title('studio.unknown')).toBe('studio.unknown');
  });

  it('pauses, runs now and deletes from the revision read', () => {
    const { component } = render();
    component.toggle(AUTOMATION);
    expect(api.update).toHaveBeenCalledWith(5, 3, expect.objectContaining({ active: false, name: AUTOMATION.name }));
    component.runNow(SCHEDULED);
    expect(api.runNow).toHaveBeenCalledWith(6);
    expect(toast.success).toHaveBeenCalledWith(translateTest('studio.automations.run_now_queued', { n: 2 }));
    component.remove(AUTOMATION);
    expect(api.remove).toHaveBeenCalledWith(5, 3);
  });

  it('shows a refusal and offers nothing to change without the right to keep automations', () => {
    manage = false;
    api.runNow.mockReturnValueOnce(throwError(() => ({ status: 429, error: { detail: 'Слишком часто' } })));
    const { host, component } = render();
    expect(host.querySelector('[data-testid="automations-new"]')).toBeNull();
    expect(host.querySelector('[data-testid="automation-edit"]')).toBeNull();
    component.runNow(SCHEDULED);
    expect(toast.error).toHaveBeenCalled();
  });

  it('filters by form and opens the run log', () => {
    const { component, host } = render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    component.filter(null);
    expect(api.list).toHaveBeenLastCalledWith(null);
    expect(navigate).toHaveBeenCalled();
    (host.querySelector('[data-testid="automation-runs"]') as HTMLButtonElement).click();
    expect(component.history()).toEqual(AUTOMATION);
  });

  it('reports a list that does not load', () => {
    api.list.mockReturnValueOnce(throwError(() => ({ status: 500 })));
    const { host } = render();
    expect(host.querySelector('[data-testid="automations-error"]')?.textContent).toContain(
      translateTest('studio.automations.load_failed'),
    );
  });
});
