import { Component, inject, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../../shared/ui-kit/components/forms/control';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../../shared/ui-kit/components/modal';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { SMTDataSelectComponent } from '../../../../shared/ui-kit/components/forms/data-select';
import { SMTPhoneInputComponent, SMTPhoneInputValueAccessor } from '../../../../shared/ui-kit/components/forms/phone-input';
import { LookupSources, UserRef } from '../../../../shared/lookups/lookup-sources';
import { SMTTagGroupComponent, SMTTagOption } from '../../../../shared/ui-kit/components/tag';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent, SMTCheckboxValueAccessor } from '../../../../shared/ui-kit/components/forms/checkbox';
import { UiCustomFieldsComponent } from '../../../../shared/ui/ui-custom-fields.component';
import { User } from '../../../../core/models/auth.models';
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
  selector: 'app-user-edit-modal',
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
    SMTDialogComponent, SMTDialogContentDirective,
    SMTButtonComponent,
    SMTDataSelectComponent,
    SMTPhoneInputComponent,
    SMTPhoneInputValueAccessor,
    SMTTagGroupComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <smt-dialog
      [open]="isOpen"
      [smtTitle]="'iam.redaktirovat_polzovatelya' | t"
      smtSize="md"
      (closed)="close.emit()">
      <ng-template smtDialogContent>
      <div body class="clean-modal-body" *ngIf="editingUser as u">
        <div class="form-grid">
          <smt-control class="form-group span-2" [smtLabel]="'iam.fio' | t" [smtError]="isEditSubmitted && !editForm.name.trim() ? ('iam.ukazhite_fio_polzovatelya' | t) : ''">
            <smt-input
              smtFieldId="user-edit-name"
              name="userEditName"
              required
              [(ngModel)]="editForm.name"
              [placeholder]="'iam.ivanov_ivan_ivanovich' | t" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.login_chtenie' | t">
            <smt-input smtFieldId="user-edit-login" class="font-mono" [value]="u.login" disabled />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.email_chtenie' | t">
            <smt-input smtFieldId="user-edit-email" type="email" class="font-mono" [value]="u.email" disabled />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.telefon.822f9fd' | t">
            <smt-phone-input smtFieldId="user-edit-phone" name="userEditPhone" [(ngModel)]="editForm.phone" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.rukovoditel' | t">
            <!-- Searches the server: a manager is rarely among the rows loaded on the list. -->
            <smt-data-select
              [source]="users"
              [exclude]="notThisUser"
              [value]="editForm.managerId"
              (valueChange)="editForm.managerId = $event"
              [placeholder]="'iam.bez_rukovoditelya' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'iam.bez_rukovoditelya' | t" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.yazyk' | t">
            <smt-select
              smtTriggerId="user-edit-language"
              name="userEditLanguage"
              [(ngModel)]="editForm.language"
              [options]="languageOptions()"
              [allowClear]="false" />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.chasovoy_poyas' | t">
            <smt-select
              smtTriggerId="user-edit-timezone"
              name="userEditTimezone"
              [(ngModel)]="editForm.timezone"
              [options]="timezoneOptions"
              [allowClear]="false" />
          </smt-control>

          <div class="form-group span-2">
            <div smt-checkbox name="userEdit2fa" [(ngModel)]="editForm.is2faEnabled">{{ 'iam.vklyuchit_dvuhfaktornuyu_zaschitu_2fa_otp' | t }}</div>
          </div>

          <!-- Roles -->
          <smt-control class="form-group span-2" *ngIf="roles.length > 0" [smtLabel]="'iam.roli_dostupa_rbac' | t">
            <smt-tag-group
              [options]="roleOptions(u)"
              [value]="editForm.roleIds || []"
              (valueChange)="editForm.roleIds = $event" />
          </smt-control>

          <!-- Custom Fields -->
          <div class="form-group span-2" *ngIf="customFields.length > 0">
            <span class="clean-label">{{ 'iam.dopolnitelnye_polya' | t }}</span>
            <ui-custom-fields
              [fields]="customFields"
              [(values)]="editForm.attributes"
            ></ui-custom-fields>
          </div>
        </div>
      </div>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="close.emit()">{{ 'common.cancel' | t }}</button>
        <button smt-button type="button" smtVariant="primary" smtSize="md" [smtLoading]="isSubmitting" (click)="submit.emit()">{{ 'common.save' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>
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

    .lock-ico { font-size: 13px; color: var(--text-muted); }

    @media (max-width: 640px) {
      .modal-form,
      .form-group { min-width: 0; }
      .form-grid { grid-template-columns: minmax(0, 1fr); }
      .span-2 { grid-column: auto; }
    }
  `]
})
export class UserEditModalComponent {
  private readonly i18n = inject(I18nService);

  /** Active users for the manager picker; the field searches them itself. */
  readonly users = inject(LookupSources).activeUsers;
  readonly timezoneOptions = TIMEZONE_OPTIONS;
  private readonly languageMemo = optionsMemo<SMTSelectOption<string>[]>();
  private roleOptionsCache: { roles: Role[]; user: User | null; lang: string; options: SMTTagOption<number>[] } | null = null;
  /** A user cannot be their own manager. */
  readonly notThisUser = (candidate: UserRef) => candidate.id === this.editingUser?.id;
  @Input() isOpen = false;
  @Input() isSubmitting = false;
  @Input() isEditSubmitted = false;
  @Input() editingUser: User | null = null;
  @Input() editForm: any = {};
  @Input() roles: Role[] = [];
  @Input() languages: Array<{ code: string, name: string }> = [];
  @Input() customFields: CustomField[] = [];

  @Output() close = new EventEmitter<void>();
  @Output() submit = new EventEmitter<void>();

  /** The languages as options, labelled as they are named in the data. */
  languageOptions(): SMTSelectOption<string>[] {
    return this.languageMemo([this.languages], () =>
      this.languages.map(lang => ({ id: lang.code, label: `${lang.name} (${lang.code})` })));
  }

  /** The roles as tags; the built-in admin keeps its admin role, shown locked. */
  roleOptions(user: User | null): SMTTagOption<number>[] {
    const lang = this.i18n.currentLang();
    const cached = this.roleOptionsCache;
    if (cached && cached.roles === this.roles && cached.user === user && cached.lang === lang) return cached.options;
    const locked = (role: Role) => user?.login === 'admin' && role.pcode === 'admin';
    const options = this.roles.map(role => locked(role)
      ? { value: role.id, label: role.name, disabled: true, icon: 'lock', note: this.i18n.translate('iam.zaschischeno') }
      : { value: role.id, label: role.name });
    this.roleOptionsCache = { roles: this.roles, user, lang, options };
    return options;
  }
}
