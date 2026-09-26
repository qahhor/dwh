/* Our code: the ngModel bridge for smt-input (see ../picker-value-accessor.ts).
 * Unlike the pickers it reports every edit, not only a changed value, the way a
 * native field's accessor does: a screen that clears its error on
 * (ngModelChange) hears the person type even when the text ends up the same. */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTInputComponent, SMTInputValue } from './input.component';

@Directive({
  selector: 'smt-input[ngModel], smt-input[formControl], smt-input[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTInputValueAccessor), multi: true }],
})
export class SMTInputValueAccessor extends PickerValueAccessor<SMTInputValue> {
  private readonly input = inject(SMTInputComponent);

  protected readonly picker: BridgedPicker<SMTInputValue> = {
    value: {
      set: value => this.input.value.set(value),
      subscribe: callback => this.input.edited.subscribe(callback),
    },
    touch: this.input.touch,
    setDisabledFromForms: disabled => this.input.setDisabledFromForms(disabled),
  };
}
