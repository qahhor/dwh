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
  it('offers select, reference and user only to a live form and seeds their parts', async () => {
    const { fixture, component, emit } = await render();
    expect(component.options().map((option) => option.id)).not.toContain('select');
    fixture.componentRef.setInput('live', true);
    fixture.componentRef.setInput('lists', [{ code: 'kinds', labels: { ru: 'Типы', uz: 'Turlar', en: 'Kinds' } }]);
    fixture.componentRef.setInput('forms', [
      { code: 'studio.clients', labels: { ru: 'Клиенты', uz: 'Mijozlar', en: 'Clients' } },
    ]);
    fixture.detectChanges();
    expect(component.options().map((option) => option.id)).toEqual(expect.arrayContaining(['select', 'ref', 'user']));
    component.changeType('select');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ type: 'select', options: [{ code: 'option1' }] });
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('maxLength');
    component.changeType('ref');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ type: 'ref', target: { kind: 'list', code: '' } });
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('options');
    component.changeType('user');
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('target');

    fixture.componentRef.setInput('field', { ...component.field(), type: 'ref', target: { kind: 'list', code: '' } });
    fixture.detectChanges();
    expect(component.targetOptions()).toEqual([{ id: 'kinds', label: 'Типы (kinds)' }]);
    component.setTargetCode('kinds');
    expect(emit.mock.lastCall?.[0].target).toEqual({ kind: 'list', code: 'kinds' });
    component.setTargetKind('form');
    expect(emit.mock.lastCall?.[0].target).toEqual({ kind: 'form', code: '' });
    component.setTargetKind('table');
    fixture.componentRef.setInput('field', {
      ...component.field(),
      target: { kind: 'form', code: 'studio.gone' },
    });
    expect(component.targetOptions().map((option) => option.id)).toEqual(['studio.gone', 'studio.clients']);
  });
  it('edits the choices of a select and keeps every choice a stored version may hold', async () => {
    const { fixture, component, emit } = await render();
    const option = (code: string) => ({ code, labels: { ru: code, uz: code, en: code } });
    const select = { ...component.field(), type: 'select' as const, options: [option('done'), option('later')] };
    delete (select as { maxLength?: number }).maxLength;
    fixture.componentRef.setInput('field', select);
    fixture.componentRef.setInput('live', true);
    fixture.componentRef.setInput('versioning', true);
    fixture.detectChanges();
    component.addOption();
    expect(emit.mock.lastCall![0].options!.map((one: { code: string }) => one.code)).toEqual([
      'done',
      'later',
      'option3',
    ]);
    component.setOptionCode(1, 'soon');
    expect(emit.mock.lastCall![0].options![1].code).toBe('soon');
    component.setOptionLabel(0, 'en', 'Done');
    expect(emit.mock.lastCall![0].options![0].labels.en).toBe('Done');
    component.removeOption(1);
    expect(emit.mock.lastCall![0].options!).toHaveLength(1);
    expect(component.defaultOptions().map((one) => one.id)).toEqual(['done', 'later']);

    fixture.componentRef.setInput('stored', select);
    fixture.detectChanges();
    const calls = emit.mock.calls.length;
    component.setOptionCode(0, 'other');
    component.removeOption(0);
    expect(emit).toHaveBeenCalledTimes(calls);
    expect(component.optionLocked(option('done'))).toBe(true);
    component.setTargetCode('x');
    component.setTargetKind('list');
    expect(emit).toHaveBeenCalledTimes(calls);
  });
});
