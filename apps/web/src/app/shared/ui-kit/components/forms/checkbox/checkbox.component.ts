/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path components/forms/checkbox/checkbox.component.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  model,
  output,
  signal,
  ViewChild,
} from '@angular/core';
// Compatibility: Angular 22 renamed WithOptionalField to WithOptionalFieldTree.
import type { FormCheckboxControl, ValidationError, WithOptionalFieldTree } from '@angular/forms/signals';
import { NgClass } from '@angular/common';
import { injectRegisterSMTIcons } from '../../../providers/svg-icon.provider';
import { checkboxCheckIcon, checkboxMinusIcon } from '../../../svg-icons';
import { SMTIconComponent } from '../../icon/icon';
import type { SMTControlSize } from '../../../types/control-size';
import { SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES } from '../../../forms/field-registry';
import { shouldShowSMTFormControlError } from '../../../forms/form-control-validation';

export type SMTCheckboxSize = SMTControlSize;

/** Checked-state accent: `brand` (default) or legacy role-access green from kernel. */
export type SMTCheckboxVariant = 'brand' | 'success';

let nextCheckboxUid = 0;

@Component({
  selector: '[smt-checkbox]',
  imports: [NgClass, SMTIconComponent],
  templateUrl: './checkbox.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'smt-checkbox',
  },
  hostDirectives: [...SMT_FORM_FIELD_REGISTRY_HOST_DIRECTIVES],
})
export class SMTCheckboxComponent<T> implements FormCheckboxControl {
  /**
   * Item identity for `smt-checkbox-group` selections. Named `groupItemValue` (not `value`) so this
   * control can implement {@link FormCheckboxControl} for `[formField]` (signal forms).
   */
  groupItemValue = input<T | null>(null, { alias: 'smtValue' });

  autoFocus = input(false, { alias: 'smtAutoFocus' });

  id = input<string | null>(null);

  preventDefault = input(true, { alias: 'smtPreventDefault' });

  /** Template / `[formField]` disable; merged with {@link setGroupDisabled} from `smt-checkbox-group`. */
  disabled = input(false, { transform: booleanAttribute });

  invalid = input(false, { transform: booleanAttribute });

  errors = input<readonly WithOptionalFieldTree<ValidationError>[]>([]);

  required = input(false, { transform: booleanAttribute });

  isHalfChecked = input<boolean>(false, { alias: 'smtIsHalfChecked' });

  size = input<SMTCheckboxSize>('md', { alias: 'smtSize' });

  variant = input<SMTCheckboxVariant>('brand', { alias: 'smtVariant' });

  hideLabel = input(false, { alias: 'smtHideLabel', transform: booleanAttribute });

  /** Accessible name when the visible label is hidden (`smtHideLabel`). */
  ariaLabel = input('', { alias: 'smtAriaLabel' });

  hint = input<string | null>(null, { alias: 'smtHint' });

  /**
   * Always emitted on user toggle (click/keyboard), even when `checked` stays the same
   * (half-checked → clear). Prefer this for select-all / tri-state handlers; `checkedChange`
   * from the model only fires when the backing value changes.
   */
  checkedUserChange = output<boolean>({ alias: 'smtCheckedChange' });

  /** Two-way checked state; use `[(checked)]` or `[formField]` (emits `checkedChange`). */
  checked = model(false);

  touched = model(false);

  /** Internal: `smt-checkbox-group` disables projected children via `contentChildren` (projection breaks DI). */
  private groupDisabled = signal(false);

  readonly hasError = computed(() =>
    shouldShowSMTFormControlError({
      invalid: this.invalid(),
      errors: this.errors(),
      touched: this.touched(),
      required: this.required(),
      empty: !this.checked(),
    })
  );

  /** Non-empty supporting text (layout: top-align box with label stack when true). */
  readonly hasHint = computed(() => {
    const h = this.hint();
    return typeof h === 'string' && h.trim().length > 0;
  });

  isDisabled = computed(() => this.disabled() || this.groupDisabled());

  readonly resolvedAriaLabel = computed(() => {
    const label = this.ariaLabel().trim();
    return label.length > 0 ? label : null;
  });

