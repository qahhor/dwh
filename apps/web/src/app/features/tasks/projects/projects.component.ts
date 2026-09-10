import { Component, OnDestroy, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { canonicalRecordId, recordResponseMatches, safeNumericRecordId } from '../../../core/services/search-target';
import { RecordNavigationDecision } from '../../../core/guards/record-navigation.guard';
import { Subscription } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { Project, ProjectTaskStats } from '../../../core/models/task.models';
import { CustomField } from '../../../core/models/custom-field.models';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { ProjectCreateForm, ProjectEditForm, ProjectViewState, ProjectStateFilter } from './projects.models';
import { ProjectFilterBarComponent } from './components/project-filter-bar.component';
import { ProjectTableViewComponent } from './components/project-table-view.component';
import { ProjectCardsViewComponent } from './components/project-cards-view.component';
import { ProjectModalsComponent } from './components/project-modals.component';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    TranslatePipe,
    UiButtonComponent,
    ProjectFilterBarComponent,
    ProjectTableViewComponent,
    ProjectCardsViewComponent,
    ProjectModalsComponent
  ],
  template: `
    <div class="projects-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'nav.projects' | t }}</h1>
          <span *ngIf="isListReady()" class="count-badge">{{ filteredProjects().length }}</span>

          <!-- View Mode Switcher -->
          <div class="status-tabs" role="group" [attr.aria-label]="'projects.rezhim_otobrazheniya_proektov' | t">
            <button
              type="button"
              class="status-tab"
              [class.active]="viewMode === 'list'"
              [attr.aria-pressed]="viewMode === 'list'"
              (click)="viewMode = 'list'"
              [title]="'projects.spisok_tablica' | t"
            >
              <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">table_rows</span>
              <span>{{ 'projects.spisok' | t }}</span>
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="viewMode === 'cards'"
              [attr.aria-pressed]="viewMode === 'cards'"
              (click)="viewMode = 'cards'"
              [title]="'projects.kartochki' | t"
            >
              <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">grid_view</span>
              <span>{{ 'projects.kartochki' | t }}</span>
            </button>
          </div>
        </div>

        <div class="header-right">
          <ui-button
            *ngIf="canCreateProject()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            {{ 'projects.novyy_proekt' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Toolbar / Filter Bar -->
      <app-project-filter-bar
        [searchQuery]="searchQuery"
        [selectedState]="selectedState"
        (searchChange)="setSearchQuery($event)"
        (clearSearch)="clearSearch()"
        (stateChange)="setSelectedState($event)"
      ></app-project-filter-bar>

      <div
        *ngIf="isLoading()"
        class="request-state"
        data-testid="projects-list-loading"
        role="status"
        aria-live="polite"
      >
        {{ 'projects.loading_projects' | t }}
      </div>
      <div *ngIf="listLoadError()" class="request-state request-error" data-testid="projects-list-error" role="alert">
        <span>{{ 'projects.load_projects_error' | t }}</span>
        <button type="button" class="request-retry projects-list-retry" (click)="loadProjects()">
          {{ 'projects.retry_projects' | t }}
        </button>
      </div>

      <div
        *ngIf="!canViewTasks()"
        class="stats-state"
        data-testid="projects-stats-permission"
        role="status"
      >
        {{ 'projects.stats_permission' | t }}
      </div>
      <div
        *ngIf="canViewTasks() && statsLoading()"
        class="stats-state"
        data-testid="projects-stats-loading"
        role="status"
        aria-live="polite"
      >
        {{ 'projects.loading_stats' | t }}
      </div>
      <div
        *ngIf="canViewTasks() && statsLoadError()"
        class="stats-state request-error"
        data-testid="projects-stats-error"
        role="alert"
      >
        <span>{{ 'projects.load_stats_error' | t }}</span>
        <button type="button" class="request-retry projects-stats-retry" (click)="loadStats()">
          {{ 'projects.retry_stats' | t }}
        </button>
      </div>
      <div *ngIf="canViewTasks() && statsLoaded()" class="stats-state" data-testid="projects-stats-scope" role="status">
        {{ 'projects.closed_stats_scope' | t }}
      </div>

      <!-- VIEW 1: TABLE / LIST VIEW -->
      <app-project-table-view
        *ngIf="viewMode === 'list' && isListReady()"
        [paginatedProjects]="paginatedProjects()"
        [totalCount]="filteredProjects().length"
        [currentPage]="currentPage"
        [pageSize]="pageSize"
        [canViewTasks]="canViewTasks()"
        [canUpdateProject]="canUpdateProject()"
        [projectStats]="projectStats()"
        [statsLoading]="statsLoading()"
        [statsLoadError]="statsLoadError()"
        [statsLoaded]="statsLoaded()"
        (viewTasks)="viewProjectTasks($event)"
        (editProject)="openEditModal($event)"
        (pageChange)="setPage($event)"
        (pageSizeChange)="setPageSize($event)"
      ></app-project-table-view>

      <!-- VIEW 2: CARDS GRID VIEW -->
      <app-project-cards-view
        *ngIf="viewMode === 'cards' && isListReady()"
        [paginatedProjects]="paginatedProjects()"
        [totalCount]="filteredProjects().length"
        [currentPage]="currentPage"
        [pageSize]="pageSize"
        [canViewTasks]="canViewTasks()"
        [canUpdateProject]="canUpdateProject()"
        [projectStats]="projectStats()"
        [statsLoading]="statsLoading()"
        [statsLoadError]="statsLoadError()"
        [statsLoaded]="statsLoaded()"
        (viewTasks)="viewProjectTasks($event)"
        (editProject)="openEditModal($event)"
        (pageChange)="setPage($event)"
        (pageSizeChange)="setPageSize($event)"
      ></app-project-cards-view>
    </div>

    <!-- Modals -->
    <app-project-modals
      [routeRecordId]="routeRecordId()"
      [viewingProject]="viewingProject()"
      [recordLoading]="recordLoading()"
      [recordError]="recordError()"
      [recordNotFound]="recordNotFound()"
      (closeRecordView)="closeRecordView()"
      (loadRecordView)="loadRecordView($event)"

      [isCreateModalOpen]="isCreateModalOpen()"
      [isCreateSubmitted]="isCreateSubmitted"
      [isCreateDiscardConfirmationOpen]="isCreateDiscardConfirmationOpen()"
      [createSaveError]="createSaveError()"
      [createForm]="createForm"
      (requestCloseCreate)="requestCloseCreate()"
      (confirmDiscardCreate)="confirmDiscardCreate()"
      (submitCreateProject)="submitCreateProject()"

      [isEditModalOpen]="isEditModalOpen()"
      [isEditSubmitted]="isEditSubmitted"
      [isEditDiscardConfirmationOpen]="isEditDiscardConfirmationOpen()"
      [editLoading]="editLoading()"
      [editLoadError]="editLoadError()"
      [editSaveError]="editSaveError()"
      [editingProject]="editingProject"
      [editForm]="editForm"
      (requestCloseEdit)="requestCloseEdit()"
      (confirmDiscardEdit)="confirmDiscardEdit()"
      (submitEditProject)="submitEditProject()"
      (retryEditLoad)="retryEditLoad()"

      (cancelNavigationDiscard)="cancelNavigationDiscard($event)"

      [isSubmitting]="isSubmitting()"
      [projectCustomFields]="projectCustomFields()"
    ></app-project-modals>
  `,
  styles: [`
    .projects-page {
      display: flex;
      flex-direction: column;
      gap: 16px;
      width: 100%;
    }

    /* Header */
    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .header-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .view-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .count-badge {
      font-size: 12px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 7px;
      border-radius: 10px;
      font-weight: 500;
      border: 1px solid var(--border-color);
    }

    .header-right { display: flex; align-items: center; gap: 8px; }

    .status-tabs {
      display: inline-flex;
      align-items: center;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
    }
    .status-tab {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border: none;
      background: transparent;
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 500;
      border-radius: var(--radius-xs);
      cursor: pointer;
      transition: all 0.1s ease;
    }
    .status-tab:hover { color: var(--text-main); }
    .status-tab.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
    }

    .request-state,
    .stats-state {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      min-height: 40px;
      padding: 9px 12px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      font-size: 12px;
    }
    .request-error {
      border-color: var(--danger);
      background-color: var(--danger-bg);
      color: var(--danger);
    }
    .request-retry {
      min-height: 28px;
      padding: 3px 9px;
      border: 1px solid currentColor;
      border-radius: var(--radius-xs);
      background-color: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      font-weight: 600;
    }
  `]
})
export class ProjectsComponent implements OnInit, OnDestroy {
  private readonly uiI18n = inject(I18nService);
  private readonly recordRoute = inject(ActivatedRoute, { optional: true });
  private readonly navigationDecision = new RecordNavigationDecision();
  private recordRouteSubscription?: Subscription;
  private recordRequest?: Subscription;
  private recordRequestId = 0;
  readonly routeRecordId = signal<string | null>(null);
  readonly viewingProject = signal<Project | null>(null);
  readonly recordLoading = signal(false);
  readonly recordError = signal(false);
  readonly recordNotFound = signal(false);
  private listRequest?: Subscription;
  private statsRequest?: Subscription;
  private editDetailRequest?: Subscription;
  private createSaveRequest?: Subscription;
  private editSaveRequest?: Subscription;
  private editDetailRequestId = 0;
  private createSaveRequestId = 0;
  private editSaveRequestId = 0;
  private destroyed = false;

