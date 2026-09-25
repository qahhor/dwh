import { Component, inject, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../../shared/ui-kit/components/forms/control';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { SMTDataSelectComponent } from '../../../../shared/ui-kit/components/forms/data-select';
import { LookupSources, UserRef } from '../../../../shared/lookups/lookup-sources';
import { SMTTagGroupComponent, SMTTagOption } from '../../../../shared/ui-kit/components/tag';
import { UiCustomFieldsComponent } from '../../../../shared/ui/ui-custom-fields.component';
import { User } from '../../../../core/models/auth.models';
import { Role } from '../../../../core/models/rbac.models';
import { CustomField } from '../../../../core/models/custom-field.models';

@Component({
  selector: 'app-user-edit-modal',
  standalone: true,
  imports: [
    SMTControlComponent,
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiModalComponent,
    UiButtonComponent,
    SMTDataSelectComponent,
    SMTTagGroupComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <ui-modal
      [isOpen]="isOpen"
      [title]="'iam.redaktirovat_polzovatelya' | t"
      size="md"
      (close)="close.emit()"
    >
      <div body class="clean-modal-body" *ngIf="editingUser as u">
        <div class="form-grid">
          <smt-control class="form-group span-2" [smtLabel]="'iam.fio' | t" [smtError]="isEditSubmitted && !editForm.name.trim() ? ('iam.ukazhite_fio_polzovatelya' | t) : ''">
            <input
              id="user-edit-name"
              name="userEditName"
              type="text"
              class="clean-input"
              required
              [(ngModel)]="editForm.name"
              [placeholder]="'iam.ivanov_ivan_ivanovich' | t"
            />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.login_chtenie' | t">
            <input id="user-edit-login" type="text" class="clean-input font-mono disabled" [value]="u.login" disabled />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.email_chtenie' | t">
            <input id="user-edit-email" type="email" class="clean-input font-mono disabled" [value]="u.email" disabled />
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.telefon.822f9fd' | t">
            <input
              id="user-edit-phone"
              name="userEditPhone"
              type="tel"
              class="clean-input font-mono"
              autocomplete="tel"
              [(ngModel)]="editForm.phone"
              placeholder="+998901234567"
            />
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
            <select id="user-edit-language" name="userEditLanguage" class="clean-input" [(ngModel)]="editForm.language">
              <option *ngFor="let lang of languages" [value]="lang.code">
                {{ lang.name }} ({{ lang.code }})
              </option>
            </select>
          </smt-control>

          <smt-control class="form-group" [smtLabel]="'iam.chasovoy_poyas' | t">
            <select id="user-edit-timezone" name="userEditTimezone" class="clean-input" [(ngModel)]="editForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
              <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
              <option value="Asia/Dubai">Asia/Dubai (UTC+4)</option>
            </select>
          </smt-control>

          <div class="form-group span-2">
            <label class="clean-checkbox">
              <input name="userEdit2fa" type="checkbox" [(ngModel)]="editForm.is2faEnabled" />
              <span>{{ 'iam.vklyuchit_dvuhfaktornuyu_zaschitu_2fa_otp' | t }}</span>
            </label>
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
        <ui-button variant="secondary" size="md" (onClick)="close.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting" (onClick)="submit.emit()">{{ 'common.save' | t }}</ui-button>
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
    .clean-input {
      height: 34px;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }
    .clean-input:focus { border-color: var(--primary); }
    .clean-input.disabled {
      background-color: var(--bg-hover);
      color: var(--text-muted);
      cursor: not-allowed;
    }
    .font-mono { font-family: monospace; }
    .req { color: var(--danger); }
    .field-error { font-size: 10px; color: var(--danger); }

    .clean-checkbox {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      color: var(--text-main);
      cursor: pointer;
    }

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
