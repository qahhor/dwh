import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTOMATION_CHECK_DELAY_MS, AutomationFormulaComponent } from './automation-formula.component';
import { AutomationsApi } from './automations.api';

/* A formula of an automation (ADR-0046): checked by the server while it is typed, names and functions inserted. */
describe('AutomationFormulaComponent', () => {
  const api = {
    check: vi.fn(() =>
      of({ valid: false, reason: 'unknown_field', position: 3, name: 'x', messageKey: 'error.formula.unknown_field' }),
    ),
  };

  beforeEach(() => {
    vi.useFakeTimers();
    api.check.mockClear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AutomationsApi, useValue: api }],
    });
  });

  afterEach(() => vi.useRealTimers());

  function render(value: string | undefined, targetField: string | null = null) {
    const fixture = TestBed.createComponent(AutomationFormulaComponent);
    fixture.componentRef.setInput('label', 'Условие');
    fixture.componentRef.setInput('form', 'studio.orders');
    fixture.componentRef.setInput('trigger', 'saved');
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('targetField', targetField);
    fixture.componentRef.setInput('targetForm', targetField ? 'studio.tasks' : null);
    fixture.componentRef.setInput('names', [{ id: 'discount', label: 'discount' }]);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('asks the server after a pause and shows its problem at the position', () => {
    const { fixture, component } = render('discount > x', 'ordernum');
    expect(api.check).not.toHaveBeenCalled();
    vi.advanceTimersByTime(AUTOMATION_CHECK_DELAY_MS);
    expect(api.check).toHaveBeenCalledWith('studio.orders', 'saved', 'discount > x', {
      form: 'studio.tasks',
      field: 'ordernum',
    });
    fixture.detectChanges();
    expect(component.problem()).toContain('3');
  });

  it('shows the message of the last save while the text has no check of its own, and inserts names', () => {
    const { fixture, component } = render(undefined);
    fixture.componentRef.setInput('error', 'Ошибка сохранения');
    fixture.detectChanges();
    expect(component.problem()).toBe('Ошибка сохранения');
    const emitted: (string | undefined)[] = [];
    component.valueChange.subscribe((text) => emitted.push(text));
    component.insert('discount', false);
    component.insert('round', true);
    component.insert(null, false);
    component.edit('   ');
    expect(emitted).toEqual(['discount', 'round(', undefined]);
  });

  it('drops a check the server could not answer', () => {
    api.check.mockReturnValueOnce(throwError(() => ({ status: 500 })));
    const { component } = render('true');
    vi.advanceTimersByTime(AUTOMATION_CHECK_DELAY_MS);
    expect(component.checked()).toBeNull();
  });
});
