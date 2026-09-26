/* Our code: the ngModel bridge for smt-weekday-toggle (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTWeekday, SMTWeekdayToggleComponent } from './weekday-toggle.component';

@Directive({
  selector: 'smt-weekday-toggle[ngModel], smt-weekday-toggle[formControl], smt-weekday-toggle[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTWeekdayToggleValueAccessor), multi: true }],
})
export class SMTWeekdayToggleValueAccessor extends PickerValueAccessor<readonly SMTWeekday[]> {
  protected override readonly empty: readonly SMTWeekday[] = [];

  protected readonly picker = inject(SMTWeekdayToggleComponent) as unknown as BridgedPicker<readonly SMTWeekday[]>;
}
