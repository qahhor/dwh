import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../../shared/ui/ui-custom-fields.component';
import { User } from '../../../core/models/auth.models';
import { Role } from '../../../core/models/rbac.models';
import { CustomField } from '../../../core/models/custom-field.models';
import { KeysetPage } from '../../../core/models/common.models';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiBadgeComponent,
    UiModalComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <div class="users-container">
      <div class="page-header">
        <div>
          <h2 class="page-title">Пользователи</h2>
          <p class="page-subtitle">Управление учётными записями, ролями и динамическими полями</p>
        </div>
        <ui-button
          *ngIf="canCreateUser()"
          variant="primary"
          icon="person_add"
          (onClick)="openCreateModal()"
        >
          Создать пользователя
        </ui-button>
      </div>

      <!-- Filters & Search -->
      <div class="card toolbar-card">
        <div class="search-box">
          <span class="material-symbols-outlined">search</span>
          <input
            type="text"
            class="toolbar-input"
            placeholder="Поиск по имени, логину или email..."
            [(ngModel)]="searchQuery"
            (keyup.enter)="loadUsers(true)"
          />
        </div>

        <div class="filter-box">
          <select class="toolbar-select" [(ngModel)]="selectedState" (change)="loadUsers(true)">
            <option value="">Все статусы</option>
            <option value="A">Активные</option>
            <option value="P">Заблокированные / Удаленные</option>
          </select>
          <ui-button variant="secondary" size="md" (onClick)="loadUsers(true)">Применить</ui-button>
        </div>
      </div>

      <!-- Users Grid -->
      <div class="card table-card">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Пользователь</th>
                <th>Логин</th>
                <th>Email / Телефон</th>
                <th>Язык / Зона</th>
                <th>2FA</th>
                <th>Статус</th>
                <th>Создан</th>
                <th class="text-right">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let u of users()">
                <td class="tabular-nums font-mono text-muted">#{{ u.id }}</td>
                <td>
                  <div class="user-cell">
                    <div class="user-avatar-sm">{{ u.name ? u.name.charAt(0).toUpperCase() : 'U' }}</div>
                    <span class="user-fullname font-medium">{{ u.name }}</span>
                  </div>
                </td>
                <td class="tabular-nums font-mono">&#64;{{ u.login }}</td>
                <td>
                  <div class="contact-info">
                    <div>{{ u.email }}</div>
                    <div class="text-muted" *ngIf="u.phone">{{ u.phone }}</div>
                  </div>
                </td>
                <td class="text-muted text-xs">
                  <div>{{ u.language || 'ru' }}</div>
                  <div>{{ u.timezone || 'Asia/Tashkent' }}</div>
                </td>
                <td>
                  <span class="material-symbols-outlined twofa-icon" [class.enabled]="u.is2faEnabled">
                    {{ u.is2faEnabled ? 'verified_user' : 'gpp_maybe' }}
                  </span>
                </td>
                <td>
                  <ui-badge [variant]="u.state === 'A' ? 'active' : 'passive'" [dot]="true">
                    {{ u.state === 'A' ? 'Активен' : 'Заблокирован' }}
                  </ui-badge>
                </td>
                <td class="tabular-nums text-muted">{{ u.createdAt | date:'dd.MM.yyyy' }}</td>
                <td class="text-right actions-cell">
                  <ui-button
                    *ngIf="canUpdateUser()"
                    variant="ghost"
                    size="sm"
                    icon="edit"
                    title="Редактировать"
                    (onClick)="openEditModal(u)"
                  ></ui-button>
                  <ui-button
                    *ngIf="u.state === 'A' && canBlockUser()"
                    variant="ghost"
                    size="sm"
                    icon="lock"
                    title="Заблокировать"
                    (onClick)="toggleUserState(u, 'block')"
                  ></ui-button>
                  <ui-button
                    *ngIf="u.state === 'P' && canUnblockUser()"
                    variant="ghost"
                    size="sm"
                    icon="lock_open"
                    title="Разблокировать"
                    (onClick)="toggleUserState(u, 'unblock')"
                  ></ui-button>
                  <ui-button
                    *ngIf="u.login !== 'admin' && canDeleteUser()"
                    variant="ghost"
                    size="sm"
                    icon="delete"
                    title="Удалить (анонимизировать)"
                    (onClick)="deleteUser(u)"
                  ></ui-button>
                </td>
              </tr>

              <tr *ngIf="users().length === 0 && !isLoading()">
                <td colspan="9" class="empty-cell">Пользователи не найдены</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Load More (Keyset) -->
        <div class="load-more-bar" *ngIf="hasMore()">
          <ui-button variant="secondary" size="md" [loading]="isLoading()" (onClick)="loadUsers(false)">
            Загрузить ещё
          </ui-button>
        </div>
      </div>
    </div>

    <!-- Create User Modal -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание пользователя"
      size="lg"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">ФИО <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="createForm.name" placeholder="Иванов Иван" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Логин <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="createForm.login" placeholder="ivanov" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Email <span class="req">*</span></label>
            <input type="email" class="form-input" [(ngModel)]="createForm.email" placeholder="ivanov@company.com" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Телефон</label>
            <input type="text" class="form-input" [(ngModel)]="createForm.phone" placeholder="+998901234567" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Временный пароль <span class="req">*</span></label>
            <input type="password" class="form-input" [(ngModel)]="createForm.password" placeholder="Минимум 10 символов" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Язык интерфейса</label>
            <select class="form-input" [(ngModel)]="createForm.language">
              <option value="ru">Русский (ru)</option>
              <option value="uz">O'zbekcha (uz)</option>
              <option value="en">English (en)</option>
            </select>
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Часовой пояс</label>
            <select class="form-input" [(ngModel)]="createForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
            </select>
          </div>
          <div class="form-group flex-1 checkbox-group">
            <label class="checkbox-label">
              <input type="checkbox" [(ngModel)]="createForm.is2faEnabled" />
              <span>Включить 2FA (двухфакторную аутентификацию)</span>
            </label>
          </div>
        </div>

        <!-- Dynamic Custom Fields -->
        <div class="custom-fields-section" *ngIf="customFields().length > 0">
          <h4 class="custom-fields-title">Дополнительные атрибуты</h4>
          <ui-custom-fields
            [fields]="customFields()"
            [(values)]="createForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateUser()">Создать</ui-button>
      </div>
    </ui-modal>

    <!-- Edit User Modal -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактирование пользователя"
      size="lg"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-form">
        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">ФИО <span class="req">*</span></label>
            <input type="text" class="form-input" [(ngModel)]="editForm.name" placeholder="Иванов Иван" />
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Телефон</label>
            <input type="text" class="form-input" [(ngModel)]="editForm.phone" placeholder="+998901234567" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Язык интерфейса</label>
            <select class="form-input" [(ngModel)]="editForm.language">
              <option value="ru">Русский (ru)</option>
              <option value="uz">O'zbekcha (uz)</option>
              <option value="en">English (en)</option>
            </select>
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Часовой пояс</label>
            <select class="form-input" [(ngModel)]="editForm.timezone">
              <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
              <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
              <option value="UTC">UTC (UTC+0)</option>
            </select>
          </div>
        </div>

        <div class="form-group checkbox-group">
          <label class="checkbox-label">
            <input type="checkbox" [(ngModel)]="editForm.is2faEnabled" />
            <span>Включить 2FA (двухфакторную аутентификацию)</span>
          </label>
        </div>

        <!-- Dynamic Custom Fields -->
        <div class="custom-fields-section" *ngIf="customFields().length > 0">
          <h4 class="custom-fields-title">Дополнительные атрибуты</h4>
          <ui-custom-fields
            [fields]="customFields()"
            [(values)]="editForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditUser()">Сохранить</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .users-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .page-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
    }

    .page-subtitle {
      font-size: 12px;
      color: var(--text-muted);
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
    }

    .toolbar-card {
      padding: 12px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    .search-box {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      max-width: 420px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 4px 10px;
      color: var(--text-muted);
    }

    .toolbar-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 13px;
      font-family: inherit;
      color: var(--text-main);
      width: 100%;
    }

    .filter-box {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .toolbar-select {
      height: 34px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }

    .table-card {
      padding: 0;
      overflow: hidden;
    }

    .table-wrapper {
      overflow-x: auto;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }

    .data-table th {
      text-align: left;
      padding: 10px 14px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 12px;
    }

    .data-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
    }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .user-avatar-sm {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 12px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-xs { font-size: 11px; }
    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }
    .actions-cell { white-space: nowrap; }

    .twofa-icon {
      font-size: 18px;
      color: var(--text-light);
    }
    .twofa-icon.enabled {
      color: var(--success);
    }

    .load-more-bar {
      padding: 12px;
      display: flex;
      justify-content: center;
      background-color: var(--bg-hover);
      border-top: 1px solid var(--border-color);
    }

    .empty-cell {
      text-align: center;
      color: var(--text-muted);
      padding: 32px !important;
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .form-row {
      display: flex;
      gap: 14px;
    }

    .flex-1 { flex: 1; }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .checkbox-group {
      justify-content: center;
      padding-top: 18px;
    }

    .checkbox-label {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: var(--text-main);
      cursor: pointer;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req { color: var(--danger); }

    .form-input {
      height: 34px;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .form-input:focus {
      border-color: var(--primary);
    }

    .custom-fields-section {
      margin-top: 8px;
      padding-top: 12px;
      border-top: 1px dashed var(--border-color);
    }

    .custom-fields-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 8px;
    }
  `]
})
export class UsersComponent implements OnInit {
  readonly users = signal<User[]>([]);
  readonly customFields = signal<CustomField[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  nextCursor: string | null = null;

  searchQuery = '';
  selectedState = '';

  readonly isCreateModalOpen = signal<boolean>(false);
  createForm: any = {
    name: '',
    login: '',
    email: '',
    phone: '',
    password: '',
    language: 'ru',
    timezone: 'Asia/Tashkent',
    is2faEnabled: false,
    attributes: {}
  };

  readonly isEditModalOpen = signal<boolean>(false);
  editUserId: number | null = null;
  editForm: any = {
    name: '',
    phone: '',
    language: 'ru',
    timezone: 'Asia/Tashkent',
    is2faEnabled: false,
    attributes: {}
  };

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadUsers(true);
    this.loadCustomFields();
  }

  canCreateUser(): boolean {
    return this.permService.canCreate('iam.users') || this.permService.canCreate('md_users');
  }

  canUpdateUser(): boolean {
    return this.permService.canUpdate('iam.users') || this.permService.canUpdate('md_users');
  }

  canDeleteUser(): boolean {
    return this.permService.canDelete('iam.users') || this.permService.canDelete('md_users');
  }

  canBlockUser(): boolean {
    return this.permService.hasPermission('iam.users', 'block') || this.permService.hasPermission('md_users', 'block');
  }

  canUnblockUser(): boolean {
    return this.permService.hasPermission('iam.users', 'unblock') || this.permService.hasPermission('md_users', 'unblock');
  }

  loadUsers(reset: boolean = false) {
    if (reset) {
      this.nextCursor = null;
    }

    this.isLoading.set(true);
    this.api.get<KeysetPage<User>>('/iam/users', {
      limit: 20,
      cursor: this.nextCursor || undefined,
      search: this.searchQuery || undefined,
      state: this.selectedState || undefined
    }).subscribe({
      next: res => {
        this.isLoading.set(false);
        if (reset) {
          this.users.set(res.items || []);
        } else {
          this.users.update(cur => [...cur, ...(res.items || [])]);
        }
        this.nextCursor = res.nextCursor;
        this.hasMore.set(res.hasMore);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }

  loadCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'USER' }).subscribe(res => {
      this.customFields.set(res || []);
    });
  }

  openCreateModal() {
    this.createForm = {
      name: '',
      login: '',
      email: '',
      phone: '',
      password: '',
      language: 'ru',
      timezone: 'Asia/Tashkent',
      is2faEnabled: false,
      attributes: {}
    };
    this.isCreateModalOpen.set(true);
  }

  submitCreateUser() {
    if (!this.createForm.name || !this.createForm.login || !this.createForm.email || !this.createForm.password) {
      this.toast.warning('Заполните обязательные поля');
      return;
    }

    if (this.createForm.password.length < 10) {
      this.toast.warning('Пароль должен содержать не менее 10 символов');
      return;
    }

    this.isSubmitting.set(true);
    this.api.post('/iam/users', this.createForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success('Пользователь успешно создан');
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.isSubmitting.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при создании пользователя');
      }
    });
  }

  openEditModal(user: User) {
    this.editUserId = user.id;
    this.editForm = {
      name: user.name,
      phone: user.phone || '',
      language: (user as any).language || 'ru',
      timezone: (user as any).timezone || 'Asia/Tashkent',
      is2faEnabled: !!user.is2faEnabled,
      attributes: { ...(user.attributes || {}) }
    };
    this.isEditModalOpen.set(true);
  }

  submitEditUser() {
    if (!this.editUserId) return;
    if (!this.editForm.name) {
      this.toast.warning('Имя пользователя обязательно');
      return;
    }

    this.isSubmitting.set(true);
    this.api.patch(`/iam/users/${this.editUserId}`, this.editForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success('Данные пользователя обновлены');
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.isSubmitting.set(false);
        this.toast.error(err?.error?.detail || 'Ошибка при сохранении пользователя');
      }
    });
  }

  toggleUserState(user: User, action: 'block' | 'unblock') {
    this.api.post(`/iam/users/${user.id}/${action}`).subscribe({
      next: () => {
        this.toast.success(action === 'block' ? 'Пользователь заблокирован' : 'Пользователь разблокирован');
        this.loadUsers(true);
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || 'Ошибка при изменении статуса');
      }
    });
  }

  deleteUser(user: User) {
    if (confirm(`Вы действительно хотите удалить и анонимизировать пользователя "${user.name}" (@${user.login})? Это действие необратимо.`)) {
      this.api.delete(`/iam/users/${user.id}`).subscribe({
        next: () => {
          this.toast.success(`Пользователь ${user.name} успешно удален и анонимизирован`);
          this.loadUsers(true);
        },
        error: (err: any) => {
          this.toast.error(err?.error?.detail || 'Ошибка при удалении пользователя');
        }
      });
    }
  }
}
