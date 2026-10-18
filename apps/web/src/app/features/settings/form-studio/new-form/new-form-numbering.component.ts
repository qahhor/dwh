import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import type { SimpleFormNumbering, SimpleFormState } from './new-form.models';

/** The number a pattern gives the first document, as the server writes it: the year, the month, the counter. */
export function numberExample(pattern: string, day = new Date()): string {
  const pad = (value: number, digits: number) => String(value).padStart(digits, '0');
  return pattern.replace(/\{(YYYY|YY|MM|N{1,12})\}/g, (_token, part: string) => {
    if (part === 'YYYY') return pad(day.getFullYear(), 4);
    if (part === 'YY') return pad(day.getFullYear() % 100, 2);
    if (part === 'MM') return pad(day.getMonth() + 1, 2);
    return pad(1, part.length);
  });
}

/**
 * The number of a live form's documents (ADR-0043): a pattern of fixed text and the tokens `{YYYY}`, `{YY}`, `{MM}`
 * and one counter `{N...}`, the period the counter starts again in and, when the form has statuses, the status whose
 * first entry gives the number instead of the creation. The server gives each number once, in turn, unique within the
 * form; a stored pattern and period never change (`locked`).
 */
@Component({
  selector: 'app-new-form-numbering',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTCheckboxComponent, SMTControlComponent, SMTInputComponent, SMTSelectComponent],
  template: `
    <fieldset class="form-numbering" data-testid="form-numbering">
      <legend>{{ 'studio.numbering.title' | t }}</legend>
      <div
        smt-checkbox
        data-testid="numbering-on"
        [checked]="!!numbering()"
        [disabled]="disabled() || locked()"
        (checkedChange)="toggle($event)"
      >
        {{ 'studio.numbering.enabled' | t }}
      </div>
      @if (numbering(); as current) {
        <smt-control
          [smtLabel]="'studio.numbering.pattern' | t"
          [required]="true"
          [smtHint]="
            (locked() ? 'studio.numbering.locked' : 'studio.numbering.pattern_hint') | t: { example: example() }
          "
        >
          <smt-input
            [value]="current.pattern"
            [maxLength]="60"
            [disabled]="disabled() || locked()"
            data-testid="numbering-pattern"
            (valueChange)="patch({ pattern: text($event) })"
          />
        </smt-control>
        <smt-control [smtLabel]="'studio.numbering.reset' | t">
          <smt-select
            [options]="resetOptions()"
            [value]="current.reset"
            [allowClear]="false"
            [disabled]="disabled() || locked()"
            data-testid="numbering-reset"
            (valueChange)="setReset($event)"
          />
        </smt-control>
        @if (states().length) {
          <smt-control [smtLabel]="'studio.numbering.assign_on' | t" [smtHint]="'studio.numbering.assign_on_hint' | t">
            <smt-select
              [options]="assignOptions()"
              [value]="current.assignOn ?? ''"
              [allowClear]="false"
              [disabled]="disabled()"
              data-testid="numbering-assign-on"
              (valueChange)="setAssignOn($event)"
            />
          </smt-control>
        }
      }
    </fieldset>
  `,
  styles: [':host { display: block; min-width: 0; }', '.form-numbering { display: grid; gap: 12px; min-width: 0; }'],
})
export class NewFormNumberingComponent {
  private readonly i18n = inject(I18nService);
  readonly numbering = input<SimpleFormNumbering | undefined>(undefined);
  /** The statuses of the form: one of them may give the number. */
  readonly states = input<readonly SimpleFormState[]>([]);
  readonly disabled = input(false);
  /** The active version numbers its documents: the pattern and period stay. */
  readonly locked = input(false);
  readonly changed = output<SimpleFormNumbering | undefined>();
  readonly example = computed(() => numberExample(this.numbering()?.pattern ?? ''));
  readonly resetOptions = computed(() => {
    this.i18n.currentLang();
    return (['never', 'year', 'month'] as const).map((id) => ({
      id,
      label: this.i18n.translate(`studio.numbering.reset_${id}`),
    }));
  });
  readonly assignOptions = computed(() => {
    const language = this.i18n.currentLang() as 'ru' | 'uz' | 'en';
    return [
      { id: '', label: this.i18n.translate('studio.numbering.on_create') },
      ...this.states().map((state) => ({ id: state.code, label: state.labels[language] || state.code })),
    ];
  });

  toggle(on: boolean): void {
    if (this.locked()) return;
    this.changed.emit(on ? { pattern: 'DOC-{YYYY}-{NNNNNN}', reset: 'year' } : undefined);
  }

  patch(change: Partial<SimpleFormNumbering>): void {
    const current = this.numbering();
    if (current) this.changed.emit({ ...current, ...change });
  }

  setReset(value: unknown): void {
    if (value === 'never' || value === 'year' || value === 'month') this.patch({ reset: value });
  }

  setAssignOn(value: unknown): void {
    const current = this.numbering();
    if (!current) return;
    const next = { ...current };
    if (typeof value === 'string' && value) next.assignOn = value;
    else delete next.assignOn;
    this.changed.emit(next);
  }

  text(value: unknown): string {
    return value === null || value === undefined ? '' : String(value);
  }
}
