// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, form, maxLength, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTControlComponent } from '../control/control.component';
import { SMTInputComponent } from './input.component';
import { SMTInputValueAccessor } from './input-value-accessor';

@Component({
  standalone: true,
  imports: [SMTInputComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Name" smtHint="As on the badge">
      <smt-input [formField]="person.name" placeholder="Name" />
    </smt-control>
  `,
})
class FormHost {
  readonly model = signal({ name: 'Ann' });
  readonly person = form(this.model, path => {
    required(path.name);
    maxLength(path.name, 40);
  });
}

@Component({
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, FormsModule],
  template: `
    <smt-input type="number" [(ngModel)]="count" [smtMin]="0" [smtMax]="10" [disabled]="off()" smtAriaLabel="Count" />
    <smt-input type="search" clearable smtIcon="search" [(value)]="query" smtAriaLabel="Search" (keydown.enter)="submitted = submitted + 1" />
    <smt-input type="password" [(value)]="secret" smtAriaLabel="Password" [smtInvalid]="rejected()" />
  `,
})
class PlainHost {
  count: number | null = 3;
  query = 'cement';
  secret = 'hunter2';
  submitted = 0;
  readonly off = signal(false);
  readonly rejected = signal(false);
}

describe('SMTInputComponent', () => {
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
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const fields = Array.from(element.querySelectorAll('input')) as HTMLInputElement[];
    return { fixture, element, fields, settle };
  }

  it('is named by the smt-control label, described by its hint, and writes what is typed into the form', async () => {
    const { fixture, element, fields, settle } = await render(FormHost);
    const [field] = fields;
    expect((element.querySelector('label') as HTMLLabelElement).htmlFor).toBe(field.id);
    expect(field.getAttribute('aria-describedby')).toContain(element.querySelector('.smt-control__hint')!.id);
    expect(field.value).toBe('Ann');
    expect(field.getAttribute('maxlength')).toBe('40');
    field.value = 'Anna';
    field.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.model().name).toBe('Anna');
  });

  it('shows the error of a required field only once the person has left it', async () => {
    const { element, fields, settle } = await render(FormHost);
    const [field] = fields;
    field.value = '';
    field.dispatchEvent(new Event('input'));
    await settle();
    expect(field.getAttribute('aria-invalid')).toBeNull();
    field.dispatchEvent(new Event('blur'));
    await settle();
    expect(field.getAttribute('aria-invalid')).toBe('true');
    expect(element.querySelector('smt-input')!.classList).toContain('smt-input--invalid');
  });

  it('gives ngModel a number, null when emptied, and follows its disabled state', async () => {
    const { fixture, fields, settle } = await render(PlainHost);
    const [count] = fields;
    expect(count.type).toBe('number');
    expect(count.value).toBe('3');
    expect(count.getAttribute('min')).toBe('0');
    expect(count.getAttribute('max')).toBe('10');
    count.value = '7';
    count.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.count).toBe(7);
    count.value = '';
    count.dispatchEvent(new Event('input'));
    await settle();
    expect(fixture.componentInstance.count).toBeNull();
    fixture.componentInstance.off.set(true);
    await settle();
    expect(count.disabled).toBe(true);
  });

  it('clears the search with a named button and lets Enter reach the host', async () => {
    const { fixture, element, fields, settle } = await render(PlainHost);
    const search = fields[1];
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle();
    expect(fixture.componentInstance.submitted).toBe(1);
    const clear = element.querySelectorAll('smt-input')[1].querySelector('button') as HTMLButtonElement;
    expect(clear.getAttribute('aria-label')).toBe('Clear the field');
    clear.click();
    await settle();
    expect(fixture.componentInstance.query).toBe('');
    expect(element.querySelectorAll('smt-input')[1].querySelector('button')).toBeNull();
    expect(element.querySelectorAll('.smt-input__icon')[0].getAttribute('aria-hidden')).toBe('true');
  });

  it('shows an error the screen decides at once, before the field is touched', async () => {
    const { fixture, fields, settle } = await render(PlainHost);
    expect(fields[2].getAttribute('aria-invalid')).toBeNull();
    fixture.componentInstance.rejected.set(true);
    await settle();
    expect(fields[2].getAttribute('aria-invalid')).toBe('true');
  });

  it('shows and hides the password with a button that says what it does', async () => {
    const { element, fields, settle } = await render(PlainHost);
    const password = fields[2];
    const toggle = element.querySelectorAll('smt-input')[2].querySelector('button') as HTMLButtonElement;
    expect(password.type).toBe('password');
    expect(toggle.getAttribute('aria-label')).toBe('Show the password');
    toggle.click();
    await settle();
    expect(password.type).toBe('text');
    expect(toggle.getAttribute('aria-label')).toBe('Hide the password');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
  });
});
