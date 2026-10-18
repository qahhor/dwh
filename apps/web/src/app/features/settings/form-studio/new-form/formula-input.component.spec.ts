import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StudioFormulasApi, type FormulaCheck } from '../studio-formulas.api';
import { FORMULA_CHECK_DELAY_MS, FormulaInputComponent } from './formula-input.component';
import type { SimpleField } from './new-form.models';

const FIELDS: SimpleField[] = [
  {
    id: 'qty',
    type: 'number',
    labels: { ru: 'Количество', uz: 'Miqdor', en: 'Quantity' },
    required: true,
    listed: true,
  },
  { id: 'reason', type: 'text', labels: { ru: 'Причина', uz: 'Sabab', en: 'Reason' }, required: false, listed: true },
  {
    id: 'old',
    type: 'text',
    labels: { ru: 'Старое', uz: 'Eski', en: 'Old' },
    required: false,
    listed: true,
    hidden: true,
  },
  {
    id: 'kind',
    type: 'ref',
    labels: { ru: 'Тип', uz: 'Tur', en: 'Kind' },
    required: false,
    listed: true,
    target: { kind: 'list', code: 'kinds' },
  },
];

describe('FormulaInputComponent', () => {
  afterEach(() => vi.useRealTimers());

  async function render(answer: () => ReturnType<StudioFormulasApi['check']>) {
    const check = vi.fn(answer);
    await TestBed.configureTestingModule({
      imports: [FormulaInputComponent],
      providers: [{ provide: StudioFormulasApi, useValue: { check } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(FormulaInputComponent);
    fixture.componentRef.setInput('label', 'Показывать, если');
    fixture.componentRef.setInput('kind', 'visibleIf');
    fixture.componentRef.setInput('field', 'reason');
    fixture.componentRef.setInput('fields', FIELDS);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.valueChange, 'emit');
    return { fixture, component: fixture.componentInstance, emit, check };
  }

  it('offers the shown fields but the one a condition shows, and every function', async () => {
    const { component } = await render(() => of({ valid: true }));
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['qty', 'kind']);
    expect(component.functionOptions.map((option) => option.id)).toContain('round');
  });

  it('inserts a field or a function at the end and emits the text, an empty text as none', async () => {
    const { fixture, component, emit } = await render(() => of({ valid: true }));
    component.insert('qty', false);
    expect(emit).toHaveBeenLastCalledWith('qty');
    fixture.componentRef.setInput('value', 'qty >');
    component.insert('round', true);
    expect(emit).toHaveBeenLastCalledWith('qty > round(');
    fixture.componentRef.setInput('value', 'max(');
    component.insert('qty', false);
    expect(emit).toHaveBeenLastCalledWith('max(qty');
    component.insert(null, false);
    expect(emit).toHaveBeenCalledTimes(3);
    component.edit('   ');
    expect(emit).toHaveBeenLastCalledWith(undefined);
    component.edit(null);
    expect(emit).toHaveBeenLastCalledWith(undefined);
  });

  it('asks the server after the typing stops and shows the problem with its position', async () => {
    vi.useFakeTimers();
    const problem: FormulaCheck = {
      valid: false,
      reason: 'unknown_field',
      position: 5,
      name: 'foo',
      messageKey: 'error.formula.unknown_field',
    };
    const { fixture, component, check } = await render(() => of(problem));
    fixture.componentRef.setInput('value', 'qty + foo');
    fixture.detectChanges();
    expect(check).not.toHaveBeenCalled();
    vi.advanceTimersByTime(FORMULA_CHECK_DELAY_MS);
    expect(check).toHaveBeenCalledWith(
      [
        { id: 'qty', type: 'number' },
        { id: 'reason', type: 'text' },
        { id: 'kind', type: 'ref', target: 'list' },
      ],
      'qty + foo',
      'visibleIf',
      'reason',
    );
    expect(component.problem()).not.toBe('');
    fixture.componentRef.setInput('value', '');
    fixture.detectChanges();
    expect(component.checked()).toBeNull();
    expect(component.problem()).toBe('');
  });

  it('shows no problem for a valid formula or when the check fails', async () => {
    vi.useFakeTimers();
    const { fixture, component } = await render(() => throwError(() => new Error('offline')));
    fixture.componentRef.setInput('value', 'qty > 1');
    fixture.detectChanges();
    vi.advanceTimersByTime(FORMULA_CHECK_DELAY_MS);
    expect(component.checked()).toBeNull();
    component.checked.set({ valid: true, type: 'bool' });
    expect(component.problem()).toBe('');
  });
});
