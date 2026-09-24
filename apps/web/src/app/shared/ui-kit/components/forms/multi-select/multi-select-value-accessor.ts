/* Our code: the ngModel bridge for smt-multi-select (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTMultiSelectComponent } from './multi-select.component';

@Directive({
  selector: 'smt-multi-select[ngModel], smt-multi-select[formControl], smt-multi-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTMultiSelectValueAccessor), multi: true }],
})
export class SMTMultiSelectValueAccessor extends PickerValueAccessor<readonly unknown[]> {
  protected readonly picker = inject(SMTMultiSelectComponent) as unknown as BridgedPicker<readonly unknown[]>;

  protected override readonly empty: readonly unknown[] = [];
}
