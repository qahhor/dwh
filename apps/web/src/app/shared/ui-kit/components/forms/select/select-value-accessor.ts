/* Our code: the ngModel bridge for smt-select (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTSelectComponent } from './select.component';

@Directive({
  selector: 'smt-select[ngModel], smt-select[formControl], smt-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTSelectValueAccessor), multi: true }],
})
export class SMTSelectValueAccessor extends PickerValueAccessor<unknown> {
  protected readonly picker = inject(SMTSelectComponent) as unknown as BridgedPicker<unknown>;
}
