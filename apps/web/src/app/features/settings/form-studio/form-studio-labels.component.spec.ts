import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { FormStudioLabelsComponent } from './form-studio-labels.component';

describe('FormStudioLabelsComponent', () => {
  it('names all language inputs and emits the edited locale without changing the other labels', async () => {
    const fixture = TestBed.createComponent(FormStudioLabelsComponent);
    const labels = { ru: 'Название', uz: 'Nomi', en: 'Name' };
    fixture.componentRef.setInput('labels', labels);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const inputs = [...root.querySelectorAll('input')];
    expect(inputs.map((input) => input.value)).toEqual(['Название', 'Nomi', 'Name']);
    expect([...root.querySelectorAll('label')].map((label) => label.textContent?.trim())).toEqual([
      'Русский',
      'Узбекский',
      'Английский',
    ]);
    expect(inputs.every((input) => input.maxLength === 240)).toBe(true);
    inputs[1].value = 'Mahsulot';
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }));
    expect(changed).toHaveBeenCalledWith({ language: 'uz', value: 'Mahsulot' });
    expect(labels).toEqual({ ru: 'Название', uz: 'Nomi', en: 'Name' });
  });

  it('keeps an imported 120-code-point label editable when every character uses a UTF-16 surrogate pair', async () => {
    const fixture = TestBed.createComponent(FormStudioLabelsComponent);
    const original = '😀'.repeat(120);
    const labels = { ru: original, uz: 'Nomi', en: 'Name' };
    fixture.componentRef.setInput('labels', labels);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    await fixture.whenStable();
    const input = (fixture.nativeElement as HTMLElement).querySelector('input')!;
    expect(input.value).toBe(original);
    expect([...original]).toHaveLength(120);
    expect(input.maxLength).toBeGreaterThanOrEqual(original.length);
    const edited = '😀'.repeat(119) + '🚀';
    input.value = edited;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    expect(changed).toHaveBeenCalledWith({ language: 'ru', value: edited });
    expect([...edited]).toHaveLength(120);
    expect(labels.ru).toBe(original);
  });

  it('disables all inputs while a declaration is being checked', async () => {
    const fixture = TestBed.createComponent(FormStudioLabelsComponent);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    await fixture.whenStable();
    const inputs = [...(fixture.nativeElement as HTMLElement).querySelectorAll('input')];
    expect(inputs).toHaveLength(3);
    expect(inputs.every((input) => input.disabled)).toBe(true);
  });
});
