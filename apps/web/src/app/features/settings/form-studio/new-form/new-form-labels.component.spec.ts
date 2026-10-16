import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { NewFormLabelsComponent } from './new-form-labels.component';

describe('NewFormLabelsComponent', () => {
  async function render(disabled = false) {
    await TestBed.configureTestingModule({ imports: [NewFormLabelsComponent] }).compileComponents();
    const fixture = TestBed.createComponent(NewFormLabelsComponent);
    fixture.componentRef.setInput('labels', { ru: 'Название', uz: 'Nomi', en: 'Name' });
    fixture.componentRef.setInput('disabled', disabled);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return {
      fixture,
      root: fixture.nativeElement as HTMLElement,
      emit: vi.spyOn(fixture.componentInstance.changed, 'emit'),
    };
  }
  it('provides three named controls linked to their labels and emits only the edited language', async () => {
    const { fixture, root, emit } = await render();
    const inputs = Array.from(root.querySelectorAll('input'));
    expect(inputs.map((input) => input.value)).toEqual(['Название', 'Nomi', 'Name']);
    for (const input of inputs) {
      expect(root.querySelector(`label[for="${input.id}"]`)?.textContent?.trim()).not.toBe('');
      expect(input.maxLength).toBe(160);
    }
    inputs[2].focus();
    expect(document.activeElement).toBe(inputs[2]);
    inputs[2].value = 'Title';
    inputs[2].dispatchEvent(new Event('input', { bubbles: true }));
    expect(emit).toHaveBeenCalledWith({ language: 'en', value: 'Title' });
    expect(fixture.componentInstance.labels()).toEqual({ ru: 'Название', uz: 'Nomi', en: 'Name' });
  });
  it('keeps an imported 80-code-point label editable when every character uses a UTF-16 surrogate pair', async () => {
    const { fixture, root, emit } = await render();
    const original = '😀'.repeat(80);
    const labels = { ru: original, uz: 'Nomi', en: 'Name' };
    fixture.componentRef.setInput('labels', labels);
    fixture.detectChanges();
    await fixture.whenStable();
    const first = root.querySelector('input')!;
    expect(first.value).toBe(original);
    expect([...original]).toHaveLength(80);
    expect(first.maxLength).toBeGreaterThanOrEqual(original.length);
    const edited = '😀'.repeat(79) + '🚀';
    first.value = edited;
    first.dispatchEvent(new Event('input', { bubbles: true }));
    expect(emit).toHaveBeenCalledWith({ language: 'ru', value: edited });
    expect([...edited]).toHaveLength(80);
    expect(labels.ru).toBe(original);
  });
  it('preserves an empty label for server validation instead of silently inheriting a title', async () => {
    const { root, emit } = await render();
    const first = root.querySelector('input')!;
    first.value = '';
    first.dispatchEvent(new Event('input', { bubbles: true }));
    expect(emit).toHaveBeenCalledWith({ language: 'ru', value: '' });
  });
  it('disables all three controls while preparation is in progress', async () => {
    const { root } = await render(true);
    expect(Array.from(root.querySelectorAll('input')).every((input) => input.disabled)).toBe(true);
  });
});
