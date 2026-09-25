/* Our code, after the idea of the kit's `smt-dynamic-field` (smartup-ui-kit@6472beb,
 * components/forms/dynamic-field). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it switched over the kit's own field descriptors
 * and controls wired to its ControlService; ours are the kit fields this
 * application already carries.
 *
 * One field described as data — its code, label, type, whether it is
 * required, its options — drawn with the matching field inside smt-control,
 * so every type gets the same label, required mark and error: text, long
 * text, number, yes/no (a switch), date, date and time, time, a choice from
 * a list, and a person, searched on the server through the lookup source
 * the caller gives. This is where screens built from configuration — custom
 * fields today, the form designer later — get their fields. */
import { booleanAttribute, ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SMTControlComponent } from '../control/control.component';
import { SMTDataSelectComponent } from '../data-select/data-select.component';
import type { SMTLookupSource } from '../data-select/lookup-source';
import { SMTDatePickerComponent } from '../date-picker/date-picker.component';
import { SMTDatePickerValueAccessor } from '../date-picker/date-picker-value-accessor';
import { SMTSelectComponent, type SMTSelectOption } from '../select/select.component';
import { SMTSwitchComponent } from '../switch/switch.component';
import { SMTTextareaComponent } from '../textarea/textarea.component';
import { SMTTimePickerComponent } from '../time-picker/time-picker.component';

export type SMTDynamicFieldType = 'string' | 'text' | 'number' | 'boolean' | 'date' | 'datetime' | 'time' | 'select' | 'user_ref';

export interface SMTDynamicFieldDef {
  readonly code: string;
  readonly label: string;
  readonly type: SMTDynamicFieldType;
  readonly required?: boolean;
  readonly placeholder?: string;
  readonly hint?: string;
  /** The choices of a `select`. */
  readonly options?: readonly SMTSelectOption<string | number | boolean>[];
}

let nextFieldId = 0;

@Component({
  selector: 'smt-dynamic-field',
  standalone: true,
  imports: [
    FormsModule,
    SMTControlComponent,
    SMTDataSelectComponent,
    SMTDatePickerComponent,
    SMTDatePickerValueAccessor,
    SMTSelectComponent,
    SMTSwitchComponent,
    SMTTextareaComponent,
    SMTTimePickerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'smt-dynamic-field' },
  template: `
    <smt-control [smtLabel]="field().label" [smtHint]="field().hint ?? ''" [required]="!!field().required">
      @switch (field().type) {
        @case ('text') {
          <smt-textarea
            [smtFieldId]="fieldId"
            [name]="field().code"
            [placeholder]="field().placeholder ?? ''"
            [required]="!!field().required"
            [disabled]="disabled()"
            [value]="text()"
            (valueChange)="value.set($event)" />
        }
        @case ('number') {
          <input
            class="form-input"
            type="number"
            [id]="fieldId"
            [name]="field().code"
            [placeholder]="field().placeholder ?? ''"
            [required]="!!field().required"
            [disabled]="disabled()"
            [value]="text()"
            (input)="setNumber($event)" />
        }
        @case ('boolean') {
          <smt-switch [smtFieldId]="fieldId" [disabled]="disabled()" [checked]="checked()" (smtUserChange)="value.set($event)" />
        }
        @case ('date') {
          <smt-date-picker
            [smtInputId]="fieldId"
            [name]="field().code"
            [required]="!!field().required"
            [disabled]="disabled()"
            [ngModel]="value() || null"
            (ngModelChange)="value.set($event ?? '')" />
        }
        @case ('datetime') {
          <smt-date-picker
            smtWithTime
            [smtInputId]="fieldId"
            [name]="field().code"
            [required]="!!field().required"
            [disabled]="disabled()"
            [ngModel]="value() || null"
            (ngModelChange)="value.set($event ?? '')" />
        }
        @case ('time') {
          <smt-time-picker
            [smtFieldId]="fieldId"
            [name]="field().code"
            [required]="!!field().required"
            [disabled]="disabled()"
            [value]="timeValue()"
            (valueChange)="value.set($event)" />
        }
        @case ('select') {
          <smt-select
            [smtTriggerId]="fieldId"
            [options]="field().options ?? []"
            [required]="!!field().required"
            [disabled]="disabled()"
            [placeholder]="field().placeholder ?? ''"
            [value]="selectValue()"
            (valueChange)="value.set($event)" />
        }
        @case ('user_ref') {
          @if (userSource(); as source) {
            <smt-data-select
              [smtTriggerId]="fieldId"
              [source]="source"
              [required]="!!field().required"
              [disabled]="disabled()"
              [placeholder]="field().placeholder ?? ''"
              [value]="userValue()"
              (valueChange)="value.set($event)" />
          }
        }
        @default {
          <input
            class="form-input"
            type="text"
            [id]="fieldId"
            [name]="field().code"
            [placeholder]="field().placeholder ?? ''"
            [required]="!!field().required"
            [disabled]="disabled()"
            [value]="text()"
            (input)="setText($event)" />
        }
      }
    </smt-control>
  `,
  styles: [':host { display: block; min-width: 0; }'],
})
export class SMTDynamicFieldComponent {
  readonly field = input.required<SMTDynamicFieldDef>();

  /** Where a person is searched for; needed by a `user_ref` field. */
  readonly userSource = input<SMTLookupSource<{ id: number }, number> | null>(null);

  readonly disabled = input(false, { transform: booleanAttribute });

  /** The value as the record stores it: text, number, true/false, `yyyy-MM-dd`, `HH:mm` or an id. */
  readonly value = model<unknown>(null);

  readonly text = computed(() => {
    const value = this.value();
    return value === null || value === undefined ? '' : String(value);
  });

  /** A stored "true" counts as yes, as the API has sent it both ways. */
  readonly checked = computed(() => this.value() === true || this.value() === 'true');

  readonly timeValue = computed(() => (typeof this.value() === 'string' && this.value() ? (this.value() as string) : null));

  readonly selectValue = computed(() => {
    const value = this.value();
    return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? value : null;
  });

  readonly userValue = computed(() => {
    const id = Number(this.value());
    return this.value() !== null && this.value() !== '' && Number.isSafeInteger(id) && id > 0 ? id : null;
  });

  readonly fieldId = `smt-dynamic-field-${nextFieldId++}`;

  setText(event: Event): void {
    this.value.set((event.target as HTMLInputElement).value);
  }

  /** An empty box is no value, not zero. */
  setNumber(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.value.set(raw === '' ? null : Number(raw));
  }
}
