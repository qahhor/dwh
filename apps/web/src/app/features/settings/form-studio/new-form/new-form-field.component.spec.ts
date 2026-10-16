import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { NewFormFieldComponent } from './new-form-field.component';
describe('NewFormFieldComponent', () => {
  async function render() {
    await TestBed.configureTestingModule({ imports: [NewFormFieldComponent] }).compileComponents();
    const fixture = TestBed.createComponent(NewFormFieldComponent);
    fixture.componentRef.setInput('field', {
      id: 'note',
      type: 'text',
      labels: { ru: 'Текст', uz: 'Matn', en: 'Text' },
      required: false,
      listed: true,
      maxLength: 255,
    });
    fixture.componentRef.setInput('index', 0);
    fixture.componentRef.setInput('count', 1);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.changed, 'emit');
    return { fixture, component: fixture.componentInstance, emit };
  }
  it('resets incompatible limits when a field type changes and ignores unknown types', async () => {
    const { component, emit } = await render();
    component.changeType('number');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ type: 'number', scale: 2 });
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('maxLength');
    component.changeType('bool');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ type: 'bool' });
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('maxLength');
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('scale');
    component.changeType('textarea');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ type: 'textarea', maxLength: 1000 });
    component.changeType('sql');
    component.changeType(null);
    expect(emit).toHaveBeenCalledTimes(3);
  });
  it('edits a translated label without changing its siblings or the source', async () => {
    const { component, emit } = await render();
    component.setLabel('en', 'Comment');
    expect(emit.mock.lastCall?.[0].labels).toEqual({ ru: 'Текст', uz: 'Matn', en: 'Comment' });
    expect(component.field().labels.en).toBe('Text');
  });
  it('does not convert an empty required numeric limit into a valid zero', async () => {
    const { component } = await render();
    expect(component.number(null)).toBeUndefined();
    expect(component.number(0)).toBe(0);
  });
});
