import { Component, DestroyRef, OnDestroy, OnInit, computed, signal, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { FormsModule } from '@angular/forms';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { canonicalRecordId, recordResponseMatches, safeNumericRecordId } from '../../../core/services/search-target';
import { Subscription, Observable, finalize, tap } from 'rxjs';
import { SMTModalService } from '../../../shared/ui-kit/components/modal';
import { problemText } from '../../../shared/ui/problem-text';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { Project, ProjectTaskStats } from '../../../core/models/task.models';
import { CustomField } from '../../../core/models/custom-field.models';
import { ToastService } from '../../../core/services/toast.service';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import {
  ProjectCreateForm,
  ProjectEditForm,
  ProjectViewState,
  ProjectStateFilter,
  ProjectMember,
  ProjectListItem,
} from './projects.models';
import { ProjectFilterBarComponent } from './components/project-filter-bar.component';
import { ProjectTableViewComponent } from './components/project-table-view.component';
import { ProjectCardsViewComponent } from './components/project-cards-view.component';
import { ProjectModalsComponent } from './components/project-modals.component';
import { ProjectMembersModalComponent } from './components/project-members-modal.component';
import { ProjectFormsService } from './services/project-forms.service';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { KeysetPage } from '../../../core/models/common.models';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { QueryMetaService, parseSort, toQueryParams } from '../../../core/services/query-meta.service';
import { ListViewState, ListViewsApi } from '../../../shared/list-views/list-views';
import { TableColumnStateStore } from '../../../shared/ui-kit/services/table-column-state.store';
import { sortFromHeader } from '../../../shared/ui/registry-table-config';
import { OrderBy } from '../../../shared/ui-kit/components/table/table.types';
import { SMTAlertComponent } from '../../../shared/ui-kit/components/alert';
import {
  optionsMemo,
  SMTRadioGroupComponent,
  SMTRadioOption,
} from '../../../shared/ui-kit/components/forms/radio-group';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [
    SMTRadioGroupComponent,
    FormsModule,
    RouterModule,
    TranslatePipe,
    SMTButtonComponent,
    SMTAlertComponent,
    ProjectFilterBarComponent,
    ProjectTableViewComponent,
    ProjectCardsViewComponent,
    ProjectModalsComponent,
    ProjectMembersModalComponent,
  ],
  providers: [ProjectFormsService],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.css',
})
export class ProjectsComponent implements OnInit, OnDestroy {
  readonly forms = inject(ProjectFormsService);
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);
  private readonly recordRoute = inject(ActivatedRoute, { optional: true });

  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);

  readonly routeRecordId = signal<string | null>(null);
  readonly viewingProject = signal<Project | null>(null);
  readonly recordLoading = signal(false);
  readonly recordError = signal(false);
  readonly recordNotFound = signal(false);

  readonly selectedProjectForMembers = signal<Project | null>(null);
  readonly projectMembers = signal<ProjectMember[]>([]);
  readonly isLoadingMembers = signal<boolean>(false);
  readonly isAddingMember = signal<boolean>(false);
  readonly removingMemberId = signal<number | null>(null);

  /** Field metadata of the list (`query-meta/ms.projects`), roadmap item 51. */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly listLoaded = signal<boolean>(false);

  readonly projectCustomFields = signal<CustomField[]>([]);

  /** The counts the server sent with each row; empty for someone who may not view tasks. */
  readonly projectStats = computed<Record<number, ProjectTaskStats>>(() => {
    const stats: Record<number, ProjectTaskStats> = {};
    for (const project of this.projects()) {
      if (project.totalTasks == null || project.doneTasks == null) continue;
      stats[project.id] = {
        projectId: project.id,
        totalTasks: project.totalTasks,
        doneTasks: project.doneTasks,
        activeTasks: project.totalTasks - project.doneTasks,
      } as ProjectTaskStats;
    }
    return stats;
  });
  readonly statsLoaded = computed(() => this.canViewTasks() && this.isListReady());
  readonly statsLoading = computed(() => false);
  readonly statsLoadError = computed(() => false);

  private exportFilters: Record<string, string> = {};

  /** Sort, filter and columns of the list; saved views keep them under a name. */
  readonly views = new ListViewState('ms.projects', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.pager.first(),
    columnsStore: inject(TableColumnStateStore),
  });

  /* A page at a time from the server, which sorts the whole list (by progress too) and counts each
     project's tasks over the ones the viewer may see. The pager cancels a superseded request. */
  readonly pager = new KeysetPager<ProjectListItem>(
    (cursor, limit) =>
      this.api.get<KeysetPage<ProjectListItem>>(
        '/tasks/projects/page',
        {
          limit,
          cursor: cursor ?? undefined,
          ...this.flatFilters(),
          ...toQueryParams({
            sort: this.views.sort(),
            conditions: this.views.filter(),
            match: this.views.match(),
            search: this.searchQuery,
          }),
        },
        { notifyError: false },
      ),
    { pageSize: 10, destroyRef: this.destroyRef, onLoaded: () => this.listLoaded.set(true) },
  );
  readonly projects = this.pager.items;
  readonly isLoading = this.pager.loading;
  readonly listLoadError = this.pager.failed;

  private recordRouteSubscription?: Subscription;
  private recordRequest?: Subscription;
  private recordRequestId = 0;
  private searchTimer?: ReturnType<typeof setTimeout>;
  private destroyed = false;

  readonly isCreateModalOpen = this.forms.isCreateModalOpen;
  readonly isEditModalOpen = this.forms.isEditModalOpen;
  readonly isCreateDiscardConfirmationOpen = this.forms.isCreateDiscardConfirmationOpen;
  readonly isEditDiscardConfirmationOpen = this.forms.isEditDiscardConfirmationOpen;
  readonly isSubmitting = this.forms.isSubmitting;
  readonly editLoading = this.forms.editLoading;
  readonly editLoadError = this.forms.editLoadError;
  readonly createSaveError = this.forms.createSaveError;
  readonly editSaveError = this.forms.editSaveError;

  viewMode: ProjectViewState = 'list';
  searchQuery = '';
  selectedState: ProjectStateFilter = 'all';

  private readonly viewMemo = optionsMemo<SMTRadioOption<ProjectViewState>[]>();

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private router: Router,
  ) {}

  get createForm(): ProjectCreateForm {
    return this.forms.createForm;
  }
  set createForm(val: ProjectCreateForm) {
    this.forms.createForm = val;
  }

  get editForm(): ProjectEditForm {
    return this.forms.editForm;
  }
  set editForm(val: ProjectEditForm) {
    this.forms.editForm = val;
  }

  get isCreateSubmitted(): boolean {
    return this.forms.isCreateSubmitted;
  }
  set isCreateSubmitted(val: boolean) {
    this.forms.isCreateSubmitted = val;
  }

  get isEditSubmitted(): boolean {
    return this.forms.isEditSubmitted;
  }
  set isEditSubmitted(val: boolean) {
    this.forms.isEditSubmitted = val;
  }

  get editingProject(): Project | null {
    return this.forms.editingProject;
  }
  set editingProject(val: Project | null) {
    this.forms.editingProject = val;
  }

  ngOnInit() {
    this.forms.onProjectCreated = () => {
      this.searchQuery = '';
      this.selectedState = 'all';
      this.loadProjects();
    };
    this.forms.onProjectUpdated = () => this.loadProjects();
    this.loadProjects();
    this.loadProjectCustomFields();
    this.recordRouteSubscription = this.recordRoute?.paramMap?.subscribe((params) =>
      this.loadRecordView(params.get('id')),
    );
  }

  ngOnDestroy() {
    this.forms.destroy();
    this.recordRouteSubscription?.unsubscribe();
    this.recordRequest?.unsubscribe();
    this.recordRequestId++;
    this.destroyed = true;
    clearTimeout(this.searchTimer);
  }

  canCreateProject(): boolean {
    return this.forms.canCreateProject();
  }

  canUpdateProject(): boolean {
    return this.forms.canUpdateProject();
  }

  canViewTasks(): boolean {
    return this.permService.canView('tasks.items');
  }

  openCreateModal() {
    this.forms.openCreateModal();
  }
  requestCloseCreate() {
    this.forms.requestCloseCreate();
  }
  confirmDiscardCreate() {
    this.forms.confirmDiscardCreate();
  }
  submitCreateProject() {
    this.forms.submitCreateProject();
  }

  openEditModal(p: Project) {
    this.forms.openEditModal(p);
  }
  retryEditLoad() {
    this.forms.retryEditLoad();
  }
  requestCloseEdit() {
    this.forms.requestCloseEdit();
  }
  confirmDiscardEdit() {
    this.forms.confirmDiscardEdit();
  }
  submitEditProject() {
    this.forms.submitEditProject();
  }

  cancelNavigationDiscard(kind: 'create' | 'edit') {
    this.forms.cancelNavigationDiscard(kind);
  }
  canLeaveRecordPage(): boolean | Observable<boolean> | Promise<boolean> {
    return this.forms.canLeaveRecordPage();
  }

  /** The first page for the current filters; the list's metadata comes first, once. */
  loadProjects() {
    if (this.destroyed) return;
    clearTimeout(this.searchTimer);
    if (!this.meta()) {
      this.metaError.set(false);
      this.queryMeta
        .get('ms.projects')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (meta) => {
            this.meta.set(meta);
            this.views.load().subscribe(() => this.pager.first());
          },
          error: () => this.metaError.set(true),
        });
      return;
    }
    this.pager.first();
  }

  /** The search box asks the server after a short pause, so typing does not send a request per key. */
  setSearchQuery(value: string) {
    this.searchQuery = value;
    clearTimeout(this.searchTimer);
    this.pager.invalidate();
    this.searchTimer = setTimeout(() => this.loadProjects(), 300);
  }

  clearSearch() {
    this.searchQuery = '';
    this.loadProjects();
  }

  setSelectedState(state: ProjectStateFilter) {
    this.selectedState = state;
    this.loadProjects();
  }

  /** A header click sorts the whole list on the server. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined) {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.pager.first();
  }

  goToPage(page: number) {
    if (!this.isListReady()) return;
    this.pager.goTo(page);
  }

  isListReady(): boolean {
    return this.listLoaded() && !this.isLoading() && !this.listLoadError();
  }

  /** The state quick filter as an export option; the same object while it stays. */
  exportOptions(): Record<string, string> {
    const state = this.selectedState === 'all' ? undefined : this.selectedState;
    if ((this.exportFilters['state'] ?? undefined) !== state) this.exportFilters = state ? { state } : {};
    return this.exportFilters;
  }

  viewProjectTasks(project: Project) {
    if (!this.canViewTasks() || !safeNumericRecordId(project.id)) return;
    this.router.navigate(['/tasks'], { queryParams: { project_id: project.id } });
  }

  loadRecordView(id: string | null) {
    const requestId = ++this.recordRequestId;
    this.recordRequest?.unsubscribe();
    this.routeRecordId.set(id);
    this.viewingProject.set(null);
    this.recordLoading.set(false);
    this.recordError.set(false);
    this.recordNotFound.set(false);
    if (id === null) return;
    if (!canonicalRecordId(id)) {
      this.recordError.set(true);
      this.recordNotFound.set(true);
      return;
    }
    this.recordLoading.set(true);
    this.recordRequest = this.api.get<Project>(`/tasks/projects/${id}`, undefined, { notifyError: false }).subscribe({
      next: (project) => {
        if (this.destroyed || requestId !== this.recordRequestId) return;
        this.recordLoading.set(false);
        if (recordResponseMatches(project?.id, id)) this.viewingProject.set(project);
        else this.recordError.set(true);
      },
      error: (error) => {
        if (this.destroyed || requestId !== this.recordRequestId) return;
        this.recordLoading.set(false);
        this.recordError.set(true);
        this.recordNotFound.set(error?.status === 404 || error?.status === 403);
      },
    });
  }

  closeRecordView() {
    this.router.navigate(['/tasks/projects'], { queryParamsHandling: 'preserve' });
  }

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
    this.api.get<ProjectMember[]>(`/tasks/projects/${projectId}/members`).subscribe({
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
    this.api
      .post<void>(`/tasks/projects/${event.projectId}/members`, {
        userId: event.userId,
        accessKind: event.accessKind,
      })
      .subscribe({
        next: () => {
          this.isAddingMember.set(false);
          this.toast.success(this.uiI18n.translate('projects.uchastnik_uspeshno_dobavlen'));
          this.loadProjectMembers(event.projectId);
        },
        error: (err: any) => {
          this.isAddingMember.set(false);
          this.toast.error(err?.error?.detail || this.uiI18n.translate('projects.oshibka_dobavleniya_uchastnika'));
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
          return this.api
            .delete(`/tasks/projects/${event.projectId}/members/${event.userId}`, { notifyError: false })
            .pipe(
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

  loadProjectCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'PROJECT' }).subscribe({
      next: (res) => {
        if (Array.isArray(res)) {
          const validFields = res.filter(
            (f) => f && typeof f === 'object' && typeof f.code === 'string' && typeof f.fieldType === 'string',
          );
          this.projectCustomFields.set(validFields);
        } else {
          this.projectCustomFields.set([]);
        }
      },
      error: () => {},
    });
  }

  viewOptions(): SMTRadioOption<ProjectViewState>[] {
    return this.viewMemo([this.optionText.currentLang()], () => [
      {
        value: 'list',
        label: this.optionText.translate('projects.spisok'),
        icon: 'table_rows',
        title: this.optionText.translate('projects.spisok_tablica'),
      },
      { value: 'cards', label: this.optionText.translate('projects.kartochki'), icon: 'grid_view' },
    ]);
  }

  private flatFilters() {
    return { state: this.selectedState === 'all' ? undefined : this.selectedState };
  }
}
