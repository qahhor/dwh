// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormField, form, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTCheckboxComponent } from './checkbox.component';

@Component({
  standalone: true,
  imports: [SMTCheckboxComponent, FormField],
  template: `<label smt-checkbox [formField]="terms.accepted">I accept the terms</label>`,
})
class FormHost {
  readonly model = signal({ accepted: false });
  readonly terms = form(this.model, path => required(path.accepted));
}

describe('SMTCheckboxComponent with Signal Forms', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    const fixture = TestBed.createComponent(FormHost);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const box = fixture.nativeElement.querySelector('[role="checkbox"]') as HTMLElement;
    return { fixture, box, settle };
  }

  it('writes the choice into the form and marks the field touched', async () => {
    const { fixture, box, settle } = await render();
    box.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
    await settle();
    expect(fixture.componentInstance.model().accepted).toBe(true);
    expect(box.getAttribute('aria-checked')).toBe('true');
    // Angular 22 listens to `touch`; the kit's `touchedChange` never reached the form.
    expect(fixture.componentInstance.terms.accepted().touched()).toBe(true);
  });

  it('marks the field touched when focus leaves it, and shows the error from then on', async () => {
    const { fixture, box, settle } = await render();
    expect(box.getAttribute('aria-invalid')).toBeNull();
    box.dispatchEvent(new Event('blur'));
    await settle();
    expect(fixture.componentInstance.terms.accepted().touched()).toBe(true);
    expect(box.getAttribute('aria-invalid')).toBe('true');
  });

  it('forgets its own touched state when the form is reset', async () => {
    const { fixture, box, settle } = await render();
    box.dispatchEvent(new Event('blur'));
    await settle();
    fixture.componentInstance.terms().reset();
    await settle();
    expect(box.getAttribute('aria-invalid')).toBeNull();
  });
});
