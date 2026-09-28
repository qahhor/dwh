import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

const TIMEZONES: readonly SMTSelectOption<string>[] = [
  { id: 'Asia/Tashkent', label: 'Asia/Tashkent (UTC+5)' },
  { id: 'Asia/Samarkand', label: 'Asia/Samarkand (UTC+5)' },
  { id: 'Asia/Almaty', label: 'Asia/Almaty (UTC+5)' },
  { id: 'Asia/Bishkek', label: 'Asia/Bishkek (UTC+6)' },
  { id: 'Asia/Dushanbe', label: 'Asia/Dushanbe (UTC+5)' },
  { id: 'Asia/Ashgabat', label: 'Asia/Ashgabat (UTC+5)' },
  { id: 'Asia/Baku', label: 'Asia/Baku (UTC+4)' },
  { id: 'Europe/Moscow', label: 'Europe/Moscow (UTC+3)' },
  { id: 'Europe/Istanbul', label: 'Europe/Istanbul (UTC+3)' },
  { id: 'Europe/Berlin', label: 'Europe/Berlin (UTC+1)' },
  { id: 'Europe/London', label: 'Europe/London (UTC+0)' },
  { id: 'UTC', label: 'UTC (GMT+0)' },
];

const DATE_FORMATS: readonly SMTSelectOption<string>[] = [
  { id: 'dd.MM.yyyy HH:mm', label: '29.08.2026 14:30 (dd.MM.yyyy HH:mm)' },
  { id: 'yyyy-MM-dd HH:mm', label: '2026-08-29 14:30 (yyyy-MM-dd HH:mm)' },
  { id: 'MM/dd/yyyy hh:mm a', label: '08/29/2026 02:30 PM (MM/dd/yyyy)' },
  { id: 'dd.MM.yyyy', label: '29.08.2026 (dd.MM.yyyy)' },
  { id: 'dd/MM/yyyy HH:mm', label: '29/08/2026 14:30 (dd/MM/yyyy HH:mm)' },
];

/** The known choices, plus the stored value as its own choice when it is none of them. */
function withCurrent(
  known: readonly SMTSelectOption<string>[],
  current: string | undefined,
): readonly SMTSelectOption<string>[] {
  return current && !known.some((option) => option.id === current)
    ? [...known, { id: current, label: current }]
    : known;
}

@Component({
  selector: 'app-settings-general-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
  ],
  template: `
    <div class="settings-card">
      <div class="card-header-bar">
        <div class="card-title-group">
          <span class="material-symbols-outlined card-icon" aria-hidden="true">corporate_fare</span>
          <div>
            <h3 class="card-title">{{ 'settings.konfiguraciya_kompanii' | t }}</h3>
            <p class="card-desc">{{ 'settings.globalnye_parametry_dlya_vseh_sotrudnikov_organi' | t }}</p>
          </div>
        </div>
        @if (!canUpdateSystemSettings()) {
          <span class="badge badge-neutral">{{ 'settings.readonly_badge' | t }}</span>
        }
      </div>

      <div class="form-grid">
        <div class="form-group full-width">
          <label class="form-label" for="settings-company-name">{{ 'settings.company_name' | t }}</label>
          <smt-input
            smtFieldId="settings-company-name"
            name="settingsCompanyName"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [(ngModel)]="systemSettings()['system.company_name']"
            placeholder="SmartupCMS"
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-default-language">{{ 'settings.default_language' | t }}</label>
          <smt-select
            smtTriggerId="settings-default-language"
            name="settingsDefaultLanguage"
            [options]="languageOptions()"
            [allowClear]="false"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [(ngModel)]="systemSettings()['system.default_language']"
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-default-timezone">{{ 'settings.default_timezone' | t }}</label>
          <smt-select
            smtTriggerId="settings-default-timezone"
            name="settingsDefaultTimezone"
            [options]="timezoneOptions()"
            [allowClear]="false"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [(ngModel)]="systemSettings()['system.default_timezone']"
          />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-date-format">{{ 'settings.date_format' | t }}</label>
          <smt-select
            smtTriggerId="settings-date-format"
            name="settingsDateFormat"
            [options]="dateFormatOptions()"
            [allowClear]="false"
            [disabled]="!canUpdateSystemSettings() || isSaving()"
            [(ngModel)]="systemSettings()['system.date_format']"
          />
        </div>
      </div>

      @if (canUpdateSystemSettings()) {
        <div class="card-footer-actions">
          <button smt-button type="button" [smtLoading]="isSaving()" (click)="save.emit()">
            {{ 'common.save' | t }}
          </button>
        </div>
      }
    </div>
  `,
  styleUrl: './settings-general-panel.component.css',
})
export class SettingsGeneralPanelComponent {
  readonly canUpdateSystemSettings = input(false);
  readonly isSaving = input(false);
  readonly languages = input<
    Array<{
      code: string;
      name: string;
    }>
  >([]);

  readonly systemSettings = input<Record<string, string>>({});

  readonly save = output<void>();

  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly timezoneMemo = optionsMemo<readonly SMTSelectOption<string>[]>();

  private readonly dateFormatMemo = optionsMemo<readonly SMTSelectOption<string>[]>();

  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages()], () =>
      this.languages().map((lang) => ({ id: lang.code, label: `${lang.name} (${lang.code.toUpperCase()})` })),
    );
  }

  timezoneOptions(): readonly SMTSelectOption<string>[] {
    const current = this.systemSettings()['system.default_timezone'];
    return this.timezoneMemo([current], () => withCurrent(TIMEZONES, current));
  }

  dateFormatOptions(): readonly SMTSelectOption<string>[] {
    const current = this.systemSettings()['system.date_format'];
    return this.dateFormatMemo([current], () => withCurrent(DATE_FORMATS, current));
  }
}