  readonly projects = signal<Project[]>([]);
  readonly projectStats = signal<Record<number, ProjectTaskStats>>({});
  readonly isLoading = signal<boolean>(false);
  readonly listLoadError = signal<boolean>(false);
  readonly listLoaded = signal<boolean>(false);
  readonly statsLoading = signal<boolean>(false);
  readonly statsLoadError = signal<boolean>(false);
  readonly statsLoaded = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly editLoading = signal<boolean>(false);
  readonly editLoadError = signal<boolean>(false);
  readonly createSaveError = signal<string | null>(null);
  readonly editSaveError = signal<string | null>(null);

  viewMode: ProjectViewState = 'list';
  searchQuery = '';
  selectedState: ProjectStateFilter = 'all';
  currentPage = 1;
  pageSize = 10;

  isCreateSubmitted = false;
  isEditSubmitted = false;

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);
  readonly isCreateDiscardConfirmationOpen = signal<boolean>(false);
  readonly isEditDiscardConfirmationOpen = signal<boolean>(false);

  readonly projectCustomFields = signal<CustomField[]>([]);
  createForm: ProjectCreateForm = { name: '', description: '', attributes: {} };
  editForm: ProjectEditForm = { name: '', description: '', state: 'A', attributes: {} };
  editingProject: Project | null = null;
  private createFormBaseline: ProjectCreateForm = { name: '', description: '', attributes: {} };
  private editFormBaseline: ProjectEditForm | null = null;
  private editTargetId: number | null = null;

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService,
    private router: Router
  ) {}

  ngOnInit() {
    this.loadProjects();
    this.loadStats();
    this.loadProjectCustomFields();
    this.recordRouteSubscription = this.recordRoute?.paramMap?.subscribe(params => this.loadRecordView(params.get('id')));
  }

  ngOnDestroy() {
    this.navigationDecision.settle(false);
    this.recordRouteSubscription?.unsubscribe();
    this.recordRequest?.unsubscribe();
    this.recordRequestId++;
    this.destroyed = true;
    this.listRequest?.unsubscribe();
    this.statsRequest?.unsubscribe();
    this.editDetailRequestId++;
    this.createSaveRequestId++;
    this.editSaveRequestId++;
    this.editDetailRequest?.unsubscribe();
    this.createSaveRequest?.unsubscribe();
    this.editSaveRequest?.unsubscribe();
  }

  canCreateProject(): boolean {
    return this.permService.canCreate('tasks.projects');
  }

  canUpdateProject(): boolean {
    return this.permService.canUpdate('tasks.projects');
  }

  canViewTasks(): boolean {
    return this.permService.canView('tasks.items');
  }

  loadProjects(focusProjectId?: number) {
    if (this.destroyed) return;

    this.listRequest?.unsubscribe();
    this.isLoading.set(true);
    this.listLoadError.set(false);
    this.listLoaded.set(false);
    this.projects.set([]);
    this.listRequest = this.api.get<Project[]>('/tasks/projects', undefined, { notifyError: false }).subscribe({
      next: res => {
        if (this.destroyed) return;
        this.isLoading.set(false);
        const projects = res || [];
        this.projects.set(projects);
        this.listLoaded.set(true);

        const filteredProjects = this.filteredProjects();
        if (focusProjectId !== undefined) {
          const projectIndex = filteredProjects.findIndex(project => project.id === focusProjectId);
          if (projectIndex >= 0) {
            this.currentPage = Math.floor(projectIndex / this.pageSize) + 1;
            return;
          }
        }

        this.clampCurrentPage();
      },
      error: () => {
        if (this.destroyed) return;
        this.isLoading.set(false);
        this.listLoadError.set(true);
      }
    });
  }

  loadStats() {
    this.statsRequest?.unsubscribe();
    this.projectStats.set({});
    this.statsLoading.set(false);
    this.statsLoadError.set(false);
    this.statsLoaded.set(false);

    if (this.destroyed || !this.canViewTasks()) return;

    this.statsLoading.set(true);
    this.statsRequest = this.api.get<ProjectTaskStats[]>('/tasks/projects/stats', undefined, { notifyError: false }).subscribe({
      next: res => {
        if (this.destroyed) return;
        const map: Record<number, ProjectTaskStats> = {};
        for (const s of res || []) {
          map[s.projectId] = s;
        }
        this.projectStats.set(map);
        this.statsLoading.set(false);
        this.statsLoaded.set(true);
      },
      error: () => {
        if (this.destroyed) return;
        this.statsLoading.set(false);
        this.statsLoadError.set(true);
      }
    });
  }

  setSearchQuery(value: string) {
    this.searchQuery = value;
    this.currentPage = 1;
  }

  clearSearch() {
    this.searchQuery = '';
    this.currentPage = 1;
  }

  setSelectedState(state: ProjectStateFilter) {
    this.selectedState = state;
    this.currentPage = 1;
  }

  setPage(page: number) {
    if (!this.isListReady()) return;
    this.currentPage = page;
  }

  setPageSize(pageSize: number) {
    if (!this.isListReady()) return;
    this.pageSize = pageSize;
    this.currentPage = 1;
  }

  isListReady(): boolean {
    return this.listLoaded() && !this.isLoading() && !this.listLoadError();
  }

  private clampCurrentPage() {
    const lastPage = Math.max(1, Math.ceil(this.filteredProjects().length / this.pageSize));
    this.currentPage = Math.min(this.currentPage, lastPage);
  }

  filteredProjects(): Project[] {
    const q = this.searchQuery.trim().toLowerCase();
    const st = this.selectedState;

    return this.projects().filter(p => {
      const matchSearch = !q || p.name.toLowerCase().includes(q) || (p.description && p.description.toLowerCase().includes(q));
      const matchState = st === 'all' || p.state === st;
      return matchSearch && matchState;
    });
  }

  paginatedProjects(): Project[] {
    const list = this.filteredProjects();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }

  hasProjectStats(projectId: number): boolean {
    return this.canViewTasks()
      && this.statsLoaded()
      && !this.statsLoading()
      && !this.statsLoadError()
      && this.projectStats()[projectId] !== undefined;
  }

  getProjectTotalCount(projectId: number): number {
    return this.projectStats()[projectId]?.totalTasks ?? 0;
  }

  getProjectDoneCount(projectId: number): number {
    return this.projectStats()[projectId]?.doneTasks ?? 0;
  }

  getProjectPercent(projectId: number): number {
    const stats = this.projectStats()[projectId];
    if (!stats) return 0;
    const total = stats.totalTasks;
    if (total === 0) return 0;
    return Math.round((stats.doneTasks / total) * 100);
  }

  openCreateModal() {
    if (
      this.destroyed
      || !this.canCreateProject()
      || this.isSubmitting()
      || this.isCreateModalOpen()
      || this.isEditModalOpen()
      || this.isCreateDiscardConfirmationOpen()
      || this.isEditDiscardConfirmationOpen()
    ) return;
    this.isCreateSubmitted = false;
    this.createForm = { name: '', description: '' };
    this.createFormBaseline = { ...this.createForm };
    this.createSaveError.set(null);
    this.isCreateDiscardConfirmationOpen.set(false);
    this.isCreateModalOpen.set(true);
  }

  requestCloseCreate() {
    if (this.destroyed || this.isSubmitting() || !this.isCreateModalOpen()) return;
    if (this.isCreateDraftDirty()) {
      this.isCreateDiscardConfirmationOpen.set(true);
      return;
    }
    this.closeCreateModal();
  }

  confirmDiscardCreate() {
    if (
      this.destroyed
      || this.isSubmitting()
      || !this.isCreateModalOpen()
      || !this.isCreateDiscardConfirmationOpen()
    ) return;
    this.closeCreateModal();
    this.navigationDecision.settle(true);
  }

  submitCreateProject() {
    if (
      this.destroyed
      || !this.canCreateProject()
      || !this.isCreateModalOpen()
      || this.isEditModalOpen()
      || this.isCreateDiscardConfirmationOpen()
      || this.isEditDiscardConfirmationOpen()
      || this.isSubmitting()
    ) return;
    this.isCreateSubmitted = true;
    if (!this.createForm.name.trim()) {
      this.toast.warning(this.uiI18n.translate('projects.vvedite_nazvanie_proekta'));
      return;
    }

    const requestId = ++this.createSaveRequestId;
    this.createSaveError.set(null);
    this.isSubmitting.set(true);
    const payload: any = {
      name: this.createForm.name.trim(),
      description: this.createForm.description.trim()
    };
    if (this.createForm.attributes && Object.keys(this.createForm.attributes).length > 0) {
      payload.attributes = this.createForm.attributes;
    }
    this.createSaveRequest = this.api.post<Project>('/tasks/projects', payload).subscribe({
      next: created => {
        if (
          this.destroyed
          || requestId !== this.createSaveRequestId
          || !this.isCreateModalOpen()
          || this.isEditModalOpen()
        ) return;
        this.isSubmitting.set(false);
        this.closeCreateModal();
        this.toast.success(this.uiI18n.translate('projects.proekt_uspeshno_sozdan'));
        this.searchQuery = '';
        this.selectedState = 'all';
        this.loadProjects(created.id);
        this.loadStats();
      },
      error: err => {
        if (
          this.destroyed
          || requestId !== this.createSaveRequestId
          || !this.isCreateModalOpen()
          || this.isEditModalOpen()
        ) return;
        this.isSubmitting.set(false);
        this.createSaveError.set(err?.detail || this.uiI18n.translate('projects.create_save_error'));
      }
    });
  }

  openEditModal(p: Project) {
    if (!safeNumericRecordId(p.id)) return;
    if (
      this.destroyed
      || !this.canUpdateProject()
      || this.isSubmitting()
      || this.isCreateModalOpen()
      || this.isEditModalOpen()
      || this.isCreateDiscardConfirmationOpen()
      || this.isEditDiscardConfirmationOpen()
    ) return;
    this.isEditSubmitted = false;
    this.editTargetId = p.id;
    this.editingProject = null;
    this.editFormBaseline = null;
    this.editSaveError.set(null);
    this.isEditDiscardConfirmationOpen.set(false);
    this.isEditModalOpen.set(true);
    this.loadEditDetails(p.id);
  }

  retryEditLoad() {
    if (
      this.destroyed
      || this.isSubmitting()
      || this.editLoading()
      || !this.isEditModalOpen()
      || this.isCreateModalOpen()
      || this.isCreateDiscardConfirmationOpen()
      || this.isEditDiscardConfirmationOpen()
      || this.editTargetId == null
    ) return;
    this.loadEditDetails(this.editTargetId);
  }

  requestCloseEdit() {
    if (this.destroyed || this.isSubmitting() || !this.isEditModalOpen()) return;
    if (this.isEditDraftDirty()) {
      this.isEditDiscardConfirmationOpen.set(true);
      return;
    }
    this.closeEditModal();
  }

  confirmDiscardEdit() {
    if (
      this.destroyed
      || this.isSubmitting()
      || !this.isEditModalOpen()
      || !this.isEditDiscardConfirmationOpen()
    ) return;
    this.closeEditModal();
    this.navigationDecision.settle(true);
  }

  submitEditProject() {
    if (
      this.destroyed
      || !this.canUpdateProject()
      || !this.isEditModalOpen()
      || this.isCreateModalOpen()
      || this.isCreateDiscardConfirmationOpen()
      || this.isEditDiscardConfirmationOpen()
      || !this.editingProject
      || !this.editFormBaseline
      || this.editLoading()
      || this.editLoadError()
      || this.isSubmitting()
    ) return;
    this.isEditSubmitted = true;
    if (!this.editForm.name.trim()) {
      this.toast.warning(this.uiI18n.translate('projects.nazvanie_proekta_obyazatelno'));
      return;
    }

    const payload: Record<string, unknown> = {};
    const name = this.editForm.name.trim();
    const description = this.editForm.description.trim();
    if (name !== this.editFormBaseline.name) payload['name'] = name;
    if (description !== this.editFormBaseline.description) payload['description'] = description;
    if (this.editForm.state !== this.editFormBaseline.state) payload['state'] = this.editForm.state;
    if (JSON.stringify(this.editForm.attributes || {}) !== JSON.stringify(this.editFormBaseline.attributes || {})) {
      payload['attributes'] = this.editForm.attributes;
    }
    if (Object.keys(payload).length === 0) {
      this.closeEditModal();
      return;
    }

    const editedProjectId = this.editingProject.id;
    const requestId = ++this.editSaveRequestId;
    this.editSaveError.set(null);
    this.isSubmitting.set(true);
    this.editSaveRequest = this.api.patch<void>(`/tasks/projects/${editedProjectId}`, payload).subscribe({
      next: () => {
        if (
          this.destroyed
          || requestId !== this.editSaveRequestId
          || !this.isEditModalOpen()
          || this.editingProject?.id !== editedProjectId
        ) return;
        this.isSubmitting.set(false);
        this.closeEditModal();
        this.toast.success(this.uiI18n.translate('projects.proekt_obnovlen'));
        this.loadProjects();
        this.loadStats();
      },
      error: err => {
        if (
          this.destroyed
          || requestId !== this.editSaveRequestId
          || !this.isEditModalOpen()
          || this.editingProject?.id !== editedProjectId
        ) return;
        this.isSubmitting.set(false);
        this.editSaveError.set(err?.detail || this.uiI18n.translate('projects.edit_save_error'));
      }
    });
  }

  private loadEditDetails(projectId: number) {
    const requestId = ++this.editDetailRequestId;
    this.editDetailRequest?.unsubscribe();
    this.editLoading.set(true);
    this.editLoadError.set(false);
    this.editSaveError.set(null);
    this.editingProject = null;
    this.editFormBaseline = null;

    this.editDetailRequest = this.api.get<Project>(`/tasks/projects/${projectId}`, undefined, { notifyError: false }).subscribe({
      next: project => {
        if (
          this.destroyed
          || requestId !== this.editDetailRequestId
          || !this.isEditModalOpen()
          || this.isCreateModalOpen()
          || this.editTargetId !== projectId
        ) return;
        this.editLoading.set(false);
        if (!project || project.id !== projectId) {
          this.editLoadError.set(true);
          return;
        }
        const normalized: ProjectEditForm = {
          name: project.name.trim(),
          description: (project.description || '').trim(),
          state: project.state
        };
        if (project.attributes && Object.keys(project.attributes).length > 0) {
          normalized.attributes = { ...project.attributes };
        }
        this.editingProject = project;
        this.editForm = { ...normalized };
        this.editFormBaseline = { ...normalized, ...(normalized.attributes ? { attributes: { ...normalized.attributes } } : {}) };
      },
      error: () => {
        if (
          this.destroyed
          || requestId !== this.editDetailRequestId
          || !this.isEditModalOpen()
          || this.editTargetId !== projectId
        ) return;
        this.editLoading.set(false);
        this.editLoadError.set(true);
      }
    });
  }

  private isCreateDraftDirty(): boolean {
    return this.createForm.name !== this.createFormBaseline.name
      || this.createForm.description !== this.createFormBaseline.description
      || (!!this.createForm.attributes && Object.keys(this.createForm.attributes).length > 0);
  }

  private isEditDraftDirty(): boolean {
    return !!this.editFormBaseline && (
      this.editForm.name !== this.editFormBaseline.name
      || this.editForm.description !== this.editFormBaseline.description
      || this.editForm.state !== this.editFormBaseline.state
      || JSON.stringify(this.editForm.attributes || {}) !== JSON.stringify(this.editFormBaseline.attributes || {})
    );
  }

  private closeCreateModal() {
    this.createSaveRequestId++;
    this.createSaveRequest?.unsubscribe();
    this.isCreateModalOpen.set(false);
    this.isCreateDiscardConfirmationOpen.set(false);
    this.createSaveError.set(null);
    this.createForm = { name: '', description: '' };
    this.createFormBaseline = { ...this.createForm };
  }

  private closeEditModal() {
    this.editDetailRequestId++;
    this.editSaveRequestId++;
    this.editDetailRequest?.unsubscribe();
    this.editSaveRequest?.unsubscribe();
    this.isEditModalOpen.set(false);
    this.isEditDiscardConfirmationOpen.set(false);
    this.editLoading.set(false);
    this.editLoadError.set(false);
    this.editSaveError.set(null);
    this.editingProject = null;
    this.editTargetId = null;
    this.editFormBaseline = null;
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
      this.recordError.set(true); this.recordNotFound.set(true); return;
    }
    this.recordLoading.set(true);
    this.recordRequest = this.api.get<Project>(`/tasks/projects/${id}`, undefined, { notifyError: false }).subscribe({
      next: project => {
        if (this.destroyed || requestId !== this.recordRequestId) return;
        this.recordLoading.set(false);
        if (recordResponseMatches(project?.id, id)) this.viewingProject.set(project);
        else this.recordError.set(true);
      },
      error: error => {
        if (this.destroyed || requestId !== this.recordRequestId) return;
        this.recordLoading.set(false); this.recordError.set(true);
        this.recordNotFound.set(error?.status === 404 || error?.status === 403);
      }
    });
  }

  closeRecordView() { this.router.navigate(['/tasks/projects'], { queryParamsHandling: 'preserve' }); }

  cancelNavigationDiscard(kind: 'create' | 'edit') {
    (kind === 'create' ? this.isCreateDiscardConfirmationOpen : this.isEditDiscardConfirmationOpen).set(false);
    this.navigationDecision.settle(false);
  }

  canLeaveRecordPage() {
    if (this.isSubmitting()) return false;
    const dialog = this.isCreateModalOpen() && this.isCreateDraftDirty() ? this.isCreateDiscardConfirmationOpen :
      this.isEditModalOpen() && this.isEditDraftDirty() ? this.isEditDiscardConfirmationOpen : null;
    if (dialog) return this.navigationDecision.request(() => dialog.set(true), () => dialog.set(false));
    if (this.isCreateModalOpen()) this.closeCreateModal();
    if (this.isEditModalOpen()) this.closeEditModal();
    return true;
  }

  loadProjectCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'PROJECT' }).subscribe({
      next: res => {
        if (Array.isArray(res)) {
          const validFields = res.filter(f => f && typeof f === 'object' && typeof f.code === 'string' && typeof f.fieldType === 'string');
          this.projectCustomFields.set(validFields);
        } else {
          this.projectCustomFields.set([]);
        }
      },
      error: () => {}
    });
  }

  hasAttributes(attrs: any): boolean {
    if (!attrs || typeof attrs !== 'object') return false;
    return Object.keys(attrs).length > 0;
  }

  formatAttributes(attrs: any): Array<{ key: string; value: string }> {
    if (!this.hasAttributes(attrs)) return [];
    const fields = this.projectCustomFields();
    return Object.entries(attrs).map(([k, v]) => {
      const field = fields.find(f => f.code === k);
      const keyLabel = field ? field.name : k;
      let valueStr = String(v ?? '');
      if (field?.fieldType === 'boolean') {
        valueStr = v === true || v === 'true'
          ? this.uiI18n.translate('common.yes')
          : this.uiI18n.translate('common.no');
      } else if (field?.fieldType === 'select' && field.optionsJson) {
        try {
          const opts = JSON.parse(field.optionsJson);
          if (Array.isArray(opts)) {
            const matched = opts.find(o => typeof o === 'object' && o !== null ? o.value === v : o === v);
            if (matched && typeof matched === 'object' && matched.label) {
              valueStr = matched.label;
            }
          }
        } catch {
          // ignore
        }
      }
      return { key: keyLabel, value: valueStr };
    });
  }
}
