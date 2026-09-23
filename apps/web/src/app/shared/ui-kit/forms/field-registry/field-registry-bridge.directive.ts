/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path forms/field-registry/field-registry-bridge.directive.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { Directive, effect, inject, type WritableSignal } from '@angular/core';
import { FORM_FIELD } from '@angular/forms/signals';
import { UI_KIT_FIELD_REGISTRY, type UiKitFieldHandle } from './field-registry.token';

/**
 * Shared bridge: when a host provides {@link UI_KIT_FIELD_REGISTRY} and the control is
 * bound with `[formField]`, register `{ key, value }` for the live field state.
 *
 * Attached to form controls via `hostDirectives` so coverage stays in one place.
 * Inert when either the registry or `FORM_FIELD` is missing (`optional: true`).
 */
@Directive({
  selector: '[smtFormFieldRegistryBridge]',
  standalone: true,
})
export class SmtFormFieldRegistryBridgeDirective {
  private readonly fieldRegistry = inject(UI_KIT_FIELD_REGISTRY, { optional: true });

  /** Same-element `[formField]` only — do not pick up a parent field. */
  private readonly formField = inject(FORM_FIELD, { optional: true, self: true });

  constructor() {
    effect(onCleanup => {
      const registry = this.fieldRegistry;
      const formField = this.formField;
      if (!registry || !formField) {
        return;
      }

      const state = formField.state();
      const handle: UiKitFieldHandle = {
        key: String(state.keyInParent()),
        value: state.value as WritableSignal<unknown>,
      };

      registry.register(handle);
      onCleanup(() => registry.unregister(handle));
    });
  }
}

/** Use in `@Component({ hostDirectives: SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES })`. */
export const SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES = [SmtFormFieldRegistryBridgeDirective] as const;
