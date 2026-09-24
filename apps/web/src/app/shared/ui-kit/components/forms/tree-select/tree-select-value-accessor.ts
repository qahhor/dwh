/* Our code: the ngModel bridge for smt-tree-select (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTTreeSelectComponent } from './tree-select.component';

@Directive({
  selector: 'smt-tree-select[ngModel], smt-tree-select[formControl], smt-tree-select[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTTreeSelectValueAccessor), multi: true }],
})
export class SMTTreeSelectValueAccessor extends PickerValueAccessor<unknown> {
  protected readonly picker = inject(SMTTreeSelectComponent) as unknown as BridgedPicker<unknown>;
}
