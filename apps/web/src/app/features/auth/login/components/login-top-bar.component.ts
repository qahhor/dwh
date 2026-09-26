import { Component, inject } from '@angular/core';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTSelectComponent, SMTSelectOption } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-login-top-bar',
  standalone: true,
  imports: [SMTSelectComponent, TranslatePipe],
  template: `
    <div class="login-top-bar">
      <div class="lang-selector-login">
        <span class="material-symbols-outlined lang-icon" aria-hidden="true">language</span>
        <smt-select
          smtTriggerId="login-language-select"
          class="lang-select-login"
          [ariaLabel]="'settings.yazyk_interfeysa' | t"
          [options]="languageOptions()"
          [allowClear]="false"
          [value]="i18n.currentLang()"
          (valueChange)="onLanguageChange($event)"
        />
      </div>
    </div>
  `,
  styles: [`
    .login-top-bar {
      position: absolute;
      top: 20px;
      right: 24px;
      display: flex;
      align-items: center;
      z-index: 10;
    }

    .lang-selector-login {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .lang-selector-login .lang-icon {
      font-size: 18px;
      color: var(--text-muted);
    }

    .lang-select-login {
      width: 180px;
      box-shadow: var(--shadow-sm);
    }
  `]
})
export class LoginTopBarComponent {
  readonly i18n = inject(I18nService);

  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();

  languageOptions(): SMTSelectOption<string>[] {
    const languages = this.i18n.languages();
    return this.languageMemo([languages], () =>
      languages.map(lang => ({ id: lang.code, label: `${lang.code.toUpperCase()} — ${lang.name}` }))
    );
  }

  onLanguageChange(code: string | null): void {
    if (code && code !== this.i18n.currentLang()) {
      this.i18n.setLanguage(code, false).subscribe();
    }
  }
}
