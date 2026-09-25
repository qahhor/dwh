/* Our code: the ngModel bridge for smt-tag-group (see ../forms/picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../forms/picker-value-accessor';
import { SMTTagGroupComponent } from './tag.component';

@Directive({
  selector: 'smt-tag-group[ngModel], smt-tag-group[formControl], smt-tag-group[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTTagGroupValueAccessor), multi: true }],
})
export class SMTTagGroupValueAccessor extends PickerValueAccessor<readonly unknown[]> {
  protected override readonly empty: readonly unknown[] = [];

  protected readonly picker = inject(SMTTagGroupComponent) as unknown as BridgedPicker<readonly unknown[]>;
}
