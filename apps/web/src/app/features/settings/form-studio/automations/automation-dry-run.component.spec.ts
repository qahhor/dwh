import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { AutomationDryRunComponent } from './automation-dry-run.component';
import { AutomationsApi, type AutomationDryRun, type AutomationRequest } from './automations.api';

const REQUEST: AutomationRequest = {
  name: 'Проба',
  trigger: { kind: 'saved' },
  actions: [{ type: 'set_field', values: [{ field: 'review', formula: 'true' }] }],
};

const RESULT: AutomationDryRun = {
  conditionHolds: true,
  actions: [
    { index: 0, type: 'set_field', status: 'would_apply', values: { review: true } },
    {
      index: 1,
      type: 'notify',
      status: 'would_apply',
      recipients: [
        { id: 2, name: 'Менеджер', channels: ['in_app'] },
        { id: 3, name: 'Чужой', channels: [], skipped: 'no_access' },
      ],
      message: { title: 'Заказ', body: 'Текст' },
    },
  ],
};

/* The dry run of an automation (ADR-0046): over a chosen record, nothing applied, the editor learns the problems. */
describe('AutomationDryRunComponent', () => {
  const api = {
    records: vi.fn(() => ({ page: vi.fn(), key: vi.fn(), option: vi.fn() })),
    dryRun: vi.fn(() => of(RESULT)),
  };

  beforeEach(() => {
    api.dryRun.mockClear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AutomationsApi, useValue: api }],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(AutomationDryRunComponent);
    fixture.componentRef.setInput('form', 'studio.orders');
    fixture.componentRef.setInput('request', REQUEST);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('runs the automation over the chosen record and words what it would do', () => {
    const { fixture, component } = render();
    const problems: Record<string, string>[] = [];
    component.problems.subscribe((found) => problems.push(found));
    component.run();
    expect(api.dryRun).not.toHaveBeenCalled();
    component.record.set(42);
    component.run();
    expect(api.dryRun).toHaveBeenCalledWith(42, { ...REQUEST, form: 'studio.orders' });
    fixture.detectChanges();
    expect(component.result()).toEqual(RESULT);
    expect(problems).toEqual([{}]);
    expect(component.valuesText({ review: true })).toBe('review = true');
    expect(component.peopleText(RESULT.actions[1])).toContain(translateTest('studio.automations.skipped.no_access'));
    expect(component.problem({ key: 'error.studio.automation_field', params: { name: 'x' }, fields: [] })).toContain(
      'x',
    );
  });

  it('hands the problems of a refused automation back to the editor', () => {
    api.dryRun.mockReturnValueOnce(
      throwError(() => ({
        status: 422,
        errors: [{ field: 'name', messageKey: 'error.studio.automation_name', message: 'Название' }],
      })),
    );
    const { component } = render();
    const problems: Record<string, string>[] = [];
    component.problems.subscribe((found) => problems.push(found));
    component.record.set(42);
    component.run();
    expect(component.alert()).toBeTruthy();
    expect(component.busy()).toBe(false);
    expect(problems.length).toBe(1);
  });
});
