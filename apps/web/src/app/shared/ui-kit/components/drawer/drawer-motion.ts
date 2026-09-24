/* Our helper: whether the drawer should animate at all. Not in the kit,
 * which animated even when the user asked the system for reduced motion. */

export const DRAWER_ANIMATION_MS = 300;

export function drawerShouldAnimate(element: Element | null | undefined): boolean {
  if (!element || typeof (element as HTMLElement).animate !== 'function') return false;
  const view = element.ownerDocument?.defaultView;
  return !view?.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}
