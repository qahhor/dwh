import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { UiFormActionsComponent } from './ui-form-actions.component';

@Component({
  imports: [UiFormActionsComponent],
  template: `
    <form id="host-form" (submit)="$event.preventDefault(); submits.set(submits() + 1)">
      <input name="name" />
    </form>
    <ui-form-actions
      [form]="linked() ? 'host-form' : ''"
      [submitLabel]="label()"
      [submitting]="submitting()"
      [submitDisabled]="locked()"
      (submitted)="presses.set(presses() + 1)"
      (cancelled)="cancels.set(cancels() + 1)"
    >
      <button uiFormActionsStart type="button" data-testid="extra">Delete</button>
    </ui-form-actions>
  `,
})
class Host {
  readonly linked = signal(false);
  readonly label = signal('');
  readonly submitting = signal(false);
  readonly locked = signal(false);
  readonly submits = signal(0);
  readonly presses = signal(0);
  readonly cancels = signal(0);
}

describe('ui-form-actions', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const submit = () => element.querySelector('[data-testid="form-submit"]') as HTMLButtonElement;
    const cancel = () => element.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement;
    return { fixture, host: fixture.componentInstance, element, submit, cancel };
  }

  it('puts the extra action apart on the left, then Cancel, then the primary button with the common texts', () => {
    const { element, submit, cancel } = render();
    const buttons = [...element.querySelectorAll('ui-form-actions button')];
    expect(buttons.map((button) => button.getAttribute('data-testid'))).toEqual([
      'extra',
      'form-cancel',
      'form-submit',
    ]);
    expect(cancel().textContent?.trim()).toBe('Отмена');
    expect(submit().textContent?.trim()).toBe('Сохранить');
    expect(submit().type).toBe('button');
    expect(submit().classList).toContain('smt-button--primary');
  });

  it('emits submitted and cancelled when no form is linked', () => {
    const { host, submit, cancel } = render();
    submit().click();
    cancel().click();
    expect(host.presses()).toBe(1);
    expect(host.cancels()).toBe(1);
  });

  it('submits the linked form instead of emitting, and takes its own label', () => {
    const { fixture, host, submit } = render();
    host.linked.set(true);
    host.label.set('Создать');
    fixture.detectChanges();
    expect(submit().getAttribute('form')).toBe('host-form');
    expect(submit().type).toBe('submit');
    expect(submit().textContent?.trim()).toBe('Создать');
    submit().click();
    expect(host.submits()).toBe(1);
    expect(host.presses()).toBe(0);
  });

  it('while submitting shows the spinner, disables both buttons and ignores a second press', () => {
    const { fixture, host, submit, cancel } = render();
    host.submitting.set(true);
    fixture.detectChanges();
    expect(submit().disabled).toBe(true);
    expect(submit().getAttribute('aria-busy')).toBe('true');
    expect(cancel().disabled).toBe(true);
    const actions = fixture.debugElement.children[1].componentInstance as UiFormActionsComponent;
    actions.submit();
    actions.cancel();
    expect(host.presses()).toBe(0);
    expect(host.cancels()).toBe(0);
  });

  it('a state the form cannot fix disables the primary button only', () => {
    const { fixture, host, submit, cancel } = render();
    host.locked.set(true);
    fixture.detectChanges();
    expect(submit().disabled).toBe(true);
    expect(cancel().disabled).toBe(false);
    const actions = fixture.debugElement.children[1].componentInstance as UiFormActionsComponent;
    actions.submit();
    expect(host.presses()).toBe(0);
  });
});
