import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { STUDIO_LANGUAGES, type StudioLabels, type StudioLanguage } from '../form-studio.models';
@Component({
  selector: 'app-new-form-labels',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTControlComponent, SMTInputComponent],
  template: `@for (language of languages; track language) {
    <smt-control [smtLabel]="keys[language] | t" [required]="true">
      <smt-input
        [value]="labels()[language]"
        [disabled]="disabled()"
        [maxLength]="160"
        (valueChange)="changed.emit({ language, value: $event === null ? '' : '' + $event })"
      />
    </smt-control>
  }`,
  styles: [':host { display: grid; gap: 12px; min-width: 0; }'],
})
export class NewFormLabelsComponent {
  readonly labels = input.required<StudioLabels>();
  readonly disabled = input(false);
  readonly changed = output<{ language: StudioLanguage; value: string }>();
  readonly languages = STUDIO_LANGUAGES;
  readonly keys: Record<StudioLanguage, string> = {
    ru: 'settings.form_studio.labels.ru',
    uz: 'settings.form_studio.labels.uz',
    en: 'settings.form_studio.labels.en',
  };
}
