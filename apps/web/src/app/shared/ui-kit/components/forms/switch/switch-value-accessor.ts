/* Our code: the ngModel bridge for smt-switch (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTSwitchComponent } from './switch.component';

@Directive({
  selector: 'smt-switch[ngModel], smt-switch[formControl], smt-switch[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTSwitchValueAccessor), multi: true }],
})
export class SMTSwitchValueAccessor extends PickerValueAccessor<boolean> {
  protected override readonly empty: boolean = false;

  protected readonly picker = this.bridge(inject(SMTSwitchComponent));

  /** The switch's value is `checked`. */
  private bridge(control: SMTSwitchComponent): BridgedPicker<boolean> {
    return { value: control.checked, touch: control.touch, setDisabledFromForms: disabled => control.setDisabledFromForms(disabled) };
  }
}
