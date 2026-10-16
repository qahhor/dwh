import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  effect,
  untracked,
  inject,
  viewChild,
  input,
  linkedSignal,
  model,
  output,
  signal,
} from '@angular/core';

import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, map, of, Subject, switchMap, timer } from 'rxjs';
import { ProjectsApi } from '../projects.api';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { Project } from '@core/models/task.models';
import { User } from '@core/models/auth.models';
import { clearTaskErrors } from '@features/tasks/task-form-errors';
import { ProjectMember } from '../projects.models';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';

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
    SMTControlComponent,
    UiLocalTableComponent,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
  ],
  templateUrl: './project-members-modal.component.html',
  styleUrl: './project-members-modal.component.css',
})
export class ProjectMembersModalComponent {
  private readonly projectsApi = inject(ProjectsApi);
  private readonly i18n = inject(I18nService);

  readonly isLoadingMembers = input(false);
  /** More members follow those shown (plan item 3.5). */
  readonly hasMore = input(false);
  readonly isLoadingMore = input(false);
  readonly isAddingMember = input(false);
  readonly addedCount = input(0);
  /** The member whose removal is running, so only that row's button shows it. */
  readonly removingUserId = input<number | null>(null);

  readonly isOpen = input(false);
  readonly project = input<Project | null>(null);

  readonly members = input<ProjectMember[]>([]);
  readonly canUpdateProject = input<boolean>(false);

  readonly closeModal = output<void>();
  /** Asks for the next page of members. */
  readonly loadMore = output<void>();
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

  /** The server's refusal of the last added member, by field (`userId`, `accessKind`). */
  readonly addErrors = model<Readonly<Record<string, string>>>({});

