import { Component, EventEmitter, Input, Output, Signal, TemplateRef, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { of, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { ApiService } from '../../../../core/services/api.service';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../../shared/ui/ui-badge.component';
import { UiLocalTableComponent } from '../../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../../shared/ui-kit/components/table/table.types';
import { Project } from '../../../../core/models/task.models';
import { User } from '../../../../core/models/auth.models';
import { ProjectMember } from '../projects.models';

@Component({
  selector: 'app-project-members-modal',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiModalComponent,
    UiButtonComponent,
    UiBadgeComponent,
    UiLocalTableComponent
  ],
  template: `
    <ui-modal
      [isOpen]="isOpen"
      [title]="'projects.uchastniki_proekta_title' | t:{name: project?.name || ''}"
      size="lg"
      (close)="close.emit()"
    >
      <div body class="members-modal-body">
        <!-- Add Member Panel (visible if user can update project) -->
        <div *ngIf="canUpdateProject" class="add-member-panel">
          <div class="panel-header">
            <span class="material-symbols-outlined panel-icon" aria-hidden="true">person_add</span>
            <span class="panel-title">{{ 'projects.dobavit_uchastnika' | t }}</span>
          </div>

          <div class="add-member-controls">
            <!-- User Search / Selector -->
            <div class="user-search-wrapper">
              <label class="form-label" for="project-member-search">
                {{ 'projects.vyberite_polzovatelya' | t }} <span class="req">*</span>
              </label>
              <div class="search-input-box">
                <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
                <input
                  id="project-member-search"
                  type="text"
                  class="form-input"
                  [placeholder]="selectedUser ? selectedUser.name : ('projects.poisk_polzovatelya' | t)"
                  [(ngModel)]="userSearchQuery"
                  (ngModelChange)="onSearchInput($event)"
                  (focus)="isUserDropdownOpen = true"
                />
                <button
                  *ngIf="selectedUser"
                  type="button"
                  class="clear-user-btn"
                  [title]="'common.clear' | t"
                  (click)="clearSelectedUser()"
                >
                  <span class="material-symbols-outlined" aria-hidden="true">close</span>
                </button>
              </div>

              <!-- User Search Results Dropdown -->
              <div *ngIf="isUserDropdownOpen && foundUsers.length > 0" class="user-dropdown-list">
                <button
                  *ngFor="let u of foundUsers"
                  type="button"
                  class="user-dropdown-item"
                  [class.is-member]="isUserAlreadyMember(u.id)"
                  [disabled]="isUserAlreadyMember(u.id)"
                  (click)="selectUser(u)"
                >
                  <span class="user-avatar-mini">{{ getInitials(u.name) }}</span>
                  <div class="user-item-info">
                    <span class="user-item-name">{{ u.name }}</span>
                    <span class="user-item-email text-muted">&#64;{{ u.login }} &bull; {{ u.email }}</span>
                  </div>
                  <span *ngIf="isUserAlreadyMember(u.id)" class="already-member-tag">
                    {{ 'projects.polzovatel_uzhe_uchastnik' | t }}
                  </span>
                </button>
              </div>
            </div>

            <!-- Role / Access Kind -->
            <div class="access-kind-wrapper">
              <label class="form-label" for="project-member-role">
                {{ 'projects.uroven_dostupa' | t }} <span class="req">*</span>
              </label>
              <select
                id="project-member-role"
                name="accessKind"
                class="form-input form-select"
                [(ngModel)]="selectedAccessKind"
              >
                <option value="MEMBER">{{ 'projects.rol_uchastnik' | t }}</option>
                <option value="MANAGER">{{ 'projects.rol_rukovoditel' | t }}</option>
                <option value="OBSERVER">{{ 'projects.rol_nablyudatel' | t }}</option>
              </select>
            </div>

            <!-- Submit Button -->
            <div class="add-btn-wrapper">
              <ui-button
                variant="primary"
                size="md"
                icon="add"
                [disabled]="!selectedUser || isAddingMember"
                [loading]="isAddingMember"
                (onClick)="submitAddMember()"
              >
                {{ 'common.add' | t }}
              </ui-button>
            </div>
          </div>
        </div>

        <!-- Members Table -->
        <div class="table-wrapper" role="region" [attr.aria-label]="'projects.tablica_uchastnikov_proekta' | t" tabindex="0">
          <ui-local-table
            data-testid="project-members-table"
            [rows]="rows()"
            [config]="config()"
            [sortValues]="sortValues"
            [loading]="isLoadingMembers"
            [emptyTemplate]="emptyMembers" />
        </div>
      </div>

      <div footer class="modal-footer-actions">
        <ui-button variant="secondary" size="md" (onClick)="close.emit()">
          {{ 'common.close' | t }}
        </ui-button>
      </div>
    </ui-modal>

    <ng-template #memberUserCell let-m>
      <div class="member-user-cell">
        <span class="user-avatar-mini" aria-hidden="true">{{ getInitials(m.userName) }}</span>
        <span class="font-medium">{{ m.userName }}</span>
      </div>
    </ng-template>
    <ng-template #memberEmailCell let-m><span class="text-muted tabular-nums">{{ m.userEmail || '—' }}</span></ng-template>
    <ng-template #memberAccessCell let-m>
      <ui-badge [variant]="accessVariant(m.accessKind)">{{ accessLabel(m.accessKind) }}</ui-badge>
    </ng-template>
    <ng-template #memberActionCell let-m>
      <div class="text-right">
        <ui-button
          variant="danger"
          size="sm"
          icon="delete"
          [ariaLabel]="'projects.remove_member_named' | t:{name: m.userName}"
          [loading]="removingUserId === m.userId"
          (onClick)="requestRemove(m)"
        >
          {{ 'common.delete' | t }}
        </ui-button>
      </div>
    </ng-template>
    <ng-template #emptyMembers><p class="empty-cell">{{ 'projects.net_uchastnikov_proekta' | t }}</p></ng-template>
  `,
  styles: [`
    .members-modal-body {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .add-member-panel {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .panel-header {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
      font-size: 14px;
      color: var(--text-main);
    }

    .panel-icon {
      font-size: 18px;
      color: var(--primary);
    }

    .add-member-controls {
      display: flex;
      align-items: flex-end;
      gap: 12px;
      flex-wrap: wrap;
    }

    .user-search-wrapper {
      position: relative;
      flex: 1;
      min-width: 220px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .access-kind-wrapper {
      min-width: 160px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .add-btn-wrapper {
      padding-bottom: 1px;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req {
      color: var(--danger);
    }

    .search-input-box {
      position: relative;
      display: flex;
      align-items: center;
    }

    .search-icon {
      position: absolute;
      left: 10px;
      font-size: 18px;
      color: var(--text-muted);
      pointer-events: none;
    }

    .form-input {
      width: 100%;
      box-sizing: border-box;
      padding: 8px 12px 8px 34px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s;
    }

    .form-select {
      padding-left: 10px;
      cursor: pointer;
    }

    .form-input:focus {
      border-color: var(--primary);
    }

    .clear-user-btn {
      position: absolute;
      right: 8px;
      background: none;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      padding: 2px;
    }

    .clear-user-btn:hover {
      color: var(--text-main);
    }

    .clear-user-btn span {
      font-size: 16px;
    }

    /* Dropdown popup */
    .user-dropdown-list {
      position: absolute;
      top: 100%;
      left: 0;
      right: 0;
      z-index: 20;
      margin-top: 4px;
      max-height: 200px;
      overflow-y: auto;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: var(--shadow-md);
      display: flex;
      flex-direction: column;
    }

    .user-dropdown-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      border: none;
      background: none;
      text-align: left;
      cursor: pointer;
      transition: background-color 0.1s;
      width: 100%;
    }

    .user-dropdown-item:hover:not(:disabled) {
      background-color: var(--bg-hover);
    }

    .user-dropdown-item:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .user-item-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
    }

    .user-item-name {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }

    .user-item-email {
      font-size: 11px;
    }

    .already-member-tag {
      font-size: 10px;
      color: var(--text-muted);
      font-style: italic;
    }

    .user-avatar-mini {
      width: 26px;
      height: 26px;
      border-radius: 50%;
      background-color: var(--primary);
      color: var(--on-primary);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 600;
      flex-shrink: 0;
    }

    /* Table styles */
    .table-wrapper {
      overflow-x: auto;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      background-color: var(--bg-surface);
    }


    .member-user-cell {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .font-medium {
      font-weight: 500;
    }

    .text-muted {
      color: var(--text-muted);
    }

    .tabular-nums {
      font-variant-numeric: tabular-nums;
    }

    .text-right {
      text-align: right;
    }

    .empty-cell {
      text-align: center;
      color: var(--text-muted);
      padding: 24px 14px;
      font-style: italic;
    }

    .modal-footer-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      width: 100%;
    }

    .remove-prompt-body {
      padding: 8px 0;
    }

    .remove-prompt-text {
      font-size: 14px;
      color: var(--text-main);
      margin: 0;
    }
  `]
})
export class ProjectMembersModalComponent {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);

  @Input() isOpen = false;
  @Input() project: Project | null = null;
  @Input() set members(members: ProjectMember[]) {
    this.rows.set(members ?? []);
  }
  get members(): ProjectMember[] {
    return this.rows();
  }
  @Input() isLoadingMembers = false;
  @Input() isAddingMember = false;
  /** The member whose removal is running, so only that row's button shows it. */
  @Input() removingUserId: number | null = null;
  @Input() set canUpdateProject(can: boolean) {
    this.canUpdate.set(can);
  }
  get canUpdateProject(): boolean {
    return this.canUpdate();
  }

  readonly rows = signal<ProjectMember[]>([]);
  private readonly canUpdate = signal(false);
  private readonly userCell = viewChild.required<TemplateRef<unknown>>('memberUserCell');
  private readonly emailCell = viewChild.required<TemplateRef<unknown>>('memberEmailCell');
  private readonly accessCell = viewChild.required<TemplateRef<unknown>>('memberAccessCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('memberActionCell');

  /** Every member of the project is loaded, so a header click sorts them all. */
  readonly sortValues = {
    user: (m: ProjectMember) => m.userName,
    email: (m: ProjectMember) => m.userEmail,
    access: (m: ProjectMember) => this.accessLabel(m.accessKind)
  };

  /** The remove column is there only for someone who may change the project. */
  readonly config = computed<TableConfig<ProjectMember>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const canUpdate = this.canUpdate();
    return {
      trackBy: (_index, m) => m.userId,
      ariaLabel: this.i18n.translate('projects.uchastniki_proekta'),
      layout: 'fit',
      columns: {
        user: { header: header('nav.users'), content: cell(this.userCell) },
        email: { header: header('iam.email'), content: cell(this.emailCell) },
        access: { header: header('projects.uroven_dostupa'), content: cell(this.accessCell), width: '170px' },
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '140px', align: 'right' }
      },
      columnsOrder: canUpdate ? ['user', 'email', 'access', 'action'] : ['user', 'email', 'access']
    };
  });

  @Output() close = new EventEmitter<void>();
  @Output() addMember = new EventEmitter<{ projectId: number; userId: number; accessKind: string }>();
  /** Asks the page to remove a member; the page confirms it first. */
  @Output() removeMember = new EventEmitter<{ projectId: number; userId: number; userName: string }>();

  userSearchQuery = '';
  foundUsers: User[] = [];
  selectedUser: User | null = null;
  selectedAccessKind = 'MEMBER';
  isUserDropdownOpen = false;

  private searchSubject = new Subject<string>();

  constructor() {
    this.searchSubject.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(query => {
        const trimmed = query.trim();
        if (!trimmed) {
          return of({ items: [] });
        }
        return this.api.get<{ items: User[] }>('/iam/users', {
          state: 'A',
          search: trimmed,
          limit: 15
        });
      })
    ).subscribe({
      next: res => {
        this.foundUsers = res.items || [];
        this.isUserDropdownOpen = true;
      },
      error: () => {
        this.foundUsers = [];
      }
    });
  }

  onSearchInput(query: string): void {
    if (this.selectedUser && query !== this.selectedUser.name) {
      this.selectedUser = null;
    }
    this.searchSubject.next(query);
  }

  selectUser(user: User): void {
    this.selectedUser = user;
    this.userSearchQuery = user.name;
    this.isUserDropdownOpen = false;
  }

  clearSelectedUser(): void {
    this.selectedUser = null;
    this.userSearchQuery = '';
    this.foundUsers = [];
    this.isUserDropdownOpen = false;
  }

  accessVariant(kind: string): 'info' | 'success' | 'neutral' {
    return kind === 'MANAGER' ? 'info' : kind === 'MEMBER' ? 'success' : 'neutral';
  }

  /** The role's name; a kind this screen does not know shows as it came. */
  accessLabel(kind: string): string {
    switch (kind) {
      case 'MANAGER': return this.i18n.translate('projects.rol_rukovoditel');
      case 'MEMBER': return this.i18n.translate('projects.rol_uchastnik');
      case 'OBSERVER': return this.i18n.translate('projects.rol_nablyudatel');
      default: return kind;
    }
  }

  isUserAlreadyMember(userId: number): boolean {
    return this.members.some(m => m.userId === userId);
  }

  getInitials(name: string | undefined): string {
    if (!name) return 'U';
    const parts = name.trim().split(' ');
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.slice(0, 2).toUpperCase();
  }

  submitAddMember(): void {
    if (!this.project || !this.selectedUser) return;
    this.addMember.emit({
      projectId: this.project.id,
      userId: this.selectedUser.id,
      accessKind: this.selectedAccessKind
    });
    this.clearSelectedUser();
  }

  requestRemove(member: ProjectMember): void {
    this.removeMember.emit({ projectId: member.projectId, userId: member.userId, userName: member.userName });
  }
}