  /** Named control (visible label or `smtAriaLabel`). Unnamed + hide-label is decorative. */
  readonly isNamedCheckbox = computed(() => !this.hideLabel() || this.resolvedAriaLabel() !== null);

  readonly ariaChecked = computed(() => {
    if (this.isHalfChecked()) return 'mixed';
    return this.checked() ? 'true' : 'false';
  });

  readonly labelledById = computed(() => (this.hideLabel() ? null : this.labelId));

  readonly describedById = computed(() => (!this.hideLabel() && this.hasHint() ? this.hintId : null));

  /** Unchecked / disabled box; checked accent comes from {@link variant}. */
  readonly boxColorClasses = computed(() => {
    const isActive = this.checked() || this.isHalfChecked();
    const isSuccess = this.variant() === 'success';

    if (this.isDisabled()) {
      return 'border-gray-300 bg-white';
    }

    if (this.hasError()) {
      return isActive ? 'border-error-500 bg-brand-600' : 'border-error-300 bg-white hover:bg-error-50';
    }

    if (isActive) {
      return isSuccess
        ? 'border-success-600 bg-success-600 hover:border-success-700 hover:bg-success-700'
        : 'border-brand-600 bg-brand-600 hover:border-brand-700 hover:bg-brand-700';
    }

    return 'border-gray-300 bg-white hover:bg-gray-50';
  });

  readonly controlClasses = computed(() => {
    const size = this.size();
    const focusClasses = this.hasError()
      ? 'focus-visible:z-10 focus-visible:border-error-500 focus-visible:shadow-button-destructive-focus focus-visible:ring-2 focus-visible:ring-error-500'
      : this.variant() === 'success'
        ? 'focus-visible:z-10 focus-visible:border-success-500 focus-visible:shadow-button-success-focus focus-visible:ring-2 focus-visible:ring-success-500'
        : 'focus-visible:z-10 focus-visible:border-brand-500 focus-visible:shadow-button-primary-focus focus-visible:ring-2 focus-visible:ring-brand-500';

    return [
      'relative flex shrink-0 appearance-none items-center justify-center rounded border outline-none transition-all duration-200',
      this.boxColorClasses(),
      this.isDisabled() ? 'cursor-not-allowed' : 'cursor-pointer',
      size === 'sm' ? 'size-3.5' : '',
      size === 'md' ? 'size-4' : '',
      size === 'lg' ? 'size-4.5' : '',
      this.hasHint() ? 'mt-0.5' : '',
      !this.isDisabled() ? focusClasses : '',
    ]
      .filter(Boolean)
      .join(' ');
  });

  readonly iconColorClasses = computed(() => {
    if (this.isDisabled()) return 'text-gray-300';
    return 'text-white';
  });

  readonly labelId = `smt-checkbox-${++nextCheckboxUid}-label`;

  readonly hintId = `smt-checkbox-${nextCheckboxUid}-hint`;

  @ViewChild('inputElement', { static: true })
  inputElement!: ElementRef<HTMLInputElement>;

  constructor() {
    injectRegisterSMTIcons([checkboxCheckIcon, checkboxMinusIcon]);
  }

  /** Called by `SMTCheckboxGroupComponent` only. */
  setGroupDisabled(disabled: boolean): void {
    this.groupDisabled.set(disabled);
  }

  markTouched(): void {
    this.touched.set(true);
  }

  toggle(e: Event) {
    if (this.isDisabled()) return;
    if (this.preventDefault()) {
      e.preventDefault();
    }

    this.applyUserToggle();
  }

  handleKeydown(event: KeyboardEvent) {
    if (this.isDisabled()) return;
    const isEnter = event.key === 'Enter' || event.code === 'Enter' || event.keyCode === 13;
    const isSpace = event.key === ' ' || event.code === 'Space' || event.keyCode === 32;

    if (isEnter || isSpace) {
      event.preventDefault();
      this.applyUserToggle();
    }
  }

  /** Half-checked click clears selection; otherwise toggle checked. */
  private nextCheckedValue(): boolean {
    return this.isHalfChecked() ? false : !this.checked();
  }

  private applyUserToggle(): void {
    const next = this.nextCheckedValue();
    if (next !== this.checked()) {
      this.checked.set(next);
    }
    this.checkedUserChange.emit(next);
    this.markTouched();
  }
}
