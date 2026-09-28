import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { SMTPhoneInputComponent, SMTPhoneInputValueAccessor } from '@shared/ui-kit/components/forms/phone-input';
import { LookupSources } from '@shared/lookups/lookup-sources';
import { SMTTagGroupComponent, SMTTagOption } from '@shared/ui-kit/components/tag';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '@shared/ui-kit/components/forms/checkbox';
import { UiCustomFieldsComponent } from '@shared/ui/ui-custom-fields.component';
import { Role } from '@core/models/rbac.models';
import { CustomField } from '@core/models/custom-field.models';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';
import { UserCreateForm, createDefaultUserCreateForm } from '../users.models';

/** The time zones offered; their names are not translated. */
const TIMEZONE_OPTIONS: readonly SMTSelectOption<string>[] = [
  { id: 'Asia/Tashkent', label: 'Asia/Tashkent (UTC+5)' },
  { id: 'Europe/Moscow', label: 'Europe/Moscow (UTC+3)' },
  { id: 'UTC', label: 'UTC (UTC+0)' },
  { id: 'Asia/Almaty', label: 'Asia/Almaty (UTC+5)' },
  { id: 'Asia/Dubai', label: 'Asia/Dubai (UTC+4)' },
];

@Component({
  selector: 'app-user-create-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTCheckboxComponent,
    SMTCheckboxValueAccessor,
    FormsModule,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    SMTDataSelectComponent,
    SMTPhoneInputComponent,
    SMTPhoneInputValueAccessor,
    SMTTagGroupComponent,
    UiCustomFieldsComponent,
  ],
  templateUrl: './user-create-modal.component.html',
  styleUrl: './user-create-modal.component.css',
})
export class UserCreateModalComponent {
  private readonly i18n = inject(I18nService);

  readonly isOpen = input(false);
  readonly isSubmitting = input(false);
  readonly isCreateSubmitted = input(false);
  readonly roles = input<Role[]>([]);
  readonly languages = input<
    Array<{
      code: string;
      name: string;
    }>
  >([]);
  readonly passwordStrength = input<{
    score: number;
    label: string;
    color: string;
  }>({ score: 0, label: '', color: '' });
  readonly hasMinLength = input(false);
  readonly hasUpperAndLower = input(false);
  readonly hasDigitsOrSymbols = input(false);
  readonly doesNotContainLogin = input(false);
  readonly createForm = input<UserCreateForm>(createDefaultUserCreateForm());
  readonly customFields = input<CustomField[]>([]);

  readonly close = output<void>();
  readonly submit = output<void>();
  readonly generatePassword = output<void>();
  readonly copyPassword = output<void>();

  /** Active users for the manager picker; the field searches them itself. */
  readonly users = inject(LookupSources).activeUsers;
  readonly timezoneOptions = TIMEZONE_OPTIONS;
  readonly passwordPolicy = PASSWORD_POLICY;
  readonly fitsPolicy = fitsPasswordPolicy;
  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();
  private roleOptionsCache: { roles: Role[]; lang: string; options: SMTTagOption<number>[] } | null = null;

  /** The languages as options, labelled as they are named in the data. */
  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages()], () =>
      this.languages().map((lang) => ({ id: lang.code, label: `${lang.name} (${lang.code})` })),
    );
  }

  /** The roles as tags. */
  roleOptions(): SMTTagOption<number>[] {
    const lang = this.i18n.currentLang();
    const cached = this.roleOptionsCache;
    const roles = this.roles();
    if (cached && cached.roles === roles && cached.lang === lang) return cached.options;
    const options = roles.map((role) => ({ value: role.id, label: role.name }));
    this.roleOptionsCache = { roles: roles, lang, options };
    return options;
  }
}
