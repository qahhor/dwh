import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { I18nService } from '@core/services/i18n.service';
import { User } from '@core/models/auth.models';
import { Role } from '@core/models/rbac.models';
import { safeNumericRecordId } from '@core/services/search-target';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';
import {
  UserCreateForm,
  UserEditForm,
  calculatePasswordStrength,
  copyPasswordToClipboard,
  createDefaultUserCreateForm,
  createDefaultUserEditForm,
  doesNotContainLogin,
  generateSecurePassword,
  hasDigitsOrSymbols,
  hasMinLength,
  hasUpperAndLower,
} from '../users.models';

@Injectable({
  providedIn: 'root',
})
export class UserFormsService {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);

  isCreateSubmitted = false;
  isEditSubmitted = false;
  editingUser: User | null = null;
  editSessionId = 0;
  editSaveRequestId = 0;

  createForm: UserCreateForm = createDefaultUserCreateForm();
  editForm: UserEditForm = {
    name: '',
    phone: '',
    managerId: null,
    language: 'ru',
    timezone: 'Asia/Tashkent',
    is2faEnabled: false,
    roleIds: [],
    attributes: {},
  };

  openCreateModal(roles: Role[]): void {
    const defaultUserRole = roles.find((r) => r.pcode === 'user');
    const defaultRoleIds = defaultUserRole ? [defaultUserRole.id] : [];
    this.createForm = createDefaultUserCreateForm(defaultRoleIds);
    this.isCreateSubmitted = false;
    this.isCreateModalOpen.set(true);
  }

  submitCreateUser(onSuccess: () => void): void {
    this.isCreateSubmitted = true;
    if (!this.createForm.name || !this.createForm.login || !this.createForm.email || !this.createForm.password) {
      this.toast.warning(this.uiI18n.translate('iam.zapolnite_obyazatelnye_polya'));
      return;
    }

    if (!fitsPasswordPolicy(this.createForm.password)) {
      this.toast.warning(this.uiI18n.translate('password.policy.length_error', PASSWORD_POLICY));
      return;
    }

    this.isSubmitting.set(true);
    this.api.post('/iam/users', this.createForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('iam.polzovatel_uspeshno_sozdan'));
        onSuccess();
      },
      error: () => {
        this.isSubmitting.set(false);
      },
    });
  }

  openEditModal(user: User): boolean {
    if (!safeNumericRecordId(user.id)) return false;
    this.editSessionId++;
    this.editingUser = user;
    this.editForm = createDefaultUserEditForm(user);
    this.isEditSubmitted = false;
    this.isEditModalOpen.set(true);
    return true;
  }

  submitEditUser(isDestroyed: () => boolean, onSuccess: () => void, onCloseModal: (sessionId: number) => void): void {
    if (!this.editingUser) return;
    this.isEditSubmitted = true;
    if (!this.editForm.name) {
      this.toast.warning(this.uiI18n.translate('iam.imya_polzovatelya_obyazatelno'));
      return;
    }

    const currentEditSessionId = this.editSessionId;
    const saveRequestId = ++this.editSaveRequestId;
    this.isSubmitting.set(true);
    this.api
      .patch(`/iam/users/${this.editingUser.id}`, this.editForm, { ifMatch: this.editingUser.revision })
      .subscribe({
        next: () => {
          if (isDestroyed()) return;
          if (saveRequestId === this.editSaveRequestId) {
            this.isSubmitting.set(false);
            onCloseModal(currentEditSessionId);
          }
          this.toast.success(this.uiI18n.translate('iam.dannye_sohraneny'));
          onSuccess();
        },
        error: () => {
          if (!isDestroyed() && saveRequestId === this.editSaveRequestId) {
            this.isSubmitting.set(false);
          }
        },
      });
  }

  /** Fills the create form with a generated password that avoids the login. */
  generateSecurePassword(): string {
    const pwd = generateSecurePassword(this.createForm.login);
    this.createForm.password = pwd;
    return pwd;
  }
  copyGeneratedPassword(): Promise<void> {
    return copyPasswordToClipboard(this.createForm.password, this.toast, this.uiI18n);
  }
  passwordStrength() {
    return calculatePasswordStrength(this.createForm.password, this.createForm.login, this.uiI18n);
  }
  hasMinLength(): boolean {
    return hasMinLength(this.createForm.password);
  }
  hasUpperAndLower(): boolean {
    return hasUpperAndLower(this.createForm.password);
  }
  hasDigitsOrSymbols(): boolean {
    return hasDigitsOrSymbols(this.createForm.password);
  }
  doesNotContainLogin(): boolean {
    return doesNotContainLogin(this.createForm.password, this.createForm.login);
  }
}
