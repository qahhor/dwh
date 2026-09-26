/* Our code: the ngModel bridge for smt-checkbox (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTCheckboxComponent } from './checkbox.component';

@Directive({
  selector: '[smt-checkbox][ngModel], [smt-checkbox][formControl], [smt-checkbox][formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTCheckboxValueAccessor), multi: true }],
})
export class SMTCheckboxValueAccessor extends PickerValueAccessor<boolean> {
  protected override readonly empty: boolean = false;

  protected readonly picker = this.bridge(inject(SMTCheckboxComponent));

  /** The checkbox's value is `checked`. */
  private bridge(control: SMTCheckboxComponent<unknown>): BridgedPicker<boolean> {
    return { value: control.checked, touch: control.touch, setDisabledFromForms: disabled => control.setDisabledFromForms(disabled) };
  }
}
