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
  it('keeps the key, type and default of a stored field in a new version and hides instead of removing', async () => {
    const { fixture, component, emit } = await render();
    fixture.componentRef.setInput('versioning', true);
    fixture.componentRef.setInput('stored', component.field());
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('input')?.disabled).toBe(true);
    expect(root.querySelector('[data-testid="version-hide-note"]')).not.toBeNull();
    expect(component.minMaxLength()).toBe(255);
    expect(component.requiredLocked()).toBe(true);
    component.changeType('number');
    component.setDefault('x');
    expect(emit).not.toHaveBeenCalled();
    component.setHidden(true);
    expect(emit.mock.lastCall?.[0]).toMatchObject({ hidden: true });
    fixture.componentRef.setInput('field', { ...component.field(), hidden: true });
    component.setHidden(false);
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('hidden');
  });
  it('sets and clears the default of a field added in a new version', async () => {
    const { fixture, component, emit } = await render();
    fixture.componentRef.setInput('versioning', true);
    fixture.detectChanges();
    component.setDefault('Новое');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ defaultValue: 'Новое' });
    component.setDefault('');
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('defaultValue');
    component.setDefault(null);
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('defaultValue');
  });
  it('does not convert an empty required numeric limit into a valid zero', async () => {
    const { component } = await render();
    expect(component.number(null)).toBeUndefined();
    expect(component.number(0)).toBe(0);
  });
});
