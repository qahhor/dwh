/* Our code: the ngModel bridge for smt-rating (see ../picker-value-accessor.ts). */
import { Directive, forwardRef, inject } from '@angular/core';
import { NG_VALUE_ACCESSOR } from '@angular/forms';
import { BridgedPicker, PickerValueAccessor } from '../picker-value-accessor';
import { SMTRatingComponent } from './rating.component';

@Directive({
  selector: 'smt-rating[ngModel], smt-rating[formControl], smt-rating[formControlName]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SMTRatingValueAccessor), multi: true }],
})
export class SMTRatingValueAccessor extends PickerValueAccessor<number | null> {
  protected readonly picker = inject(SMTRatingComponent) as unknown as BridgedPicker<number | null>;
}
