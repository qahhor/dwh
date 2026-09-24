/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/modal/modal-confirm/modal-confirm.component.spec.ts.
 * Per ADR-0015 rule 6 the tests travel with the component; the tests after
 * the kit's three cover our changes. */
// @vitest-environment jsdom
import '@angular/compiler';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import type { SMTModalConfirmData } from '../types/modal-confirm.types';
import { SMTModalConfirmComponent } from './modal-confirm.component';

type ConfirmInput = Omit<SMTModalConfirmData, 'titleId' | 'messageId'>;

describe('SMTModalConfirmComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  function configure(data: ConfirmInput) {
    const close = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        { provide: SMTI18nService, useValue: testI18n() },
        { provide: DIALOG_DATA, useValue: { titleId: 'confirm-title', messageId: 'confirm-message', ...data } },
        { provide: DialogRef, useValue: { close } },
      ],
    });

    return close;
  }

  function createComponent(data: ConfirmInput) {
    const close = configure(data);
    const component = TestBed.runInInjectionContext(() => new SMTModalConfirmComponent());

    return {
      close,
      component,
    };
  }

  function render(data: ConfirmInput) {
    const close = configure(data);
    const fixture = TestBed.createComponent(SMTModalConfirmComponent);
    fixture.detectChanges();
    return { close, fixture, host: fixture.nativeElement as HTMLElement };
  }

  it('starts the countdown immediately and enables confirm after the timer ends', () => {
    vi.useFakeTimers();

    const { component } = createComponent({
      message: 'Delete the document?',
      timer: 2,
      yesLabel: 'Delete',
    });

    expect(component.yesLabel()).toBe('Delete');
    expect(component.countdown()).toBe(2);
    expect(component.isConfirmDisabled()).toBe(true);

    vi.advanceTimersByTime(1000);

    expect(component.countdown()).toBe(1);
    expect(component.isConfirmDisabled()).toBe(true);

    vi.advanceTimersByTime(1000);

    expect(component.countdown()).toBe(0);
    expect(component.isConfirmDisabled()).toBe(false);
  });

  it('exposes cancel flow state and closes with the cancel action', () => {
    const { close, component } = createComponent({
      message: 'Stop the sync?',
      cancelLabel: 'Abort',
    });

    expect(component.hasCancel()).toBe(true);
    expect(component.cancelLabel()).toBe('Abort');

    component.cancel();

    expect(close).toHaveBeenCalledWith({ action: 'cancel' });
  });

  it('keeps the cancel flow disabled when no cancel settings are provided', () => {
    const { component } = createComponent({
      message: 'Continue?',
    });

    expect(component.hasCancel()).toBe(false);
    expect(component.cancelLabel()).toBe('Cancel');
  });

  it('renders the message as text, so markup in it is never parsed', () => {
    const { host } = render({ message: '<img src=x onerror="alert(1)">Remove?' });

    const message = host.querySelector('#confirm-message')!;
    expect(message.querySelector('img')).toBeNull();
    expect(message.textContent).toContain('<img src=x onerror="alert(1)">Remove?');
  });

  it('carries the ids the dialog is labelled and described by', () => {
    const { host } = render({ title: 'Delete role', message: 'Gone for good.' });

    expect(host.querySelector('#confirm-title')?.textContent?.trim()).toBe('Delete role');
    expect(host.querySelector('#confirm-message')?.textContent?.trim()).toBe('Gone for good.');
  });

  it('starts focus on the declining button and puts it before the confirming one', () => {
    const { host } = render({ message: 'Discard changes?' });

    const buttons = Array.from(host.querySelectorAll('button'));
    expect(buttons.map(button => button.textContent?.trim())).toEqual(['No', 'Yes']);
    expect(buttons[0].hasAttribute('cdkFocusInitial')).toBe(true);
  });

  it('styles the confirming button as danger only for a destructive action', () => {
    const plain = render({ message: 'Publish?' }).host.querySelectorAll('button')[1];
    expect(plain.classList).toContain('smt-modal-button--primary');
    expect(plain.classList).not.toContain('smt-modal-button--danger');
    TestBed.resetTestingModule();

    const destructive = render({ message: 'Delete?', destructive: true }).host.querySelectorAll('button')[1];
    expect(destructive.classList).toContain('smt-modal-button--danger');
  });

  it('does not confirm while the countdown runs', () => {
    vi.useFakeTimers();
    const { close, component } = createComponent({ message: 'Delete all?', timer: 3 });

    component.confirm();

    expect(close).not.toHaveBeenCalled();
  });
});
