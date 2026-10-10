import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { OrgUnitEditorComponent } from './org-unit-editor.component';
import { OrgUnit, OrgUnitCreate } from './org-units.models';

const root: OrgUnit = {
  id: 1,
  parentId: null,
  code: 'ROOT',
  name: 'Root',
  kind: 'company',
  state: 'A',
  orderNo: 0,
  createdAt: '',
  modifiedAt: '',
};
const child: OrgUnit = { ...root, id: 2, parentId: 1, code: 'CHILD', name: 'Child', kind: 'custom-kind' };
describe('OrgUnitEditorComponent', () => {
  async function setup(initial: OrgUnit | OrgUnitCreate = child, units = [root, child]) {
    const fixture = TestBed.createComponent(OrgUnitEditorComponent);
    fixture.componentRef.setInput('initial', initial);
    fixture.componentRef.setInput('units', units);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const save = vi.fn();
    fixture.componentInstance.save.subscribe(save);
    const editor = fixture.componentInstance;
    const field = (name: string) =>
      fixture.nativeElement.querySelector(`#${editor.formId}-${name}`) as HTMLInputElement | HTMLButtonElement;
    const errors = () =>
      Array.from(fixture.nativeElement.querySelectorAll('.smt-control__error')).map(
        (node) => (node as HTMLElement).textContent?.trim() ?? '',
      );
    const submit = async () => {
      (fixture.nativeElement.querySelector('[data-testid="form-submit"]') as HTMLButtonElement).click();
      await settle();
      await settle();
    };
    return { fixture, editor, save, field, errors, submit, settle };
  }
  it('keeps unknown kinds and immutable code; sends name-only sparse PATCH through native form', async () => {
    const { fixture, editor, save, field } = await setup();
    expect(editor.draft().kind).toBe('custom-kind');
    editor.draft.update((d) => ({ ...d, name: ' Renamed ' }));
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    expect(save).toHaveBeenCalledWith({ mode: 'edit', id: 2, patch: { name: 'Renamed' } });
    expect((field('code') as HTMLInputElement).readOnly).toBe(true);
  });
  it('does not submit pristine, blank or pending forms, and says what a blank name needs', async () => {
    const { editor, save, fixture, errors, settle, field } = await setup();
    editor.submit();
    editor.draft.update((d) => ({ ...d, name: ' ' }));
    editor.submit();
    await settle();
    expect(errors()).toEqual(['Укажите название']);
    editor.draft.update((d) => ({ ...d, name: 'New' }));
    fixture.componentRef.setInput('pending', true);
    await settle();
    editor.submit();
    expect(save).not.toHaveBeenCalled();
    expect((field('name') as HTMLInputElement).disabled).toBe(true);
  });
  it('treats surrounding name whitespace as pristine and keeps Save disabled while nothing changed', async () => {
    const { editor, save, fixture, settle } = await setup();
    editor.draft.update((d) => ({ ...d, name: '  Child  ' }));
    await settle();
    expect(editor.dirty).toBe(false);
    expect((fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(true);
    editor.submit();
    expect(save).not.toHaveBeenCalled();
  });
  it('keeps Save enabled for a new unit and explains the empty required fields', async () => {
    const { save, errors, submit, field, fixture } = await setup({
      parentId: 1,
      code: '',
      name: '',
      kind: 'company',
      orderNo: 0,
    });
    const button = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    expect((field('code') as HTMLInputElement).getAttribute('aria-required')).toBe('true');

    await submit();

    expect(errors()).toEqual(['Укажите код', 'Укажите название']);
    expect(document.activeElement).toBe(field('code'));
    expect(save).not.toHaveBeenCalled();
  });
  it('excludes malformed parents and requires impact confirmation for state/move changes', async () => {
    const { editor, save } = await setup(child, [root, child, { ...root, id: 3, parentId: 90 }]);
    expect(editor.parents.map((u) => u.id)).toEqual([1]);
    editor.draft.update((d) => ({ ...d, state: 'P' }));
    editor.submit();
    expect(save).not.toHaveBeenCalled();
    expect(editor.impactOpen()).toBe(true);
    editor.confirmImpact();
    expect(save).toHaveBeenCalledWith({ mode: 'edit', id: 2, patch: { state: 'P' } });
  });
  it('locks root parent and associates field errors without losing the draft', async () => {
    const { editor, fixture, field, settle } = await setup(root);
    editor.draft.update((d) => ({ ...d, name: 'Preserved' }));
    fixture.componentRef.setInput('error', {
      status: 409,
      title: 'Conflict',
      code: 'CONFLICT',
      detail: 'Try again',
      invalid_params: [{ name: 'name', reason: 'Field conflict' }],
    });
    await settle();
    await settle();
    expect(editor.draft().name).toBe('Preserved');
    const input = field('name') as HTMLInputElement;
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const errorId = (input.getAttribute('aria-describedby') ?? '').split(' ').at(-1)!;
    expect(fixture.nativeElement.querySelector('#' + errorId).textContent).toContain('Field conflict');
    expect(document.activeElement).toBe(input);
    expect(field('parent')).toBeNull();
  });
  it('shows a refusal of no field above the fields', async () => {
    const { fixture, settle } = await setup();
    fixture.componentRef.setInput('error', { status: 500, title: 'Error', code: 'X', detail: 'Сервер недоступен' });
    await settle();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Сервер недоступен');
  });
  it('preserves an unknown kind even when its name matches an object prototype property', async () => {
    const { editor, field } = await setup({ ...child, kind: 'constructor' });
    expect(editor.kindOptions()[0]).toEqual({ id: 'constructor', label: 'constructor' });
    expect(field('kind').textContent).toContain('constructor');
    expect(editor.draft().kind).toBe('constructor');
  });
});
