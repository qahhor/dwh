/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path forms/form-control-validation.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
export interface SMTFormControlValidationState {
  invalid?: boolean;
  errors?: readonly unknown[];
  touched?: boolean;
  legacyInvalid?: boolean;
  required?: boolean;
  empty?: boolean;
  controlError?: boolean;
  localError?: boolean;
}

interface SMTTouchableFieldState {
  markAsTouched(): void;
}

type SMTFieldTree = (() => SMTTouchableFieldState) & Record<string, unknown>;

/**
 * Resolves whether a form control should render its invalid state.
 *
 * Signal-form errors are shown only after the field is touched. Use
 * `markSMTFormFieldsTouched()` before Angular's `submit()` when untouched
 * required fields should become visible. Explicit legacy and local errors
 * remain immediate for backwards compatibility and parsing failures.
 */
export function shouldShowSMTFormControlError(state: SMTFormControlValidationState): boolean {
  const formInvalid = !!state.invalid || (state.errors?.length ?? 0) > 0;
  const requiredInvalid = !!state.required && !!state.empty;

  return (
    !!state.controlError ||
    !!state.legacyInvalid ||
    !!state.localError ||
    ((formInvalid || requiredInvalid) && !!state.touched)
  );
}

/**
 * Marks every leaf in an Angular Signal Forms field tree as touched.
 *
 * Angular's `submit()` validates and runs the submit action, but it does not
 * mark untouched child fields. Call this immediately before `submit()` when a
 * save attempt should reveal every invalid field.
 */
export function markSMTFormFieldsTouched(fieldTree: unknown): void {
  const visited = new Set<unknown>();

  const visit = (node: unknown): void => {
    if (typeof node !== 'function' || visited.has(node)) return;
    visited.add(node);

    const tree = node as SMTFieldTree;
    const children = Object.values(tree).filter(value => typeof value === 'function');
    if (children.length > 0) {
      children.forEach(visit);
    }

    tree().markAsTouched();
  };

  visit(fieldTree);
}
