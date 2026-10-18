import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { StudioFormulasApi } from '../studio-formulas.api';
import { NewFormRulesComponent } from './new-form-rules.component';
import { MAX_RULES, type SimpleField, type SimpleFormRule } from './new-form.models';

const FIELDS: SimpleField[] = [
  {
    id: 'discount',
    type: 'number',
    labels: { ru: 'Скидка', uz: 'Chegirma', en: 'Discount' },
    required: false,
    listed: true,
  },
  {
    id: 'old',
    type: 'text',
    labels: { ru: 'Старое', uz: 'Eski', en: 'Old' },
    required: false,
    listed: true,
    hidden: true,
  },
];

describe('NewFormRulesComponent', () => {
  async function render(rules?: SimpleFormRule[]) {
    await TestBed.configureTestingModule({
      imports: [NewFormRulesComponent],
      providers: [{ provide: StudioFormulasApi, useValue: { check: () => of({ valid: true }) } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(NewFormRulesComponent);
    fixture.componentRef.setInput('rules', rules);
    fixture.componentRef.setInput('fields', FIELDS);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.changed, 'emit');
    const last = () => (emit.mock.lastCall?.[0] ?? []) as SimpleFormRule[];
    return { fixture, component: fixture.componentInstance, emit, last, root: fixture.nativeElement as HTMLElement };
  }

  it('adds a rule with a free key and edits its formula, message and field', async () => {
    const { fixture, component, emit, last, root } = await render();
    expect(root.querySelector('[data-testid="rule-add"]')).not.toBeNull();
    component.add();
    const added = emit.mock.lastCall?.[0] as SimpleFormRule[];
    expect(added).toEqual([{ key: 'rule1', formula: '', message: { ru: '', uz: '', en: '' } }]);
    fixture.componentRef.setInput('rules', added);
    fixture.detectChanges();
    expect(root.querySelector('[data-testid="rule-0"]')).not.toBeNull();
    expect(component.fieldOptions().map((option) => option.id)).toEqual(['discount']);
    component.patch(0, { formula: 'discount <= 30' });
    expect(last()[0]).toMatchObject({ formula: 'discount <= 30' });
    component.setMessage(0, 'ru', 'Скидка не больше 30%');
    expect(last()[0].message).toEqual({ ru: 'Скидка не больше 30%', uz: '', en: '' });
    component.setField(0, 'discount');
    expect(last()[0]).toMatchObject({ field: 'discount' });
    component.add();
    expect((emit.mock.lastCall?.[0] as SimpleFormRule[]).map((rule) => rule.key)).toEqual(['rule1', 'rule2']);
  });

  it('removes a rule, the last one leaving none, and ignores what does not exist', async () => {
    const rule: SimpleFormRule = {
      key: 'max',
      formula: 'discount <= 30',
      message: { ru: 'a', uz: 'b', en: 'c' },
      field: 'discount',
    };
    const { component, emit, last } = await render([rule]);
    component.setField(0, null);
    expect(last()[0]).not.toHaveProperty('field');
    component.remove(0);
    expect(emit).toHaveBeenLastCalledWith(undefined);
    component.patch(5, { key: 'x' });
    component.setMessage(5, 'en', 'x');
    component.setField(5, 'discount');
    expect(emit).toHaveBeenCalledTimes(2);
    expect(component.text(null)).toBe('');
  });

  it('offers no more than the server keeps', async () => {
    const many = Array.from({ length: MAX_RULES }, (_, index) => ({
      key: `r${index}`,
      formula: 'true',
      message: { ru: 'a', uz: 'b', en: 'c' },
    }));
    const { component, emit } = await render(many);
    component.add();
    expect(emit).not.toHaveBeenCalled();
  });
});
