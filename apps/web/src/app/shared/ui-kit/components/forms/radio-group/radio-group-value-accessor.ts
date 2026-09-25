/* Our code: the ngModel bridge for smt-radio-group (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTRadioGroupComponent } from './radio-group.component';

@Directive({
  selector: 'smt-radio-group[ngModel], smt-radio-group[formControl], smt-radio-group[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTRadioGroupValueAccessor), multi: true }],
})
export class SMTRadioGroupValueAccessor extends PickerValueAccessor<unknown> {
  protected override readonly empty: unknown = null;

  protected readonly picker = inject(SMTRadioGroupComponent) as unknown as BridgedPicker<unknown>;
}
