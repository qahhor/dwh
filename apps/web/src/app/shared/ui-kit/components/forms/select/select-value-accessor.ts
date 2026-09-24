/* Our code: the thin ControlValueAccessor bridge from the forms decision
 * (variant A) for screens still on `ngModel` or reactive forms. It applies
 * only with those directives, so it never competes with `[formField]`. */
import { Directive, forwardRef, inject } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { SMTSelectComponent } from './select.component';

@Directive({
  selector: 'smt-select[ngModel], smt-select[formControl], smt-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTSelectValueAccessor), multi: true }],
})
export class SMTSelectValueAccessor implements ControlValueAccessor {
  private readonly select = inject(SMTSelectComponent);

  /** True while the form writes a value in, so that write is not reported back as a change. */
  private writing = false;

  writeValue(value: unknown): void {
    this.writing = true;
    this.select.value.set(value ?? null);
    this.writing = false;
  }

  registerOnChange(onChange: (value: unknown) => void): void {
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
