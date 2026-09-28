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

import { of, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { ProjectsApi } from '../projects.api';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { Project } from '@core/models/task.models';
import { User } from '@core/models/auth.models';
import { ProjectMember } from '../projects.models';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

@Component({
  selector: 'app-project-members-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
    SMTAvatarComponent,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    SMTBadgeComponent,
    UiLocalTableComponent,
  ],
  templateUrl: './project-members-modal.component.html',
  styleUrl: './project-members-modal.component.css',
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

  readonly closeModal = output<void>();
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

  /** Signals, since the search box, the result list and the role picker change them from callbacks. */
  readonly userSearchQuery = signal('');
  readonly selectedUser = signal<User | null>(null);
  readonly selectedAccessKind = signal('MEMBER');

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

  onSearchInput(value: SMTInputValue): void {
    const query = value === null ? '' : String(value);
    this.userSearchQuery.set(query);
    const selected = this.selectedUser();
    if (selected && query !== selected.name) {
      this.selectedUser.set(null);
    }
    this.searchSubject.next(query);
  }

  selectUser(user: User): void {
    this.selectedUser.set(user);
    this.userSearchQuery.set(user.name);
    this.isUserDropdownOpen.set(false);
  }

  clearSelectedUser(): void {
    this.selectedUser.set(null);
    this.userSearchQuery.set('');
    this.foundUsers.set([]);
    this.isUserDropdownOpen.set(false);
  }

  accessVariant(kind: string): TBadgeVariant {
    return kind === 'MANAGER' ? 'blue' : kind === 'MEMBER' ? 'success' : 'gray';
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
    const user = this.selectedUser();
    if (!project || !user) return;
    this.addMember.emit({
      projectId: project.id,
      userId: user.id,
      accessKind: this.selectedAccessKind(),
    });
    this.clearSelectedUser();
  }

  requestRemove(member: ProjectMember): void {
    this.removeMember.emit({ projectId: member.projectId, userId: member.userId, userName: member.userName });
  }
}
