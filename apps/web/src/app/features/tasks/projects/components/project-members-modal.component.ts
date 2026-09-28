import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  viewChild,
  input,
  output,
  signal,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { of, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { ProjectsApi } from '../projects.api';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiBadgeComponent } from '@shared/ui/ui-badge.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { Project } from '@core/models/task.models';
import { User } from '@core/models/auth.models';
import { ProjectMember } from '../projects.models';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';

@Component({
  selector: 'app-project-members-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    SMTAvatarComponent,
    FormsModule,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    UiBadgeComponent,
    UiLocalTableComponent,
  ],
  templateUrl: './project-members-modal.component.html',
  styles: [
    `
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
    `,
  ],
})
export class ProjectMembersModalComponent {
  private readonly projectsApi = inject(ProjectsApi);
  private readonly i18n = inject(I18nService);

  readonly isLoadingMembers = input(false);
  readonly isAddingMember = input(false);
  /** The member whose removal is running, so only that row's button shows it. */
  readonly removingUserId = input<number | null>(null);

  readonly isOpen = input(false);
  readonly project = input<Project | null>(null);

  readonly members = input<ProjectMember[]>([]);
  readonly canUpdateProject = input<boolean>(false);

  readonly close = output<void>();
  readonly addMember = output<{
    projectId: number;
    userId: number;
    accessKind: string;
  }>();
  /** Asks the page to remove a member; the page confirms it first. */
  readonly removeMember = output<{
    projectId: number;
    userId: number;
    userName: string;
  }>();

  private readonly userCell = viewChild.required<TemplateRef<unknown>>('memberUserCell');
  private readonly emailCell = viewChild.required<TemplateRef<unknown>>('memberEmailCell');
  private readonly accessCell = viewChild.required<TemplateRef<unknown>>('memberAccessCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('memberActionCell');

  readonly foundUsers = signal<User[]>([]);
  readonly isUserDropdownOpen = signal(false);

  readonly rows = computed<ProjectMember[]>(() => this.members() ?? []);

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
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '140px', align: 'right' },
      },
      columnsOrder: canUpdate ? ['user', 'email', 'access', 'action'] : ['user', 'email', 'access'],
    };
  });
  private readonly canUpdate = computed(() => this.canUpdateProject());

  /** Every member of the project is loaded, so a header click sorts them all. */
  readonly sortValues = {
    user: (m: ProjectMember) => m.userName,
    email: (m: ProjectMember) => m.userEmail,
    access: (m: ProjectMember) => this.accessLabel(m.accessKind),
  };

  userSearchQuery = '';
  selectedUser: User | null = null;
  selectedAccessKind = 'MEMBER';
  private readonly accessKindMemo = optionsMemo<SMTSelectOption<string>[]>();

  private searchSubject = new Subject<string>();

  constructor() {
    this.searchSubject
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((query) => {
          const trimmed = query.trim();
          if (!trimmed) {
            return of({ items: [] });
          }
          return this.projectsApi.searchActiveUsers(trimmed);
        }),
      )
      .subscribe({
        next: (res) => {
          this.foundUsers.set(res.items || []);
          this.isUserDropdownOpen.set(true);
        },
        error: () => {
          this.foundUsers.set([]);
        },
      });
  }

  /** Access levels a new member can get; translated again when the language changes. */
  accessKindOptions(): SMTSelectOption<string>[] {
    return this.accessKindMemo([this.i18n.currentLang()], () => [
      { id: 'MEMBER', label: this.i18n.translate('projects.rol_uchastnik') },
      { id: 'MANAGER', label: this.i18n.translate('projects.rol_rukovoditel') },
      { id: 'OBSERVER', label: this.i18n.translate('projects.rol_nablyudatel') },
    ]);
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
    this.isUserDropdownOpen.set(false);
  }

  clearSelectedUser(): void {
    this.selectedUser = null;
    this.userSearchQuery = '';
    this.foundUsers.set([]);
    this.isUserDropdownOpen.set(false);
  }

  accessVariant(kind: string): 'info' | 'success' | 'neutral' {
    return kind === 'MANAGER' ? 'info' : kind === 'MEMBER' ? 'success' : 'neutral';
  }

  /** The role's name; a kind this screen does not know shows as it came. */
  accessLabel(kind: string): string {
    switch (kind) {
      case 'MANAGER':
        return this.i18n.translate('projects.rol_rukovoditel');
      case 'MEMBER':
        return this.i18n.translate('projects.rol_uchastnik');
      case 'OBSERVER':
        return this.i18n.translate('projects.rol_nablyudatel');
      default:
        return kind;
    }
  }

  isUserAlreadyMember(userId: number): boolean {
    return this.members().some((m) => m.userId === userId);
  }

  submitAddMember(): void {
    const project = this.project();
    if (!project || !this.selectedUser) return;
    this.addMember.emit({
      projectId: project.id,
      userId: this.selectedUser.id,
      accessKind: this.selectedAccessKind,
    });
    this.clearSelectedUser();
  }

  requestRemove(member: ProjectMember): void {
    this.removeMember.emit({ projectId: member.projectId, userId: member.userId, userName: member.userName });
  }
}
