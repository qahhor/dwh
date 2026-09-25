/* Our code: the ngModel bridge for smt-color-input (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTColorInputComponent } from './color-input.component';

@Directive({
  selector: 'smt-color-input[ngModel], smt-color-input[formControl], smt-color-input[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTColorInputValueAccessor), multi: true }],
})
export class SMTColorInputValueAccessor extends PickerValueAccessor<string> {
  protected override readonly empty: string = '';

  protected readonly picker = inject(SMTColorInputComponent) as unknown as BridgedPicker<string>;
}
