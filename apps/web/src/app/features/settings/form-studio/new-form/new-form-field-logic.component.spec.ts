import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { StudioFormulasApi } from '../studio-formulas.api';
import { NewFormFieldLogicComponent } from './new-form-field-logic.component';
import type { SimpleField } from './new-form.models';

const TOTAL: SimpleField = {
  id: 'total',
  type: 'number',
  labels: { ru: 'Сумма', uz: 'Summa', en: 'Total' },
  required: true,
  listed: true,
  scale: 2,
  defaultValue: '0',
  visibleIf: 'qty > 0',
};

describe('NewFormFieldLogicComponent', () => {
  async function render(field: SimpleField, stored: SimpleField | null = null) {
    await TestBed.configureTestingModule({
      imports: [NewFormFieldLogicComponent],
      providers: [{ provide: StudioFormulasApi, useValue: { check: () => of({ valid: true }) } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(NewFormFieldLogicComponent);
    fixture.componentRef.setInput('field', field);
    fixture.componentRef.setInput('index', 2);
    fixture.componentRef.setInput('fields', [field]);
    fixture.componentRef.setInput('stored', stored);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.changed, 'emit');
    return { fixture, component: fixture.componentInstance, emit, root: fixture.nativeElement as HTMLElement };
  }

  it('makes a field computed: optional, without a default or conditions, then entered again', async () => {
    const { fixture, component, emit } = await render(TOTAL);
    expect(component.computable()).toBe(true);
    component.setMode('computed');
    const computed = emit.mock.lastCall?.[0] as SimpleField;
    expect(computed).toMatchObject({ formula: '', required: false });
    expect(computed).not.toHaveProperty('defaultValue');
    expect(computed).not.toHaveProperty('visibleIf');
    fixture.componentRef.setInput('field', { ...computed, formula: 'qty * 2' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="field-formula-2"]')).not.toBeNull();
    component.set('formula', undefined);
    expect(emit.mock.lastCall?.[0]).toMatchObject({ formula: '' });
    component.setMode('entered');
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('formula');
    component.setMode('other');
    expect(emit).toHaveBeenCalledTimes(3);
  });

  it('sets and clears the conditions of an entered field and offers required-if only to an optional one', async () => {
    const { fixture, component, emit, root } = await render({ ...TOTAL, required: false, defaultValue: undefined });
    expect(root.querySelector('[data-testid="field-required-2"]')).not.toBeNull();
    component.set('requiredIf', 'qty > 5');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ requiredIf: 'qty > 5' });
    component.set('visibleIf', undefined);
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('visibleIf');
    fixture.componentRef.setInput('field', { ...TOTAL });
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="field-required-2"]')).toBeNull();
  });

  it('never makes a stored entered field computed and offers no formula to a type that has none', async () => {
    const { component, emit } = await render(TOTAL, TOTAL);
    expect(component.computedLocked()).toBe(true);
    component.setMode('computed');
    expect(emit).not.toHaveBeenCalled();
    TestBed.resetTestingModule();
    const user = await render({ ...TOTAL, type: 'user', scale: undefined });
    expect(user.component.computable()).toBe(false);
    expect(user.component.defaultable()).toBe(false);
  });
});