  private readonly userCell = viewChild.required<TemplateRef<unknown>>('memberUserCell');
  private readonly emailCell = viewChild.required<TemplateRef<unknown>>('memberEmailCell');
  private readonly accessCell = viewChild.required<TemplateRef<unknown>>('memberAccessCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('memberActionCell');

  /** "Add" was pressed since the last added member, so an empty person field shows its error. */
  readonly addTried = linkedSignal({ source: () => [this.isOpen(), this.addedCount()], computation: () => false });
  readonly userTouched = linkedSignal({ source: () => [this.isOpen(), this.addedCount()], computation: () => false });

  readonly foundUsers = signal<User[]>([]);
  readonly isUserDropdownOpen = signal(false);

  /** Signals, since the search box, the result list and the role picker change them from callbacks. */
  readonly userSearchQuery = linkedSignal({ source: () => [this.isOpen(), this.addedCount()], computation: () => '' });
  readonly selectedUser = linkedSignal({
    source: () => [this.isOpen(), this.addedCount()],
    computation: (): User | null => null,
  });
  readonly selectedAccessKind = signal<'R' | 'W'>('R');

  readonly searchStatus = signal<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle');

  readonly pending = computed(() => this.isAddingMember() || this.removingUserId() !== null);

  readonly rows = computed<ProjectMember[]>(() => this.members() ?? []);

  /** The remove column is there only for someone who may change the project. */
  readonly config = computed<TableConfig<ProjectMember>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const canUpdate = this.canUpdate();
    return {
      trackBy: (_index, m) => m.userId,
      ariaLabel: this.i18n.translate('projects.common.project_members'),
      layout: 'fit',
      columns: {
        user: { header: header('nav.users'), content: cell(this.userCell) },
        email: { header: header('iam.email'), content: cell(this.emailCell) },
        access: { header: header('projects.members.access_level'), content: cell(this.accessCell), width: '170px' },
        action: {
          header: header('audit.common.action'),
          content: cell(this.actionCell),
          width: '140px',
          align: 'right',
        },
      },
      columnsOrder: canUpdate ? ['user', 'email', 'access', 'action'] : ['user', 'email', 'access'],
    };
  });
  private readonly canUpdate = computed(() => this.canUpdateProject());

  /** A header click sorts the members shown; the server sends them by name, a page at a time. */
  readonly sortValues = {
    user: (m: ProjectMember) => m.userName,
    email: (m: ProjectMember) => m.userEmail,
    access: (m: ProjectMember) => this.accessLabel(m.accessKind),
  };

  private readonly accessKindMemo = optionsMemo<SMTSelectOption<string>[]>();

  /** Query events reset immediately, including when the same query is entered after clearing. */
  private readonly searchRequests = new Subject<string>();

  constructor() {
    this.searchRequests
      .pipe(
        // Switching before the delay also cancels in-flight results as soon as the query is cleared or changed.
        switchMap((query) => {
          const trimmed = query.trim();
          this.foundUsers.set([]);
          this.isUserDropdownOpen.set(!!trimmed);
          this.searchStatus.set(trimmed ? 'loading' : 'idle');
          if (!trimmed) return of(undefined);
          return timer(250).pipe(
            switchMap(() => this.projectsApi.searchActiveUsers(trimmed)),
            map((res) => res.items || []),
            catchError(() => of(null)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((users) => {
        if (users === undefined) return;
        this.foundUsers.set(users ?? []);
        this.searchStatus.set(users === null ? 'error' : users.length ? 'ready' : 'empty');
      });
    effect(() => {
      this.isOpen();
      this.addedCount();
      this.project();
      untracked(() => {
        this.clearSelectedUser();
        this.selectedAccessKind.set('R');
      });
    });
  }

  /** Access levels a new member can get; translated again when the language changes. */
  accessKindOptions(): SMTSelectOption<string>[] {
    return this.accessKindMemo([this.i18n.currentLang()], () => [
      { id: 'R', label: this.i18n.translate('projects.members.access_read') },
      { id: 'W', label: this.i18n.translate('projects.members.access_write') },
    ]);
  }

  requestClose(): void {
    if (!this.pending()) this.closeModal.emit();
  }

  onSearchInput(value: SMTInputValue): void {
    const query = value === null ? '' : String(value);
    if (query !== this.userSearchQuery()) this.clearError('userId');
    this.userSearchQuery.set(query);
    const selected = this.selectedUser();
    if (selected && query !== selected.name) {
      this.selectedUser.set(null);
    }
    this.searchRequests.next(query);
  }

  selectUser(user: User): void {
    if (this.selectedUser()?.id !== user.id) this.clearError('userId');
    this.searchRequests.next('');
    this.selectedUser.set(user);
    this.userSearchQuery.set(user.name);
    this.isUserDropdownOpen.set(false);
  }

  clearSelectedUser(): void {
    this.clearError('userId');
    this.selectedUser.set(null);
    this.userSearchQuery.set('');
    this.searchRequests.next('');
  }

  retryUserSearch(): void {
    this.searchRequests.next(this.userSearchQuery());
  }

  touchUser(event: FocusEvent): void {
    const wrapper = event.currentTarget as HTMLElement;
    if (event.relatedTarget instanceof Node && wrapper.contains(event.relatedTarget)) return;
    this.userTouched.set(true);
  }

  changeAccessKind(value: string | null): void {
    if ((value !== 'R' && value !== 'W') || value === this.selectedAccessKind()) return;
    this.selectedAccessKind.set(value);
    this.clearError('accessKind');
  }

  accessVariant(kind: string): TBadgeVariant {
    return kind === 'W' ? 'blue' : 'gray';
  }

  /** The role's name; a kind this screen does not know shows as it came. */
  accessLabel(kind: string): string {
    switch (kind) {
      case 'R':
        return this.i18n.translate('projects.members.access_read');
      case 'W':
        return this.i18n.translate('projects.members.access_write');
      default:
        return kind;
    }
  }

  isUserAlreadyMember(userId: number): boolean {
    return this.members().some((m) => m.userId === userId);
  }

  /** "Required" after a press without a chosen person, else the server's word on the person. */
  userError(): string {
    if ((this.addTried() || this.userTouched()) && !this.selectedUser())
      return this.i18n.translate('ui.control.required');
    return this.addErrors()['userId'] ?? '';
  }

  /** Enter and "Add" land here; without a chosen person the field says so and takes the focus. */
  submitAddMember(): void {
    if (this.isAddingMember()) return;
    const project = this.project();
    const user = this.selectedUser();
    this.addTried.set(true);
    if (!project || !user) return;
    this.addTried.set(false);
    this.addMember.emit({
      projectId: project.id,
      userId: user.id,
      accessKind: this.selectedAccessKind(),
    });
  }

  requestRemove(member: ProjectMember): void {
    this.removeMember.emit({ projectId: member.projectId, userId: member.userId, userName: member.userName });
  }

  private clearError(field: string): void {
    this.addErrors.update((errors) => clearTaskErrors(errors, [field]));
  }
}
