import { Component, inject, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../../shared/ui-kit/components/forms/control';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { SMTDataSelectComponent } from '../../../../shared/ui-kit/components/forms/data-select';
import { SMTPhoneInputComponent, SMTPhoneInputValueAccessor } from '../../../../shared/ui-kit/components/forms/phone-input';
import { LookupSources } from '../../../../shared/lookups/lookup-sources';
import { SMTTagGroupComponent, SMTTagOption } from '../../../../shared/ui-kit/components/tag';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '../../../../shared/ui-kit/components/forms/checkbox';
import { UiCustomFieldsComponent } from '../../../../shared/ui/ui-custom-fields.component';
import { Role } from '../../../../core/models/rbac.models';
import { CustomField } from '../../../../core/models/custom-field.models';

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
  standalone: true,
  imports: [
    SMTControlComponent,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTCheckboxComponent,
    SMTCheckboxValueAccessor,
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiModalComponent,
    SMTButtonComponent,
    SMTDataSelectComponent,
    SMTPhoneInputComponent,
    SMTPhoneInputValueAccessor,
    SMTTagGroupComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <ui-modal
      [isOpen]="isOpen"
      [title]="'iam.sozdat_polzovatelya' | t"
      size="md"
      (close)="close.emit()"
    >
      <div body class="clean-modal-body">
        <div class="form-grid">
          <smt-control class="form-group span-2" [smtLabel]="'iam.fio' | t" [smtError]="isCreateSubmitted && !createForm.name.trim() ? ('iam.ukazhite_fio_polzovatelya' | t) : ''">
            <smt-input
              smtFieldId="user-create-name"
              name="userCreateName"
              required
              [(ngModel)]="createForm.name"
              [placeholder]="'iam.ivanov_ivan_ivanovich' | t" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'analytics.login' | t" [smtError]="isCreateSubmitted && !createForm.login.trim() ? ('iam.ukazhite_login' | t) : ''">
            <smt-input
              smtFieldId="user-create-login"
              name="userCreateLogin"
              class="font-mono"
              required
              autocomplete="username"
              [(ngModel)]="createForm.login"
              placeholder="ivanov" />
          </smt-control>

          <smt-control class="form-group" smtLabel="Email" [smtError]="isCreateSubmitted && !createForm.email.trim() ? ('iam.ukazhite_email' | t) : ''">
            <smt-input
              smtFieldId="user-create-email"
              name="userCreateEmail"
              type="email"
              class="font-mono"
              required
              autocomplete="email"
              [(ngModel)]="createForm.email"
              placeholder="ivanov@company.local" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.telefon.822f9fd' | t">
            <smt-phone-input smtFieldId="user-create-phone" name="userCreatePhone" [(ngModel)]="createForm.phone" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.rukovoditel' | t">
            <!-- Searches the server: a manager is rarely among the rows loaded on the list. -->
            <smt-data-select
              [source]="users"
              [value]="createForm.managerId"
              (valueChange)="createForm.managerId = $event"
              [placeholder]="'iam.bez_rukovoditelya' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'iam.bez_rukovoditelya' | t" />
          </smt-control>

          <smt-control class="form-group span-2" [smtLabel]="'iam.vremennyy_parol' | t" [smtHint]="createForm.password ? '' : ('iam.ne_menee_10_simvolov_bez_sovpadeniy_s_loginom' | t)" [smtError]="isCreateSubmitted && createForm.password.length < 10 ? ('iam.parol_dolzhen_soderzhat_ne_menee_10_simvolov' | t) : ''">
            <div class="pwd-wrapper">
              <!-- The field shows and hides the password itself; generating and copying sit beside it. -->
              <smt-input
                smtFieldId="user-create-password"
                name="userCreatePassword"
                type="password"
                class="pwd-field font-mono"
                required
                [minLength]="10"
                autocomplete="new-password"
                [(ngModel)]="createForm.password"
                [placeholder]="'iam.minimum_10_simvolov' | t" />
              <div class="pwd-actions">
                <button
                  type="button"
                  class="pwd-btn"
                  [title]="'iam.sgenerirovat_parol' | t"
                  [attr.aria-label]="'iam.sgenerirovat_parol' | t"
                  (click)="generatePassword.emit()"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">auto_fix_high</span>
                </button>
                <button
                  type="button"
                  class="pwd-btn"
                  *ngIf="createForm.password"
                  [title]="'iam.skopirovat_parol' | t"
                  [attr.aria-label]="'iam.skopirovat_parol' | t"
                  (click)="copyPassword.emit()"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">content_copy</span>
                </button>
              </div>
            </div>
            <!-- Dynamic Password Strength Meter -->
            <div class="pwd-strength-container" *ngIf="createForm.password">
              <div class="pwd-meter-header">
                <div class="pwd-meter-bars">
                  <div class="pwd-bar" [class.filled]="passwordStrength.score >= 1" [style.background-color]="passwordStrength.score >= 1 ? passwordStrength.color : ''"></div>
                  <div class="pwd-bar" [class.filled]="passwordStrength.score >= 2" [style.background-color]="passwordStrength.score >= 2 ? passwordStrength.color : ''"></div>
                  <div class="pwd-bar" [class.filled]="passwordStrength.score >= 3" [style.background-color]="passwordStrength.score >= 3 ? passwordStrength.color : ''"></div>
                  <div class="pwd-bar" [class.filled]="passwordStrength.score >= 4" [style.background-color]="passwordStrength.score >= 4 ? passwordStrength.color : ''"></div>
                </div>
                <span class="pwd-strength-label" [style.color]="passwordStrength.color">{{ passwordStrength.label }}</span>
              </div>
              <div class="pwd-checklist">
                <div class="check-item" [class.valid]="hasMinLength">
                  <span class="material-symbols-outlined check-ico" aria-hidden="true">{{ hasMinLength ? 'check' : 'close' }}</span>
                  <span>{{ 'iam.ne_menee_10_simvolov' | t }}</span>
                  <span class="sr-only">{{ (hasMinLength ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
                </div>
                <div class="check-item" [class.valid]="hasUpperAndLower">
                  <span class="material-symbols-outlined check-ico" aria-hidden="true">{{ hasUpperAndLower ? 'check' : 'close' }}</span>
                  <span>{{ 'iam.zaglavnye_i_strochnye_bukvy' | t }}</span>
                  <span class="sr-only">{{ (hasUpperAndLower ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
                </div>
                <div class="check-item" [class.valid]="hasDigitsOrSymbols">
                  <span class="material-symbols-outlined check-ico" aria-hidden="true">{{ hasDigitsOrSymbols ? 'check' : 'close' }}</span>
                  <span>{{ 'iam.cifry_ili_specsimvoly' | t }}</span>
                  <span class="sr-only">{{ (hasDigitsOrSymbols ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
                </div>
                <div class="check-item" [class.valid]="doesNotContainLogin">
                  <span class="material-symbols-outlined check-ico" aria-hidden="true">{{ doesNotContainLogin ? 'check' : 'close' }}</span>
                  <span>{{ 'iam.bez_sovpadeniy_s_loginom' | t }}</span>
                  <span class="sr-only">{{ (doesNotContainLogin ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
                </div>
              </div>
            </div>
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.yazyk' | t">
            <smt-select
              smtTriggerId="user-create-language"
              name="userCreateLanguage"
              [(ngModel)]="createForm.language"
              [options]="languageOptions()"
              [allowClear]="false" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.chasovoy_poyas' | t">
            <smt-select
              smtTriggerId="user-create-timezone"
              name="userCreateTimezone"
              [(ngModel)]="createForm.timezone"
              [options]="timezoneOptions"
              [allowClear]="false" />
          </smt-control>

          <div class="form-group span-2">
            <div smt-checkbox name="userCreate2fa" [(ngModel)]="createForm.is2faEnabled">{{ 'iam.vklyuchit_dvuhfaktornuyu_zaschitu_2fa_otp' | t }}</div>
          </div>

          <!-- Roles -->
          <smt-control class="form-group span-2" *ngIf="roles.length > 0" [smtLabel]="'iam.roli_dostupa_rbac' | t">
            <smt-tag-group
              [options]="roleOptions()"
              [value]="createForm.roleIds || []"
              (valueChange)="createForm.roleIds = $event" />
          </smt-control>

          <!-- Custom Fields -->
          <div class="form-group span-2" *ngIf="customFields.length > 0">
            <span class="clean-label">{{ 'iam.dopolnitelnye_polya' | t }}</span>
            <ui-custom-fields
              [fields]="customFields"
              [(values)]="createForm.attributes"
            ></ui-custom-fields>
          </div>
        </div>
      </div>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="close.emit()">{{ 'common.cancel' | t }}</button>
        <button smt-button type="button" smtVariant="primary" smtSize="md" [smtLoading]="isSubmitting" (click)="submit.emit()">{{ 'common.create' | t }}</button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .clean-modal-body {
      padding: 4px 0;
    }
    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    .span-2 { grid-column: 1 / -1; }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    /* The same as the smt-control label, so wrapped and plain fields read alike. */
    .clean-label {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-main);
    }
    .font-mono { font-family: monospace; }
    .req { color: var(--danger); }
    .field-error { font-size: 10px; color: var(--danger); }
    .clean-hint { font-size: 10px; color: var(--text-muted); }

    .pwd-wrapper {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .pwd-field {
      flex: 1;
    }
    .pwd-actions {
      display: flex;
      align-items: center;
      gap: 2px;
    }
    .pwd-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 4px;
      border-radius: var(--radius-sm);
      transition: color 0.15s ease, background-color 0.15s ease;
    }
    .pwd-btn:hover {
      color: var(--text-main);
      background-color: var(--bg-hover);
    }
    .pwd-btn:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 1px;
    }
    .pwd-btn .material-symbols-outlined {
      font-size: 18px;
    }

    /* Password Strength Meter */
    .pwd-strength-container {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-top: 4px;
      padding: 8px 10px;
      background-color: var(--bg-hover);
      border-radius: var(--radius-sm);
    }
    .pwd-meter-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .pwd-meter-bars {
      display: flex;
      gap: 4px;
      flex: 1;
    }
    .pwd-bar {
      height: 4px;
      flex: 1;
      border-radius: 2px;
      background-color: var(--border-color);
      transition: background-color 0.2s ease;
    }
    .pwd-strength-label {
      font-size: 11px;
      font-weight: 600;
    }
    .pwd-checklist {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 4px 10px;
      margin-top: 4px;
    }
    .check-item {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      color: var(--text-muted);
    }
    .check-item.valid {
      color: var(--success);
    }
    .check-ico {
      font-size: 13px;
    }


    @media (max-width: 640px) {
      .modal-form,
      .form-group { min-width: 0; }
      .form-grid { grid-template-columns: minmax(0, 1fr); }
      .span-2 { grid-column: auto; }
    }
  `]
})
export class UserCreateModalComponent {
  private readonly i18n = inject(I18nService);

  /** Active users for the manager picker; the field searches them itself. */
  readonly users = inject(LookupSources).activeUsers;
  readonly timezoneOptions = TIMEZONE_OPTIONS;
  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();
  private roleOptionsCache: { roles: Role[]; lang: string; options: SMTTagOption<number>[] } | null = null;
  @Input() isOpen = false;
  @Input() isSubmitting = false;
  @Input() isCreateSubmitted = false;
  @Input() createForm: any = {};
  @Input() roles: Role[] = [];
  @Input() languages: Array<{ code: string, name: string }> = [];
  @Input() customFields: CustomField[] = [];
  @Input() passwordStrength: { score: number, label: string, color: string } = { score: 0, label: '', color: '' };
  @Input() hasMinLength = false;
  @Input() hasUpperAndLower = false;
  @Input() hasDigitsOrSymbols = false;
  @Input() doesNotContainLogin = false;

  @Output() close = new EventEmitter<void>();
  @Output() submit = new EventEmitter<void>();
  @Output() generatePassword = new EventEmitter<void>();
  @Output() copyPassword = new EventEmitter<void>();

  /** The languages as options, labelled as they are named in the data. */
  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages], () =>
      this.languages.map(lang => ({ id: lang.code, label: `${lang.name} (${lang.code})` })));
  }

  /** The roles as tags. */
  roleOptions(): SMTTagOption<number>[] {
    const lang = this.i18n.currentLang();
    const cached = this.roleOptionsCache;
    if (cached && cached.roles === this.roles && cached.lang === lang) return cached.options;
    const options = this.roles.map(role => ({ value: role.id, label: role.name }));
    this.roleOptionsCache = { roles: this.roles, lang, options };
    return options;
  }
}
