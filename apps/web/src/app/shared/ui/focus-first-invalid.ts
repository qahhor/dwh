import { afterNextRender, Directive, ElementRef, inject, Injector } from '@angular/core';

/** The fields smt-control and the kit controls mark invalid. */
const INVALID = '[aria-invalid="true"]';

/**
 * Moves focus to the first invalid field inside `root` and brings it into view (docs/guidelines/forms-ux-standard.md,
 * section 4). Runs after the next render, so the errors a submit has just revealed are in the page. Returns at once;
 * nothing happens when no field is invalid.
 */
export function focusFirstInvalid(root: HTMLElement, injector: Injector): void {
  afterNextRender(
    () => {
      const field = root.querySelector<HTMLElement>(INVALID);
      if (!field) return;
      field.scrollIntoView?.({ block: 'center' });
      field.focus({ preventScroll: true });
    },
    { injector },
  );
}

/**
 * On a form: every submit (Enter in a field, a submit button, a footer button linked by `form="…"`) moves focus to
 * the first field the submit made invalid. Errors the server returns later are focused by the screen with
 * `focusFirstInvalid(form, injector)` once it has shown them.
 *
 * <form id="role-create" uiFocusFirstInvalid (submit)="$event.preventDefault(); save()" novalidate>
 */
@Directive({
  selector: 'form[uiFocusFirstInvalid]',
  host: { '(submit)': 'onSubmit()' },
})
export class UiFocusFirstInvalidDirective {
  private readonly host = inject<ElementRef<HTMLFormElement>>(ElementRef);

  private readonly injector = inject(Injector);

  onSubmit(): void {
    focusFirstInvalid(this.host.nativeElement, this.injector);
  }
}
