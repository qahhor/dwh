import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { UiFocusFirstInvalidDirective } from './focus-first-invalid';

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
