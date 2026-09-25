// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormField, form, maxLength, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTControlComponent } from '../control/control.component';
import { SMTTextareaComponent } from './textarea.component';

@Component({
  standalone: true,
  imports: [SMTTextareaComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Comment" smtHint="What happened">
      <smt-textarea [formField]="note.text" placeholder="Write…" />
    </smt-control>
  `,
})
class FormHost {
  readonly model = signal({ text: 'First line' });
  readonly note = form(this.model, path => {
    required(path.text);
    maxLength(path.text, 20);
  });
}

@Component({
  standalone: true,
  imports: [SMTTextareaComponent],
  template: `<smt-textarea [(value)]="text" [autoResize]="false" [rows]="5" [disabled]="off()" readonly />`,
})
class PlainHost {
  text = 'plain';
  readonly off = signal(false);
}

describe('SMTTextareaComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render<T>(host: new () => T) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const field = element.querySelector('textarea') as HTMLTextAreaElement;
    return { fixture, element, field, settle };
  }

  it('is named by the smt-control label and described by its hint and the length counter', async () => {
    const { element, field } = await render(FormHost);
    const label = element.querySelector('label') as HTMLLabelElement;
    expect(label.htmlFor).toBe(field.id);
    const describedBy = field.getAttribute('aria-describedby')!.split(' ');
    expect(describedBy).toContain(element.querySelector('.smt-control__hint')!.id);
    const counter = element.querySelector('.smt-textarea__counter') as HTMLElement;
    expect(describedBy).toContain(counter.id);
    expect(counter.textContent).toBe('10 of 20 characters');
    expect(field.getAttribute('maxlength')).toBe('20');
    expect(field.getAttribute('aria-required')).toBe('true');
  });

  it('writes what is typed into the form and marks the last tenth of the limit', async () => {
    const { fixture, element, field, settle } = await render(FormHost);
    field.value = 'Nineteen characters';
    field.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.model().text).toBe('Nineteen characters');
    const counter = element.querySelector('.smt-textarea__counter') as HTMLElement;
    expect(counter.textContent).toBe('19 of 20 characters');
    expect(counter.classList).toContain('smt-textarea__counter--near');
  });

  it('shows the required error only after the field is left', async () => {
    const { fixture, element, field, settle } = await render(FormHost);
    field.value = '';
    field.dispatchEvent(new Event('input'));
    await settle();
    expect(element.querySelector('.smt-control__error')).toBeNull();
    field.dispatchEvent(new Event('blur'));
    await settle();
    expect(element.querySelector('.smt-control__error')?.textContent).toContain('This field is required');
    expect(field.getAttribute('aria-invalid')).toBe('true');
    expect(fixture.componentInstance.note.text().touched()).toBe(true);
  });

  it('keeps the rows it was given when growing is off, and honours disabled and read-only', async () => {
    const { fixture, field, settle } = await render(PlainHost);
    expect(field.rows).toBe(5);
    expect(field.style.height).toBe('');
    expect(field.readOnly).toBe(true);
    expect(field.value).toBe('plain');
    fixture.componentInstance.off.set(true);
    await settle();
    expect(field.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.smt-textarea__counter')).toBeNull();
  });

  it('grows with its text between rows and maxRows', async () => {
    const { field, settle } = await render(FormHost);
    // jsdom has no layout: stand in for the browser's scroll height.
    Object.defineProperty(field, 'scrollHeight', { configurable: true, get: () => 400 });
    field.value = 'a\nb\nc';
    field.dispatchEvent(new Event('input'));
    await settle();
    // 12 rows of 20px plus padding 7+7 and borders 1+1 in the stylesheet; jsdom reads no stylesheet, so 12 × 20.
    expect(parseFloat(field.style.height)).toBeLessThanOrEqual(12 * 20 + 16);
    expect(field.style.overflowY).toBe('auto');
  });
});
