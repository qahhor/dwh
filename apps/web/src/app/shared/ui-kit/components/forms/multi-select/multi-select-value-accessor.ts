/* Our code: the thin ControlValueAccessor bridge from the forms decision
 * (variant A) for screens still on `ngModel` or reactive forms. It applies
 * only with those directives, so it never competes with `[formField]`. */
import { Directive, forwardRef, inject } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { SMTMultiSelectComponent } from './multi-select.component';

@Directive({
  selector: 'smt-multi-select[ngModel], smt-multi-select[formControl], smt-multi-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTMultiSelectValueAccessor), multi: true }],
})
export class SMTMultiSelectValueAccessor implements ControlValueAccessor {
  private readonly select = inject(SMTMultiSelectComponent);

  /** True while the form writes a value in, so that write is not reported back as a change. */
  private writing = false;

  writeValue(value: readonly unknown[] | null): void {
    this.writing = true;
    this.select.value.set(value ?? []);
    this.writing = false;
  }

  registerOnChange(onChange: (value: readonly unknown[]) => void): void {
    this.select.value.subscribe(value => {
      if (!this.writing) onChange(value);
    });
  }

  registerOnTouched(onTouched: () => void): void {
    this.select.touch.subscribe(() => onTouched());
  }

  setDisabledState(disabled: boolean): void {
    this.select.setDisabledFromForms(disabled);
  }
}
