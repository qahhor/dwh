import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { STUDIO_LANGUAGES, type StudioLabels, type StudioLanguage } from './form-studio.models';

@Component({
  selector: 'app-form-studio-labels',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTControlComponent, SMTInputComponent],
  template: `
    @for (language of languages; track language) {
      <smt-control [smtLabel]="languageKeys[language] | t">
        <smt-input
          [value]="labels()?.[language] ?? ''"
          [maxLength]="240"
          [disabled]="disabled()"
          [placeholder]="'settings.form_studio.inherited' | t"
          (valueChange)="changed.emit({ language, value: $event?.toString() ?? '' })"
        />
      </smt-control>
    }
  `,
  styles: [
    `
      :host {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 8px;
        min-width: 0;
      }
      @media (max-width: 480px) {
        :host {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class FormStudioLabelsComponent {
  readonly labels = input<StudioLabels>();
  readonly disabled = input(false);
  readonly changed = output<{ language: StudioLanguage; value: string }>();
  readonly languages = STUDIO_LANGUAGES;
  readonly languageKeys: Readonly<Record<StudioLanguage, string>> = {
    ru: 'settings.form_studio.labels.ru',
    uz: 'settings.form_studio.labels.uz',
    en: 'settings.form_studio.labels.en',
  };
}
