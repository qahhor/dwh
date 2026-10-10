import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { finalize, of, tap } from 'rxjs';
import { Project } from '@core/models/task.models';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { ProjectsApi } from '../projects.api';
import { ProjectMember } from '../projects.models';

/** Members read at once; the server allows up to 200. */
const MEMBERS_PAGE = 50;

/** The members dialog of the project screen: whose members are shown, and adding and removing them. */
@Injectable()
export class ProjectMembersService {
  private readonly projectsApi = inject(ProjectsApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly selectedProjectForMembers = signal<Project | null>(null);
  readonly isAddingMember = signal<boolean>(false);
  readonly removingMemberId = signal<number | null>(null);
  /** The server's refusal of the last added member, by field (`userId`, `accessKind`). */
  readonly addErrors = signal<Readonly<Record<string, string>>>({});

  readonly projectMembers = computed(() => this.pager.items());
  readonly isLoadingMembers = computed(() => this.pager.loading());
  readonly hasMoreMembers = computed(() => this.pager.canGoForward());
  readonly isLoadingMoreMembers = computed(() => this.pager.loadingMore());

  /**
   * The members of the project in the dialog, a page at a time by name (plan item 3.5): the first page when the
   * dialog opens and again after each change, the next ones by "load more". A failed read keeps what is shown.
   */
  private readonly pager = new KeysetPager<ProjectMember>(
    (cursor, limit) => {
      const project = this.selectedProjectForMembers();
      return project ? this.projectsApi.membersPage(project.id, cursor, limit) : of(null);
    },
    {
      pageSize: MEMBERS_PAGE,
      destroyRef: inject(DestroyRef),
      onError: () => this.toast.error(this.uiI18n.translate('projects.members.load_failed')),
    },
  );

  openMembersModal(project: Project): void {
    this.selectedProjectForMembers.set(project);
    this.addErrors.set({});
    this.pager.items.set([]);
    this.pager.first();
  }

  closeMembersModal(): void {
    this.selectedProjectForMembers.set(null);
    this.pager.cancel();
    this.pager.items.set([]);
  }

  loadMoreMembers(): void {
    this.pager.loadMore();
  }

  onAddProjectMember(event: { projectId: number; userId: number; accessKind: string }): void {
    if (this.isAddingMember()) return;
    this.isAddingMember.set(true);
    this.addErrors.set({});
    this.projectsApi.addMember(event.projectId, event.userId, event.accessKind).subscribe({
      next: () => {
        this.isAddingMember.set(false);
        this.toast.success(this.uiI18n.translate('projects.members.added'));
        this.pager.first();
      },
      error: (err: unknown) => {
        this.isAddingMember.set(false);
        // A refusal about the person or the access level goes under that field (forms standard, section 5).
        const { fields, other } = problemFieldErrors(err, { known: ['userId', 'accessKind'] });
        this.addErrors.set(fields);
        if (Object.keys(fields).length === 0 || other.length > 0) {
          this.toast.error(other[0] ?? (problemText(err) || this.uiI18n.translate('projects.members.add_failed')));
        }
      },
    });
  }

  /** Asks before removing a member; the dialog stays open until the server answers. */
  onRemoveProjectMember(event: { projectId: number; userId: number; userName: string }): void {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.modal
      .confirm({
        title: t('projects.members.remove_from_project'),
        message: t('projects.members.remove_confirm', { name: event.userName }),
        yesLabel: t('projects.members.remove_from_project'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () => {
          this.removingMemberId.set(event.userId);
          return this.projectsApi.removeMember(event.projectId, event.userId).pipe(
            tap(() => {
              this.toast.success(t('projects.members.removed'));
              this.pager.first();
            }),
            finalize(() => this.removingMemberId.set(null)),
          );
        },
        actionError: (error) => problemText(error) || t('projects.members.remove_failed'),
      })
      .subscribe();
  }
}
