import { Injectable, ResourceRef, computed, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, finalize, map, of, tap } from 'rxjs';
import { Project } from '@core/models/task.models';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { ProjectsApi } from '../projects.api';
import { ProjectMember } from '../projects.models';

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

  readonly projectMembers = computed(() => this.membersResource.value());
  readonly isLoadingMembers = computed(() => this.membersResource.isLoading());

  /** The members of the project in the dialog, read again after each change; a closed dialog has none. */
  private readonly membersResource: ResourceRef<ProjectMember[]> = rxResource({
    params: () => this.selectedProjectForMembers()?.id,
    defaultValue: [],
    stream: ({ params: projectId }) =>
      this.projectsApi.members(projectId).pipe(
        map((res) => res || []),
        catchError(() => {
          this.toast.error(this.uiI18n.translate('projects.oshibka_zagruzki_uchastnikov'));
          // A failed reload keeps the members on screen.
          return of(untracked(this.membersResource.value));
        }),
      ),
  });

  openMembersModal(project: Project): void {
    this.selectedProjectForMembers.set(project);
  }

  closeMembersModal(): void {
    this.selectedProjectForMembers.set(null);
  }

  onAddProjectMember(event: { projectId: number; userId: number; accessKind: string }): void {
    this.isAddingMember.set(true);
    this.projectsApi.addMember(event.projectId, event.userId, event.accessKind).subscribe({
      next: () => {
        this.isAddingMember.set(false);
        this.toast.success(this.uiI18n.translate('projects.uchastnik_uspeshno_dobavlen'));
        this.membersResource.reload();
      },
      error: (err: unknown) => {
        this.isAddingMember.set(false);
        this.toast.error(problemText(err) || this.uiI18n.translate('projects.oshibka_dobavleniya_uchastnika'));
      },
    });
  }

  /** Asks before removing a member; the dialog stays open until the server answers. */
  onRemoveProjectMember(event: { projectId: number; userId: number; userName: string }): void {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.modal
      .confirm({
        title: t('projects.udalit_iz_proekta'),
        message: t('projects.vy_uvereny_chto_hotite_udalit_uchastnika', { name: event.userName }),
        yesLabel: t('projects.udalit_iz_proekta'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () => {
          this.removingMemberId.set(event.userId);
          return this.projectsApi.removeMember(event.projectId, event.userId).pipe(
            tap(() => {
              this.toast.success(t('projects.uchastnik_uspeshno_udalen'));
              this.membersResource.reload();
            }),
            finalize(() => this.removingMemberId.set(null)),
          );
        },
        actionError: (error) => problemText(error) || t('projects.oshibka_udaleniya_uchastnika'),
      })
      .subscribe();
  }
}
