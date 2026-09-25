/* Our code: the ngModel bridge for smt-textarea (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTTextareaComponent } from './textarea.component';

@Directive({
  selector: 'smt-textarea[ngModel], smt-textarea[formControl], smt-textarea[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTTextareaValueAccessor), multi: true }],
})
export class SMTTextareaValueAccessor extends PickerValueAccessor<string> {
  protected override readonly empty: string = '';

  protected readonly picker = inject(SMTTextareaComponent) as unknown as BridgedPicker<string>;
}
