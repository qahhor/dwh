const EDITABLE = '[contenteditable]:not([contenteditable="false"])';
const FOCUSABLE = `input:not([type="hidden"]), select, textarea, button, a[href], [tabindex], ${EDITABLE}`;
const UNAVAILABLE = '[hidden], [inert], [aria-hidden="true"], [aria-disabled="true"]';

/** Focuses an available native control or the active option of a composite field. */
export function focusFormField(field: HTMLElement): boolean {
  const candidates = field.matches(FOCUSABLE)
    ? [field]
    : [...field.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
        (item) => item.tabIndex >= 0 || (item.matches(EDITABLE) && !item.hasAttribute('tabindex')),
      );
  for (const target of candidates) {
    if (target.closest(UNAVAILABLE) || target.matches(':disabled')) continue;
    target.focus({ preventScroll: true });
    if (target.ownerDocument.activeElement !== target) continue;
    target.scrollIntoView?.({ block: 'center' });
    return true;
  }
  return false;
}
