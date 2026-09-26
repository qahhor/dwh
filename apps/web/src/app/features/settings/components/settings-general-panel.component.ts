import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group/radio-options';

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
function withCurrent(known: readonly SMTSelectOption<string>[], current: string | undefined): readonly SMTSelectOption<string>[] {
  return current && !known.some(option => option.id === current) ? [...known, { id: current, label: current }] : known;
}

@Component({
  selector: 'app-settings-general-panel',
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, SMTSelectComponent, SMTSelectValueAccessor,
    CommonModule,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent
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
        <span class="badge badge-neutral" *ngIf="!canUpdateSystemSettings">{{ 'settings.readonly_badge' | t }}</span>
      </div>

      <div class="form-grid">
        <div class="form-group full-width">
          <label class="form-label" for="settings-company-name">{{ 'settings.company_name' | t }}</label>
          <smt-input
            smtFieldId="settings-company-name"
            name="settingsCompanyName"
            [disabled]="!canUpdateSystemSettings || isSaving"
            [(ngModel)]="systemSettings['system.company_name']"
            placeholder="SmartupCMS" />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-default-language">{{ 'settings.default_language' | t }}</label>
          <smt-select smtTriggerId="settings-default-language" name="settingsDefaultLanguage" [options]="languageOptions()" [allowClear]="false"
            [disabled]="!canUpdateSystemSettings || isSaving" [(ngModel)]="systemSettings['system.default_language']" />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-default-timezone">{{ 'settings.default_timezone' | t }}</label>
          <smt-select smtTriggerId="settings-default-timezone" name="settingsDefaultTimezone" [options]="timezoneOptions()" [allowClear]="false"
            [disabled]="!canUpdateSystemSettings || isSaving" [(ngModel)]="systemSettings['system.default_timezone']" />
        </div>

        <div class="form-group">
          <label class="form-label" for="settings-date-format">{{ 'settings.date_format' | t }}</label>
          <smt-select smtTriggerId="settings-date-format" name="settingsDateFormat" [options]="dateFormatOptions()" [allowClear]="false"
            [disabled]="!canUpdateSystemSettings || isSaving" [(ngModel)]="systemSettings['system.date_format']" />
        </div>
      </div>

      <div class="card-footer-actions" *ngIf="canUpdateSystemSettings">
        <button smt-button type="button" [smtLoading]="isSaving" (click)="save.emit()">
          {{ 'common.save' | t }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .settings-card {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    .card-header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-subtle);
      padding-bottom: 16px;
    }
    .card-title-group {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .card-icon {
      font-size: 28px;
      color: var(--primary-text);
    }
    .card-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .card-desc {
      font-size: 13px;
      color: var(--text-light);
      margin: 2px 0 0 0;
    }
    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 18px;
    }
    @media (max-width: 768px) {
      .form-grid {
        grid-template-columns: 1fr;
      }
    }
    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .form-group.full-width {
      grid-column: span 2;
    }
    @media (max-width: 768px) {
      .form-group.full-width {
        grid-column: span 1;
      }
    }
    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }
    .form-input {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 9px 12px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }
    .form-input:focus {
      border-color: var(--primary);
    }
    .card-footer-actions {
      display: flex;
      justify-content: flex-end;
      padding-top: 12px;
      border-top: 1px solid var(--border-subtle);
    }
    .badge-neutral {
      background-color: var(--bg-active);
      color: var(--text-muted);
    }
    .badge {
      display: inline-flex;
      align-items: center;
      padding: 3px 8px;
      font-size: 11px;
      font-weight: 600;
      border-radius: var(--radius-xs);
    }
  `]
})
export class SettingsGeneralPanelComponent {
  @Input() systemSettings: Record<string, string> = {};
  @Input() canUpdateSystemSettings = false;
  @Input() isSaving = false;
  @Input() languages: Array<{ code: string; name: string }> = [];
  @Output() save = new EventEmitter<void>();

  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();

  private readonly timezoneMemo = optionsMemo<readonly SMTSelectOption<string>[]>();

  private readonly dateFormatMemo = optionsMemo<readonly SMTSelectOption<string>[]>();

  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages], () =>
      this.languages.map(lang => ({ id: lang.code, label: `${lang.name} (${lang.code.toUpperCase()})` }))
    );
  }

  timezoneOptions(): readonly SMTSelectOption<string>[] {
    const current = this.systemSettings['system.default_timezone'];
    return this.timezoneMemo([current], () => withCurrent(TIMEZONES, current));
  }

  dateFormatOptions(): readonly SMTSelectOption<string>[] {
    const current = this.systemSettings['system.date_format'];
    return this.dateFormatMemo([current], () => withCurrent(DATE_FORMATS, current));
  }
}
