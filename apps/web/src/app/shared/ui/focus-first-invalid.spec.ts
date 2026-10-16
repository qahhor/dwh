import { Component, Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from './focus-first-invalid';

@Component({
  imports: [UiFocusFirstInvalidDirective],
  template: `
    <form id="f" uiFocusFirstInvalid novalidate (submit)="$event.preventDefault(); submitted.set(true)">
      <input id="ok" />
      <input id="first" [attr.aria-invalid]="submitted() ? 'true' : null" />
      <input id="second" [attr.aria-invalid]="submitted() ? 'true' : null" />
      <button id="go" type="submit">Go</button>
    </form>
  `,
})
class Host {
  readonly submitted = signal(false);
}

describe('uiFocusFirstInvalid', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('focuses the first field a submit made invalid, after the errors are drawn', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.autoDetectChanges();
    document.body.appendChild(fixture.nativeElement);
    const element = fixture.nativeElement as HTMLElement;
    (element.querySelector('#go') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(document.activeElement?.id).toBe('first');
  });

  it('skips unavailable errors and focuses the selected option of an invalid radio group', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.autoDetectChanges();
    const root = fixture.nativeElement as HTMLElement;
    root.innerHTML =
      '<div hidden><input aria-invalid="true" id="hidden-error"></div>' +
      '<input disabled aria-invalid="true" id="disabled-error">' +
      '<div aria-invalid="true" role="radiogroup">' +
      '<div role="radio" tabindex="-1" id="unselected">First</div>' +
      '<div role="radio" tabindex="0" id="selected">Second</div></div>';
    document.body.appendChild(root);
    focusFirstInvalid(root, fixture.debugElement.injector.get(Injector));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement?.id).toBe('selected');
  });

  it('leaves focus alone when nothing is invalid', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.autoDetectChanges();
    document.body.appendChild(fixture.nativeElement);
    const element = fixture.nativeElement as HTMLElement;
    const ok = element.querySelector('#ok') as HTMLInputElement;
    ok.focus();
    fixture.componentInstance.submitted.set(false);
    const directive = fixture.debugElement.children[0].injector.get(UiFocusFirstInvalidDirective);
    directive.onSubmit();
    await fixture.whenStable();
    expect(document.activeElement).toBe(ok);
  });
});
