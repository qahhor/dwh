/* Our code, after the idea of the kit's `smt-color-input` (smartup-ui-kit@6472beb,
 * components/forms/color-input). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it was a text field with a picker swatch and the
 * kit's own validation messages; a colour chosen for a status or a task
 * type is better picked from a named palette, so it reads to everyone —
 * a screen reader too — as "Blue", not "#2563eb".
 *
 * A palette of named colours as a radio group, then "Own colour": the
 * system colour picker and the hex code, for a colour outside the palette.
 * The value is `#rrggbb`; a code that is not a colour is said under the field
 * and not written. Colours picked here are data (icons, marks), not text on
 * a background, so their contrast is the caller's concern.
 *
 * <smt-control [smtLabel]="…"><smt-color-input [(ngModel)]="type.color" name="color" /></smt-control> */
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import type { FormValueControl } from '@angular/forms/signals';
import { SMTI18nService } from '../../../i18n';
import { SMTRadioGroupComponent, type SMTRadioOption } from '../radio-group/radio-group.component';

/** The palette: distinct hues that stay apart for common colour blindness in pairs. */
export const SMT_COLOR_SWATCHES = [
  { key: 'blue', value: '#2563eb' },
  { key: 'sky', value: '#0284c7' },
  { key: 'teal', value: '#0d9488' },
  { key: 'green', value: '#16a34a' },
  { key: 'amber', value: '#d97706' },
  { key: 'orange', value: '#ea580c' },
  { key: 'red', value: '#dc2626' },
  { key: 'pink', value: '#db2777' },
  { key: 'violet', value: '#7c3aed' },
  { key: 'slate', value: '#475569' },
] as const;

export type SMTColorKey = (typeof SMT_COLOR_SWATCHES)[number]['key'];

const HEX = /^#?([0-9a-f]{6}|[0-9a-f]{3})$/i;

/** `#abc` / `ABCDEF` → `#aabbcc` / `#abcdef`, or null when it is not a colour. */
export function normalizeHex(text: string): string | null {
  const match = HEX.exec(text.trim());
  if (!match) return null;
  const hex = match[1].length === 3 ? [...match[1]].map(char => char + char).join('') : match[1];
  return `#${hex.toLowerCase()}`;
}

let nextColorId = 0;

@Component({
  selector: 'smt-color-input',
  standalone: true,
  imports: [SMTRadioGroupComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './color-input.scss',
  host: { class: 'smt-color-input' },
  template: `
    <smt-radio-group
      smtAppearance="chips"
      [smtFieldId]="customId() || fieldId"
      [options]="swatches()"
      [value]="chosenSwatch()"
      [disabled]="isDisabled()"
      (valueChange)="choose($event)" />
    <div class="smt-color-input__own">
      <input
        type="color"
        class="smt-color-input__picker"
        data-smt-field-part
        [attr.aria-label]="i18n.messages().color.own"
        [disabled]="isDisabled()"
        [value]="value() || '#000000'"
        (input)="choose(asInput($event).value)" />
      <input
        type="text"
        class="smt-color-input__hex"
        data-smt-field-part
        spellcheck="false"
        [attr.aria-label]="i18n.messages().color.code"
        [attr.aria-describedby]="problem() ? problemId : null"
        [attr.aria-invalid]="problem() ? 'true' : null"
        [disabled]="isDisabled()"
        [value]="draft()"
        (input)="draft.set(asInput($event).value); problem.set(false)"
        (blur)="commit()"
        (keydown.enter)="commit()" />
    </div>
    @if (problem()) {
      <p class="smt-color-input__problem" [id]="problemId">{{ i18n.messages().color.invalid }}</p>
    }
  `,
})
export class SMTColorInputComponent implements FormValueControl<string> {
  readonly i18n = inject(SMTI18nService);

  /** The palette group's id, for an outside label. */
  readonly customId = input<string | null>(null, { alias: 'smtFieldId' });

  readonly disabled = input(false, { transform: booleanAttribute });

  /** Tells Signal Forms the person has left the field. */
  readonly touch = output<void>();

  /** `#rrggbb`, or '' for none. */
  readonly value = model<string>('');

  /** The hex field's text until it is read; shows the value again whenever the value changes. */
  readonly draft = linkedSignal(() => this.value() ?? '');

  /** A typed code that is not a colour; cleared when the value changes. */
  readonly problem = linkedSignal({ source: () => this.value(), computation: () => false });

  /** Disabled by a reactive form or ngModel through the value accessor. */
  private readonly formsDisabled = signal(false);

  readonly isDisabled = computed(() => this.disabled() || this.formsDisabled());

  readonly swatches = computed<SMTRadioOption<string>[]>(() => {
    const names = this.i18n.messages().color.names;
    return SMT_COLOR_SWATCHES.map(swatch => ({ value: swatch.value, label: names[swatch.key], color: swatch.value }));
  });

  /** The palette entry of the value, or null for an own colour. */
  readonly chosenSwatch = computed(() => {
    const value = (this.value() ?? '').toLowerCase();
    return SMT_COLOR_SWATCHES.some(swatch => swatch.value === value) ? value : null;
  });

  readonly fieldId = `smt-color-input-${nextColorId++}`;

  readonly problemId = `${this.fieldId}-problem`;

  choose(color: string | null): void {
    if (this.isDisabled() || !color) return;
    const normalized = normalizeHex(color);
    if (!normalized) return;
    this.value.set(normalized);
    this.touch.emit();
  }

  /** Reads the hex field: a colour is written, anything else is said and left unwritten. */
  commit(): void {
    const text = this.draft().trim();
    if (!text) return;
    const normalized = normalizeHex(text);
    if (!normalized) {
      this.problem.set(true);
      return;
    }
    this.problem.set(false);
    this.choose(normalized);
  }

  asInput(event: Event): HTMLInputElement {
    return event.target as HTMLInputElement;
  }

  /** Called by SMTColorInputValueAccessor. */
  setDisabledFromForms(disabled: boolean): void {
    this.formsDisabled.set(disabled);
  }
}
