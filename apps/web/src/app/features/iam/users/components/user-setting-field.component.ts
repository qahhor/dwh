import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import type { FormFieldMeta } from '@core/models/form-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';

/** The time zones the account form offers, as the user screens offered them; another stored zone is kept. */
const TIME_ZONES: readonly SMTSelectOption<string>[] = [
  { id: 'Asia/Tashkent', label: 'Asia/Tashkent (UTC+5)' },
  { id: 'Asia/Samarkand', label: 'Asia/Samarkand (UTC+5)' },
  { id: 'Asia/Almaty', label: 'Asia/Almaty (UTC+5)' },
  { id: 'Asia/Dubai', label: 'Asia/Dubai (UTC+4)' },
  { id: 'Europe/Moscow', label: 'Europe/Moscow (UTC+3)' },
  { id: 'UTC', label: 'UTC (UTC+0)' },
];

/**
 * The language or the time zone of a user account on the general form (ADR-0032 7.2, `provideEntityOverrides`): a
 * choice of the system's languages or of the usual zones instead of a text box, so the value is one the server takes.
 * The server checks the value either way.
 */
@Component({
  selector: 'app-user-setting-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTControlComponent, SMTSelectComponent, TranslatePipe],
  template: `
    <smt-control [smtLabel]="field().label || (field().labelKey | t)" [smtError]="problem()">
      <smt-select
        [smtTriggerId]="'user-setting-' + field().key"
        [options]="options()"
        [value]="text()"
        [disabled]="disabled()"
        [allowClear]="false"
        (valueChange)="choose($event)"
      />
    </smt-control>
  `,
})
export class UserSettingFieldComponent {
  private readonly i18n = inject(I18nService);

  readonly field = input.required<FormFieldMeta>();
  readonly set = input.required<(value: unknown) => void>();
  readonly value = input<unknown>(null);
  readonly problem = input('');
  readonly disabled = input(false);

  readonly text = computed(() => (typeof this.value() === 'string' ? (this.value() as string) : null));

  /** The choices of the field, with the stored value kept when it is not among them. */
  readonly options = computed<SMTSelectOption<string>[]>(() => {
    const choices: SMTSelectOption<string>[] =
      this.field().key === 'language'
        ? this.i18n
            .languages()
            .filter((language) => language.active)
            .map((language) => ({ id: language.code, label: `${language.name} (${language.code})` }))
        : [...TIME_ZONES];
    const current = this.text();
    return current && !choices.some((choice) => choice.id === current)
      ? [...choices, { id: current, label: current }]
      : choices;
  });

  choose(value: string | null): void {
    if (value !== null) this.set()(value);
  }
}
