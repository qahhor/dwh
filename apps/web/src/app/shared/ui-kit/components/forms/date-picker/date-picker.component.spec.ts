// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, form, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { SMTControlComponent } from '../control/control.component';
import { SMTDatePickerComponent } from './date-picker.component';
import { SMTDatePickerValueAccessor } from './date-picker-value-accessor';

@Component({
  standalone: true,
  imports: [SMTDatePickerComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Due date">
      <smt-date-picker [formField]="task.due" [smtWithTime]="withTime()" smtMin="2026-01-01" smtMax="2026-12-31" />
    </smt-control>
  `,
})
class SignalHost {
  readonly model = signal({ due: '2026-09-04' as string | null });
  readonly task = form(this.model, path => required(path.due));
  readonly withTime = signal(false);
}

@Component({
  standalone: true,
  imports: [SMTDatePickerComponent, SMTDatePickerValueAccessor, FormsModule],
  template: `<smt-date-picker [(ngModel)]="from" [disabled]="false" name="from" />`,
})
class NgModelHost {
  from: string | null = '2026-09-10';
}

describe('SMTDatePickerComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function render<T>(host: new () => T) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const text = element.querySelector('.smt-date-picker__input') as HTMLInputElement;
    const toggle = element.querySelector('.smt-date-picker__toggle') as HTMLButtonElement;
    const type = async (value: string) => {
      text.value = value;
      text.dispatchEvent(new Event('input'));
      text.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      await settle();
    };
    const settle = async () => {
      TestBed.tick();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    return { fixture, element, text, toggle, type, settle };
  }

  describe('bound with Signal Forms', () => {
    it('shows the value in the language format and is labelled by the surrounding smt-control', async () => {
      const { element, text } = await render(SignalHost);

      expect(text.value).toBe('04.09.2026');
      expect(element.querySelector('label')!.htmlFor).toBe(text.id);
      expect(text.getAttribute('aria-required')).toBe('true');
    });

    it('writes a typed date back to the form as ISO', async () => {
      const { fixture, type } = await render(SignalHost);

      await type('1.10.2026');

      expect(fixture.componentInstance.model().due).toBe('2026-10-01');
    });

    it('keeps the value and explains the format when the text is not a date or is out of range', async () => {
      const { fixture, element, text, type } = await render(SignalHost);

      await type('31.02.2026');
      expect(fixture.componentInstance.model().due).toBe('2026-09-04');
      const hint = element.querySelector('.smt-date-picker__hint')!;
      expect(hint.textContent).toContain('Enter a date as dd.mm.yyyy');
      expect(text.getAttribute('aria-invalid')).toBe('true');
      expect(text.getAttribute('aria-describedby')!.split(' ')).toContain(hint.id);

      await type('01.01.2027');
      expect(fixture.componentInstance.model().due).toBe('2026-09-04');

      await type('');
      expect(fixture.componentInstance.model().due).toBeNull();
      expect(element.querySelector('.smt-date-picker__hint')).toBeNull();
    });

    it('opens a calendar dialog on the picked day, picks with the keyboard and returns focus to the field', async () => {
      const { fixture, text, toggle, settle } = await render(SignalHost);

      toggle.click();
      await settle();
      const dialog = document.querySelector('[role="dialog"]')!;
      expect(dialog.getAttribute('aria-modal')).toBe('true');
      expect(dialog.getAttribute('aria-label')).toBe('Choose a date');
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(document.activeElement?.getAttribute('data-date')).toBe('2026-09-04');

      const grid = dialog.querySelector('[role="grid"]')!;
      grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await settle();
      grid.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await settle();

      expect(fixture.componentInstance.model().due).toBe('2026-09-05');
      expect(document.querySelector('[role="dialog"]')).toBeNull();
      expect(document.activeElement).toBe(text);
    });

    it('closes the dialog on Escape without changing the value and focuses the calendar button', async () => {
      const { fixture, toggle, settle } = await render(SignalHost);

      toggle.click();
      await settle();
      document.querySelector('[role="dialog"]')!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
      );
      await settle();

      expect(document.querySelector('[role="dialog"]')).toBeNull();
      expect(fixture.componentInstance.model().due).toBe('2026-09-04');
      expect(document.activeElement).toBe(toggle);
    });

    it('keeps the time when the date changes and sets it through the time field', async () => {
      const { fixture, element, type, settle } = await render(SignalHost);
      fixture.componentInstance.withTime.set(true);
      fixture.componentInstance.model.set({ due: '2026-09-04T14:30' });
      await settle();

      const time = element.querySelector('.smt-date-picker__time') as HTMLInputElement;
      expect(time.value).toBe('14:30');
      expect(time.getAttribute('aria-label')).toBe('Time');

      await type('05.09.2026');
      expect(fixture.componentInstance.model().due).toBe('2026-09-05T14:30');

      time.value = '09:15';
      time.dispatchEvent(new Event('change'));
      await settle();
      expect(fixture.componentInstance.model().due).toBe('2026-09-05T09:15');
    });
  });

  it('commits a typed date when focus moves on inside the picker, so the time applies to it', async () => {
    const { fixture, element, text, settle } = await render(SignalHost);
    fixture.componentInstance.withTime.set(true);
    fixture.componentInstance.model.set({ due: '2026-09-04T14:30' });
    await settle();

    text.value = '07.09.2026';
    text.dispatchEvent(new Event('input'));
    text.dispatchEvent(new FocusEvent('blur'));
    await settle();
    expect(fixture.componentInstance.model().due).toBe('2026-09-07T14:30');

    const time = element.querySelector('.smt-date-picker__time') as HTMLInputElement;
    time.value = '08:00';
    time.dispatchEvent(new Event('change'));
    await settle();
    expect(fixture.componentInstance.model().due).toBe('2026-09-07T08:00');
  });

  describe('bound with ngModel through the value accessor', () => {
    it('shows the model value and reports typed changes back', async () => {
      const { fixture, text, type } = await render(NgModelHost);

      expect(text.value).toBe('10.09.2026');
      await type('11.09.2026');

      expect(fixture.componentInstance.from).toBe('2026-09-11');
    });
  });
});
