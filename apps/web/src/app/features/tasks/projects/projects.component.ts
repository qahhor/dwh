import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnDestroy,
  OnInit,
  computed,
  signal,
  inject,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { RouterModule, Router, ActivatedRoute } from '@angular/router';
import { canonicalRecordId, recordResponseMatches, safeNumericRecordId } from '@core/services/search-target';
import { Subscription, Observable, catchError, map, of } from 'rxjs';
import { CustomFieldsApi } from '@core/services/custom-fields.api';
import { ProjectsApi } from './projects.api';
import { PermissionService } from '@core/services/permission.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { Project, ProjectTaskStats } from '@core/models/task.models';
import { CustomField } from '@core/models/custom-field.models';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import {
  ProjectCreateForm,
  ProjectEditForm,
  ProjectViewState,
  ProjectStateFilter,
  ProjectListItem,
} from './projects.models';
import { ProjectFilterBarComponent } from './components/project-filter-bar.component';
import { ProjectTableViewComponent } from './components/project-table-view.component';
import { ProjectCardsViewComponent } from './components/project-cards-view.component';
import { ProjectModalsComponent } from './components/project-modals.component';
import { ProjectMembersModalComponent } from './components/project-members-modal.component';
import { ProjectFormsService } from './services/project-forms.service';
import { ProjectMembersService } from './services/project-members.service';
import { KeysetPager } from '@shared/paging/keyset-pager';

import { QueryListMeta } from '@core/models/query-meta.models';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { sortFromHeader } from '@shared/ui/registry-table-config';
import { OrderBy } from '@shared/ui-kit/components/table/table.types';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { optionsMemo, SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-projects',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTRadioGroupComponent,
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
  providers: [ProjectFormsService, ProjectMembersService],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.css',
})
export class ProjectsComponent implements OnInit, OnDestroy {
  permService = inject(PermissionService);

  readonly forms = inject(ProjectFormsService);
  /** The members dialog: whose members are shown, adding and removing them. */
  readonly members = inject(ProjectMembersService);
  private router = inject(Router);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly customFieldsApi = inject(CustomFieldsApi);
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);
  private readonly recordRoute = inject(ActivatedRoute, { optional: true });

  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);

  readonly routeRecordId = signal<string | null>(null);
  readonly viewingProject = signal<Project | null>(null);
  readonly recordLoading = signal(false);
  readonly recordError = signal(false);
  readonly recordNotFound = signal(false);

  /** Field metadata of the list (`query-meta/ms.projects`), roadmap item 51. */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly listLoaded = signal<boolean>(false);

  readonly projectCustomFields = computed(() => this.customFields.value() ?? []);

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

  /** Only well-formed fields: a malformed one would break the record and the forms. */
  private readonly customFields = rxResource({
    stream: () =>
      this.customFieldsApi.list('PROJECT').pipe(
        map((res) =>
          Array.isArray(res)
            ? res.filter(
                (f) => f && typeof f === 'object' && typeof f.code === 'string' && typeof f.fieldType === 'string',
              )
            : [],
        ),
        catchError(() => of<CustomField[]>([])),
      ),
  });

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
      this.projectsApi.page(
        this.flatFilters(),
        {
          sort: this.views.sort(),
          conditions: this.views.filter(),
          match: this.views.match(),
          search: this.searchQuery,
        },
        cursor,
        limit,
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

  viewMode: ProjectViewState = 'list';
  searchQuery = '';
  selectedState: ProjectStateFilter = 'all';

  private readonly viewMemo = optionsMemo<SMTRadioOption<ProjectViewState>[]>();

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

  ngOnInit() {
    this.forms.onProjectCreated = () => {
      this.searchQuery = '';
      this.selectedState = 'all';
      this.loadProjects();
    };
    this.forms.onProjectUpdated = () => this.loadProjects();
    this.loadProjects();
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
    this.recordRequest = this.projectsApi.get(id).subscribe({
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
