/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path utils/manage-component-classes.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { effect, ElementRef, Signal } from '@angular/core';

/**
 * Manages component classes by updating classList directly without conflicts with parent classes.
 * This approach allows Angular's ngClass and other parent directives to work correctly
 * while the component manages its own internal classes.
 *
 * @param hostElement - ElementRef of the host element
 * @param classListSignal - Computed signal that returns array of class names
 * @returns Cleanup function (handled automatically by effect)
 *
 * @example
 * ```typescript
 * export class MyComponent {
 *   private host = inject(ElementRef<HTMLElement>);
 *
 *   private classList = computed(() => {
 *     const variant = this.variant();
 *     return ['base-class', variant, this.isActive() ? 'active' : ''].filter(Boolean);
 *   });
 *
 *   constructor() {
 *     manageComponentClasses(this.host, this.classList);
 *   }
 * }
 * ```
 */
export function manageComponentClasses(hostElement: ElementRef<HTMLElement>, classListSignal: Signal<string[]>): void {
  let previousClasses = new Set<string>();

  effect(() => {
    const rawClasses = classListSignal();

    // Normalize classes: split any class strings that contain spaces
    const newClasses = rawClasses
      .flatMap(cls => (cls.includes(' ') ? parseClassString(cls) : [cls]))
      .filter(cls => cls && cls.length > 0);

    const newClassesSet = new Set(newClasses);

    // Early exit if nothing changed
    if (setsAreEqual(previousClasses, newClassesSet)) {
      return;
    }

    const el = hostElement.nativeElement;
    const classList = el.classList;

    // Remove old classes that we added previously
    previousClasses.forEach(cls => {
      if (!newClassesSet.has(cls)) {
        classList.remove(cls);
      }
    });

    // Add new classes
    newClasses.forEach(cls => {
      if (cls && !classList.contains(cls)) {
        classList.add(cls);
      }
    });

    // Update tracking set
    previousClasses = newClassesSet;
  });
}

/**
 * Helper to compare two sets for equality
 */
function setsAreEqual<T>(a: Set<T>, b: Set<T>): boolean {
  if (a.size !== b.size) return false;
  for (const item of a) {
    if (!b.has(item)) return false;
  }
  return true;
}

/**
 * Helper to convert space-separated class string to array
 * Useful when you have computed class strings that need to be converted
 *
 * @param classString - Space-separated class names
 * @returns Array of individual class names
 *
 * @example
 * ```typescript
 * const classes = computed(() => {
 *   const classString = `base ${variant()} ${isActive() ? 'active' : ''}`;
 *   return parseClassString(classString);
 * });
 * ```
 */
export function parseClassString(classString: string): string[] {
  return classString
    .split(/\s+/)
    .map(cls => cls.trim())
    .filter(cls => cls.length > 0);
}
