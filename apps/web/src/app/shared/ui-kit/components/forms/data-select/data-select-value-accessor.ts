/* Our code: the ngModel bridges for smt-data-select and smt-multi-data-select
 * (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTDataSelectComponent } from './data-select.component';
import { SMTMultiDataSelectComponent } from './multi-data-select.component';

@Directive({
  selector: 'smt-data-select[ngModel], smt-data-select[formControl], smt-data-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTDataSelectValueAccessor), multi: true }],
})
export class SMTDataSelectValueAccessor extends PickerValueAccessor<unknown> {
  protected readonly picker = inject(SMTDataSelectComponent) as unknown as BridgedPicker<unknown>;
}

@Directive({
  selector: 'smt-multi-data-select[ngModel], smt-multi-data-select[formControl], smt-multi-data-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTMultiDataSelectValueAccessor), multi: true }],
})
export class SMTMultiDataSelectValueAccessor extends PickerValueAccessor<readonly unknown[]> {
  protected override readonly empty: readonly unknown[] = [];

  protected readonly picker = inject(SMTMultiDataSelectComponent) as unknown as BridgedPicker<readonly unknown[]>;
}
