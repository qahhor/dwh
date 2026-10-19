import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FormMetaService } from '@core/services/form-meta.service';
import { ToastService } from '@core/services/toast.service';
import { AUTOMATION, ORDER_META, SCHEDULED } from '@testing/automations';
import { translateTest } from '@testing/i18n-test.stub';
import { AutomationEditorComponent } from './automation-editor.component';
import { AutomationsApi } from './automations.api';

/* The editor of an automation (ADR-0046): a new one for a form, a stored one changed at its revision, the problems. */
describe('AutomationEditorComponent', () => {
  const api = {
    options: vi.fn(() =>
      of({ channels: ['in_app'], maxPerForm: 20, maxActions: 10, maxDepth: 3, maxRunsPerRecordHour: 20 }),
    ),
    forms: vi.fn(() =>
      of({ items: [{ entityCode: 'studio.orders', title: { ru: 'Заказы', uz: 'Buyurtmalar', en: 'Orders' } }] }),
    ),
    get: vi.fn(() => of(AUTOMATION)),
    create: vi.fn(() => of(AUTOMATION)),
    update: vi.fn(() => of(AUTOMATION)),
    records: vi.fn(() => ({ page: vi.fn(), key: vi.fn(), option: vi.fn() })),
    dryRun: vi.fn(),
    check: vi.fn(() => of({ valid: true, type: 'bool' })),
  };
  const toast = { success: vi.fn(), error: vi.fn() };
  let id = 'new';

  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockClear());
    id = 'new';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              get paramMap() {
                return convertToParamMap({ id });
              },
              queryParamMap: convertToParamMap({ form: 'studio.orders' }),
            },
          },
        },
        { provide: AutomationsApi, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: FormMetaService, useValue: { get: vi.fn(() => of(ORDER_META)) } },
      ],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(AutomationEditorComponent);
    fixture.detectChanges();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    return { fixture, component: fixture.componentInstance, navigate };
  }

  it('starts a new automation of the form with a notification and creates it', () => {
    const { component, navigate } = render();
    expect(component.creating()).toBe(true);
    expect(component.form()).toBe('studio.orders');
    expect(component.meta()).toEqual(ORDER_META);
    expect(component.actions()[0].type).toBe('notify');
    expect(component.stateOptions().map((option) => option.id)).toEqual(['draft', 'pending', 'approved']);
    component.name.set(' Скидки ');
    component.kind.set('saved');
    component.fields.set(['discount']);
    component.condition.set('discount > 20');
    component.newAction.set('set_field');
    component.addAction();
    component.changeAction(1, { type: 'set_field', values: [{ field: 'review', formula: 'true' }] });
    component.moveAction(1, -1);
    component.moveAction(0, -1);
    component.removeAction(1);
    expect(component.conditionNames().map((option) => option.id)).toContain('changed.discount');
    expect(component.canLeaveRecordPage()).not.toBe(true);
    component.submit();
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        form: 'studio.orders',
        name: 'Скидки',
        condition: 'discount > 20',
        trigger: { kind: 'saved', fields: ['discount'] },
        actions: [{ type: 'set_field', values: [{ field: 'review', formula: 'true' }] }],
      }),
    );
    expect(navigate).toHaveBeenCalledWith(['/settings/form-studio/automations'], {
      queryParams: { form: 'studio.orders' },
    });
    expect(component.canLeaveRecordPage()).toBe(true);
  });

  it('builds the schedule and the transition of a trigger', () => {
    const { component } = render();
    component.kind.set('scheduled');
    component.frequency.set('weekly');
    component.weekday.set(3);
    expect(component.request().trigger).toEqual({
      kind: 'scheduled',
      schedule: { frequency: 'weekly', time: '09:00', weekday: 3 },
    });
    component.frequency.set('monthly');
    component.monthDay.set('31');
    expect(component.request().trigger.schedule).toEqual({ frequency: 'monthly', time: '09:00', monthDay: 31 });
    component.kind.set('transition');
    component.from.set(['draft']);
    component.to.set(['pending']);
    expect(component.request().trigger).toEqual({ kind: 'transition', from: ['draft'], to: ['pending'] });
  });

  it('changes a stored automation at its revision and shows the problems at their parts', () => {
    id = '5';
    api.update.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        detail: 'Автоматизация заполнена с ошибками',
        errors: [{ field: 'name', messageKey: 'error.studio.automation_name', message: 'Название' }],
      })),
    );
    const { component } = render();
    expect(api.get).toHaveBeenCalledWith(5);
    expect(component.creating()).toBe(false);
    expect(component.name()).toBe(AUTOMATION.name);
    expect(component.to()).toEqual(['pending']);
    component.submit();
    expect(api.update).toHaveBeenCalledWith(5, 3, expect.objectContaining({ name: AUTOMATION.name }));
    expect(component.error('name')).toBeTruthy();
    expect(component.alert()).toContain('Автоматизация');
  });

  it('says a stale automation must be opened again and loads a scheduled one', () => {
    id = '6';
    api.get.mockReturnValueOnce(of(SCHEDULED));
    api.update.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    const { component } = render();
    expect(component.kind()).toBe('scheduled');
    expect(component.time()).toBe('09:00');
    component.submit();
    expect(component.alert()).toBe(translateTest('studio.automations.conflict'));
    component.cancel();
  });

  it('reports an automation that does not load', () => {
    id = '7';
    api.get.mockReturnValueOnce(throwError(() => ({ status: 404 })));
    const { component } = render();
    expect(component.loadError()).toBe(translateTest('studio.automations.load_failed'));
  });
});
