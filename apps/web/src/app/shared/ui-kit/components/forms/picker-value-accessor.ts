/* Our code: the base of the thin ControlValueAccessor bridges from the forms
 * decision (variant A). New screens bind the pickers with Signal Forms
 * `[formField]`; screens still on `ngModel` or reactive forms go through a
 * directive built on this, which applies only when one of those directives
 * is present, so it never competes with `[formField]`. */
import { DestroyRef, inject } from '@angular/core';
import { ControlValueAccessor } from '@angular/forms';

/** What a bridged picker exposes: its value model, touch output and a forms-driven disabled flag. */
export interface BridgedPicker<T> {
  value: { set(value: T): void; subscribe(callback: (value: T) => void): { unsubscribe(): void } };
  touch: { subscribe(callback: () => void): { unsubscribe(): void } };
  setDisabledFromForms(disabled: boolean): void;
}

export abstract class PickerValueAccessor<T> implements ControlValueAccessor {
  protected abstract readonly picker: BridgedPicker<T>;

  /** What the form's `null` means for this picker (an empty list for multi-select). */
  protected readonly empty: T | null = null;

  /** True while the form writes a value in, so that write is not reported back as a change. */
  private writing = false;

  /**
   * Template-driven NgModel registers its callbacks in a microtask. If the
   * picker is destroyed before that (a dialog opened and closed at once),
   * subscribing to its outputs would throw NG0953, so late calls are ignored.
   */
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => (this.destroyed = true));
  }

  writeValue(value: T | null): void {
    if (this.destroyed) return;
    this.writing = true;
    this.picker.value.set((value ?? this.empty) as T);
    this.writing = false;
  }

  registerOnChange(onChange: (value: T) => void): void {
    if (this.destroyed) return;
    this.picker.value.subscribe(value => {
      if (!this.writing) onChange(value);
    });
  }

  registerOnTouched(onTouched: () => void): void {
    if (this.destroyed) return;
    this.picker.touch.subscribe(() => onTouched());
  }

  setDisabledState(disabled: boolean): void {
    if (this.destroyed) return;
    this.picker.setDisabledFromForms(disabled);
  }
}
