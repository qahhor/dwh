// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { SMTButtonComponent } from './button.component';

@Component({
  standalone: true,
  imports: [SMTButtonComponent],
  template: `
    <form (submit)="$event.preventDefault(); submitted = submitted + 1">
      <button smt-button type="submit" smtIcon="save" class="save" [smtLoading]="saving()" [disabled]="locked()"
        aria-label="Save the note" (click)="clicks = clicks + 1">Save</button>
      <button smt-button type="button" smtVariant="danger" smtSize="sm" smtFullWidth>Delete</button>
      <button smt-button type="button" smtVariant="ghost" smtIconOnly smtIcon="close" aria-label="Close"></button>
    </form>
    <a smt-button smtVariant="ghost" href="#help" [disabled]="locked()" (click)="linkClicks = linkClicks + 1">Help</a>
  `,
})
class Host {
  readonly saving = signal(false);
  readonly locked = signal(false);
  clicks = 0;
  linkClicks = 0;
  submitted = 0;
}

describe('SMTButtonComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const [save, remove, close] = Array.from(element.querySelectorAll('button')) as HTMLButtonElement[];
    const link = element.querySelector('a') as HTMLAnchorElement;
    return { fixture, save, remove, close, link };
  }

  it('is the real button: its type submits the form, its aria-label and classes are its own', () => {
    const { fixture, save, remove } = render();
    expect(save.classList).toContain('smt-button');
    expect(save.classList).toContain('smt-button--primary');
    expect(save.classList).toContain('smt-button--md');
    expect(save.classList).toContain('save');
    expect(save.getAttribute('aria-label')).toBe('Save the note');
    expect(save.querySelector('.smt-button__icon')!.getAttribute('aria-hidden')).toBe('true');
    expect(remove.classList).toContain('smt-button--danger');
    expect(remove.classList).toContain('smt-button--sm');
    expect(remove.classList).toContain('smt-button--full');
    save.click();
    expect(fixture.componentInstance.clicks).toBe(1);
    expect(fixture.componentInstance.submitted).toBe(1);
  });

  it('draws an icon-only button square, named by its aria-label', () => {
    const { close } = render();
    expect(close.classList).toContain('smt-button--icon-only');
    expect(close.getAttribute('aria-label')).toBe('Close');
    expect(close.textContent!.trim()).toBe('close');
    expect(close.querySelector('.smt-button__icon')!.getAttribute('aria-hidden')).toBe('true');
  });

  it('is disabled and busy while loading, with a spinner and a hidden text instead of the icon', () => {
    const { fixture, save } = render();
    fixture.componentInstance.saving.set(true);
    fixture.detectChanges();
    expect(save.disabled).toBe(true);
    expect(save.getAttribute('aria-busy')).toBe('true');
    expect(save.querySelector('.smt-button__icon')).toBeNull();
    expect(save.querySelector('.smt-button__spinner')).not.toBeNull();
    expect(save.querySelector('.smt-button__sr-only')!.textContent).toBe('In progress');
    save.click();
    expect(fixture.componentInstance.clicks).toBe(0);
  });

  it('keeps a disabled link out of the tab order and does not follow it', () => {
    const { fixture, link } = render();
    fixture.componentInstance.locked.set(true);
    fixture.detectChanges();
    expect(link.getAttribute('aria-disabled')).toBe('true');
    expect(link.getAttribute('tabindex')).toBe('-1');
    expect(link.hasAttribute('disabled')).toBe(false);
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    expect(fixture.componentInstance.linkClicks).toBe(0);
  });
});
