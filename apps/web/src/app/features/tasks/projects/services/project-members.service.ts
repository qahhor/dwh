import { Injectable, inject, signal } from '@angular/core';
import { finalize, tap } from 'rxjs';
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
  readonly projectMembers = signal<ProjectMember[]>([]);
  readonly isLoadingMembers = signal<boolean>(false);
  readonly isAddingMember = signal<boolean>(false);
  readonly removingMemberId = signal<number | null>(null);

  openMembersModal(project: Project): void {
    this.selectedProjectForMembers.set(project);
    this.loadProjectMembers(project.id);
  }

  closeMembersModal(): void {
    this.selectedProjectForMembers.set(null);
    this.projectMembers.set([]);
  }

  loadProjectMembers(projectId: number): void {
    this.isLoadingMembers.set(true);
    this.projectsApi.members(projectId).subscribe({
      next: (res) => {
        this.projectMembers.set(res || []);
        this.isLoadingMembers.set(false);
      },
      error: () => {
        this.isLoadingMembers.set(false);
        this.toast.error(this.uiI18n.translate('projects.oshibka_zagruzki_uchastnikov'));
      },
    });
  }

  onAddProjectMember(event: { projectId: number; userId: number; accessKind: string }): void {
    this.isAddingMember.set(true);
    this.projectsApi.addMember(event.projectId, event.userId, event.accessKind).subscribe({
      next: () => {
        this.isAddingMember.set(false);
        this.toast.success(this.uiI18n.translate('projects.uchastnik_uspeshno_dobavlen'));
        this.loadProjectMembers(event.projectId);
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
              this.loadProjectMembers(event.projectId);
            }),
            finalize(() => this.removingMemberId.set(null)),
          );
        },
        actionError: (error) => problemText(error) || t('projects.oshibka_udaleniya_uchastnika'),
      })
      .subscribe();
  }
}
