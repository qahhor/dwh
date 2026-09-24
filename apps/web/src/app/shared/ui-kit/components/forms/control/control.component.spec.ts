// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, form, minLength, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { TEST_MESSAGES, testI18n } from '../../../i18n/test-messages';
import { SMTControlComponent } from './control.component';
import { fromLegacyErrors, messageForError } from './control-messages';

describe('smt-control messages', () => {
  const messages = TEST_MESSAGES.control;

  it('prefers the message a validator supplied', () => {
    expect(messageForError({ kind: 'server', message: 'Login is taken' }, messages)).toBe('Login is taken');
  });

  it('maps the built-in Signal Forms kinds to catalogue text', () => {
    expect(messageForError({ kind: 'required' }, messages)).toBe('This field is required');
    expect(messageForError({ kind: 'email' }, messages)).toBe('Enter a valid email address');
    expect(messageForError({ kind: 'minLength', minLength: 3 }, messages)).toBe('At least 3 characters');
    expect(messageForError({ kind: 'max', max: 10 }, messages)).toBe('Must be at most 10');
    expect(messageForError({ kind: 'parse' }, messages)).toBe('The value could not be read');
    expect(messageForError({ kind: 'something-else' }, messages)).toBe('Invalid value');
    expect(messageForError(undefined, messages)).toBe('');
  });

  it('turns a legacy error map into the same shape', () => {
    expect(
      fromLegacyErrors({
        required: true,
        minlength: { requiredLength: 3, actualLength: 1 },
        max: { max: 5, actual: 9 },
        custom: 'Custom text',
      })
    ).toEqual([
      { kind: 'required' },
      { kind: 'minLength', minLength: 3 },
      { kind: 'max', max: 5 },
      { kind: 'custom', message: 'Custom text' },
    ]);
  });
});

@Component({
  standalone: true,
  imports: [SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Name" smtHint="As in the passport" [smtError]="serverError()">
      <input [formField]="profile.name" aria-describedby="external-note" />
    </smt-control>
  `,
})
class SignalFormHost {
  readonly model = signal({ name: '' });
  readonly profile = form(this.model, path => {
    required(path.name);
    minLength(path.name, 3);
  });
  readonly serverError = signal('');
}

@Component({
  standalone: true,
  imports: [SMTControlComponent, FormsModule],
  template: `
    <smt-control smtLabel="Code">
      <input id="code-input" name="code" [(ngModel)]="code" required minlength="3" />
    </smt-control>
  `,
})
class NgModelHost {
  code = '';
}

describe('SMTControlComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render<T>(host: new () => T) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return {
      fixture,
      element,
      input: element.querySelector('input') as HTMLInputElement,
      label: element.querySelector('label') as HTMLLabelElement,
      async settle() {
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
      },
    };
  }

  describe('with a Signal Forms field', () => {
    it('names the field with its label and marks it required', async () => {
      const { input, label } = await render(SignalFormHost);

      expect(label.htmlFor).toBe(input.id);
      expect(input.id).not.toBe('');
      expect(input.getAttribute('aria-required')).toBe('true');
      expect(label.querySelector('[aria-hidden="true"]')?.textContent).toBe('*');
    });

    it('keeps the field’s own descriptions and adds the hint', async () => {
      const { element, input } = await render(SignalFormHost);

      const tokens = input.getAttribute('aria-describedby')!.split(' ');
      expect(tokens[0]).toBe('external-note');
      const hint = element.querySelector('.smt-control__hint')!;
      expect(tokens).toContain(hint.id);
    });

    it('hides errors until the field is touched, then describes the field with them', async () => {
      const { element, fixture, input, settle } = await render(SignalFormHost);

      expect(element.querySelector('.smt-control__error')).toBeNull();
      expect(input.hasAttribute('aria-invalid')).toBe(false);

      fixture.componentInstance.profile.name().markAsTouched();
      await settle();

      const error = element.querySelector('.smt-control__error')!;
      expect(error.textContent?.trim()).toBe('This field is required');
      expect(input.getAttribute('aria-invalid')).toBe('true');
      expect(input.getAttribute('aria-describedby')!.split(' ')).toContain(error.id);
      expect(element.querySelector('smt-control')!.classList).toContain('smt-control--invalid');
    });

    it('follows the value: a short value shows the length rule, a valid one clears the error', async () => {
      const { element, fixture, input, settle } = await render(SignalFormHost);
      fixture.componentInstance.profile.name().markAsTouched();

      fixture.componentInstance.model.set({ name: 'ab' });
      await settle();
      expect(element.querySelector('.smt-control__error')?.textContent?.trim()).toBe('At least 3 characters');

      fixture.componentInstance.model.set({ name: 'abc' });
      await settle();
      expect(element.querySelector('.smt-control__error')).toBeNull();
      expect(input.hasAttribute('aria-invalid')).toBe(false);
      expect(input.getAttribute('aria-describedby')!.split(' ')).toEqual(['external-note', element.querySelector('.smt-control__hint')!.id]);
    });

    it('shows an error from outside the field at once', async () => {
      const { element, fixture, settle } = await render(SignalFormHost);

      fixture.componentInstance.serverError.set('Name is already used');
      await settle();

      expect(element.querySelector('.smt-control__error')?.textContent?.trim()).toBe('Name is already used');
    });
  });

  describe('with a legacy ngModel field', () => {
    it('keeps the field’s own id for the label and reads required from the attribute', async () => {
      const { input, label } = await render(NgModelHost);

      expect(input.id).toBe('code-input');
      expect(label.htmlFor).toBe('code-input');
      expect(input.getAttribute('aria-required')).toBe('true');
    });

    it('shows the legacy validation error after blur', async () => {
      const { element, input, settle } = await render(NgModelHost);

      input.value = 'ab';
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      await settle();

      expect(element.querySelector('.smt-control__error')?.textContent?.trim()).toBe('At least 3 characters');
      expect(input.getAttribute('aria-invalid')).toBe('true');
    });
  });
});
