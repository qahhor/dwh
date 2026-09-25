import { Component, OnInit, OnDestroy, signal, HostListener, ElementRef, ViewChild, inject, DestroyRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subscription, finalize, tap } from 'rxjs';
import { SMTModalService } from '../../../shared/ui-kit/components/modal';
import { problemText } from '../../../shared/ui/problem-text';
import { canonicalRecordId, recordResponseMatches, safeNumericRecordId } from '../../../core/services/search-target';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { User, UserSecuritySummary } from '../../../core/models/auth.models';
import { Role } from '../../../core/models/rbac.models';
import { CustomField } from '../../../core/models/custom-field.models';
import { KeysetPage } from '../../../core/models/common.models';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { collectKeyset } from '../../../shared/paging/collect-keyset';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UserOrgUnitsPanelComponent } from '../org-units/public-api';
import { UserFilterBarComponent } from './components/user-filter-bar.component';
import { UserTableViewComponent } from './components/user-table-view.component';
import { UserCreateModalComponent } from './components/user-create-modal.component';
import { UserEditModalComponent } from './components/user-edit-modal.component';
import { UserDetailModalComponent } from './components/user-detail-modal.component';
import {
  UserCreateForm,
  UserEditForm,
  getManagerName,
  getUserRoleNames,
  generateSecurePassword,
  copyPasswordToClipboard,
  hasMinLength,
  hasUpperAndLower,
  hasDigitsOrSymbols,
  doesNotContainLogin,
  calculatePasswordStrength,
  exportUsersToCsv
} from './users.models';
import { UserSecurityService } from './services/user-security.service';
import { UserFormsService } from './services/user-forms.service';
import { UserFilterService } from './services/user-filter.service';
import { UserDirectoryService } from './services/user-directory.service';

const EXPORT_PAGE_SIZE = 200;
/** Enough for any real organisation's user list; a broader export is a report, not a CSV from the screen. */
const EXPORT_MAX_ROWS = 10_000;

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [
    TranslatePipe,
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UserFilterBarComponent,
    UserTableViewComponent,
    UserCreateModalComponent,
    UserEditModalComponent,
    UserDetailModalComponent
  ],
  providers: [UserDirectoryService],
  templateUrl: './users.component.html',
  styleUrl: './users.component.css'
})
export class UsersComponent implements OnInit, OnDestroy {
  readonly getUserRoleNamesFn = (u: User) => getUserRoleNames(u, this.roles());
  readonly getManagerNameFn = (u: User) => getManagerName(u, id => this.directory.nameOf(id));

  private readonly uiI18n = inject(I18nService);
  private readonly recordRoute = inject(ActivatedRoute, { optional: true });
  private readonly recordRouter = inject(Router, { optional: true });
  public readonly secService = inject(UserSecurityService);
  public readonly formsService = inject(UserFormsService);
  public readonly filterService = inject(UserFilterService);
  public readonly directory = inject(UserDirectoryService);

  private recordRouteSubscription?: Subscription;
  private queryParamSubscription?: Subscription;
  private recordRequest?: Subscription;
  private panelLeaveSubscription?: Subscription;
  private recordRequestId = 0;
  private searchDebounceTimer: any = null;
  private destroyed = false;

  readonly routeRecordId = signal<string | null>(null);
  readonly recordLoading = signal(false);
  readonly recordError = signal(false);
  readonly recordNotFound = signal(false);
  readonly orgPanelBusy = signal(false);
  readonly safeRecordId = safeNumericRecordId;

  /* A page at a time, in the server's order. The pager cancels a superseded
     request, so a slower answer to an earlier search or filter never replaces
     the newer result. */
  readonly userPager = new KeysetPager<User>((cursor, limit) => this.fetchUsers(cursor, limit), {
    pageSize: 20,
    destroyRef: inject(DestroyRef),
    onLoaded: rows => {
      this.directory.remember(rows);
      this.directory.resolve(rows.map(user => user.managerId));
    }
  });
  readonly users = this.userPager.items;
  readonly isExporting = signal(false);
  private exportRequest?: Subscription;
  readonly roles = signal<Role[]>([]);
  readonly customFields = signal<CustomField[]>([]);
  readonly isLoading = this.userPager.loading;

  // Delegated signals and getters
  get isSubmitting() { return this.formsService.isSubmitting; }
  get isCreateModalOpen() { return this.formsService.isCreateModalOpen; }
  get isEditModalOpen() { return this.formsService.isEditModalOpen; }
  get showPassword() { return this.formsService.showPassword; }
  get isFilterMenuOpen() { return this.filterService.isFilterMenuOpen; }
  get userSecurity() { return this.secService.userSecurity; }
  get isLoadingSecurity() { return this.secService.isLoadingSecurity; }
  get isSecurityActionPending() { return this.secService.isSecurityActionPending; }

  get isCreateSubmitted() { return this.formsService.isCreateSubmitted; }
  set isCreateSubmitted(v: boolean) { this.formsService.isCreateSubmitted = v; }
  get isEditSubmitted() { return this.formsService.isEditSubmitted; }
  set isEditSubmitted(v: boolean) { this.formsService.isEditSubmitted = v; }
  get createForm(): UserCreateForm { return this.formsService.createForm; }
  set createForm(form: UserCreateForm) { this.formsService.createForm = form; }
  get editForm(): UserEditForm { return this.formsService.editForm; }
  set editForm(form: UserEditForm) { this.formsService.editForm = form; }
  get editingUser() { return this.formsService.editingUser; }
  set editingUser(u: User | null) { this.formsService.editingUser = u; }

  // Filter & Pagination properties
  get searchQuery() { return this.filterService.searchQuery; }
  set searchQuery(v: string) { this.filterService.searchQuery = v; }
  get selectedState() { return this.filterService.selectedState; }
  set selectedState(v: string) { this.filterService.selectedState = v; }
  get selectedRoleId() { return this.filterService.selectedRoleId; }
  set selectedRoleId(v: number | null) { this.filterService.selectedRoleId = v; }
  get selected2fa() { return this.filterService.selected2fa; }
  set selected2fa(v: boolean | null) { this.filterService.selected2fa = v; }

  private readonly modal = inject(SMTModalService);
  readonly isViewModalOpen = signal<boolean>(false);
  readonly activeViewTab = signal<'info' | 'security' | 'orgUnits' | 'permissions'>('info');

  viewingUser: User | null = null;

  @ViewChild('filterTrigger') private filterTrigger?: ElementRef<HTMLButtonElement>;
  @ViewChild(UserDetailModalComponent) private userDetailModal?: UserDetailModalComponent;
  get userOrgUnitsPanel(): UserOrgUnitsPanelComponent | undefined {
    return this.userDetailModal?.orgUnitsPanel;
  }

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService,
    private elementRef: ElementRef,
    public i18n: I18nService
  ) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.isFilterMenuOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (!this.isFilterMenuOpen()) return;
    this.isFilterMenuOpen.set(false);
    queueMicrotask(() => this.filterTrigger?.nativeElement.focus());
  }

  ngOnInit() {
    this.recordRouteSubscription = this.recordRoute?.paramMap?.subscribe(params => this.loadRecordView(params.get('id')));
    this.queryParamSubscription = this.recordRoute?.queryParamMap?.subscribe(params => {
      const roleParam = params.get('roleId');
      if (roleParam && !isNaN(Number(roleParam))) {
        this.selectedRoleId = Number(roleParam);
      }
    });
    this.loadRoles();
    this.loadUsers(true);
    this.loadCustomFields();
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.panelLeaveSubscription?.unsubscribe();
    this.recordRouteSubscription?.unsubscribe();
    this.queryParamSubscription?.unsubscribe();
    this.recordRequest?.unsubscribe();
    this.recordRequestId++;
    clearTimeout(this.searchDebounceTimer);
    this.exportRequest?.unsubscribe();
  }

  // Permissions
  canCreateUser() { return this.permService.canCreate('iam.users') || this.permService.canCreate('md_users'); }
  canUpdateUser() { return this.permService.canUpdate('iam.users') || this.permService.canUpdate('md_users'); }
  canDeleteUser() { return this.permService.canDelete('iam.users') || this.permService.canDelete('md_users'); }
  canBlockUser() { return this.permService.hasPermission('iam.users', 'block') || this.permService.hasPermission('md_users', 'block'); }
  canUnblockUser() { return this.permService.hasPermission('iam.users', 'unblock') || this.permService.hasPermission('md_users', 'unblock'); }
  canViewOrgUnits() {
    return this.permService.hasPermission('iam.org_units', 'view') ||
           this.permService.hasPermission('iam.org_units', 'assign') ||
           this.orgPanelBusy();
  }
  canViewAssignments() {
    return this.permService.hasPermission('rbac.assignments', 'view') ||
           this.permService.hasPermission('rbac.assignments', 'assign');
  }
  canAssignPermissions() {
    return this.permService.hasPermission('rbac.assignments', 'assign');
  }
  canLeaveRecordPage(): boolean | Observable<boolean> {
    return this.userOrgUnitsPanel?.canLeave() ?? true;
  }

  private afterOrgPanelLeave(action: () => void): void {
    if (this.destroyed) return;
    this.panelLeaveSubscription?.unsubscribe();
    this.panelLeaveSubscription = undefined;
    const decision = this.userOrgUnitsPanel?.canLeave() ?? true;
    if (typeof decision === 'boolean') {
      if (decision) action();
      return;
    }
    this.panelLeaveSubscription = decision.subscribe((allow: boolean) => {
      if (allow && !this.destroyed) action();
    });
  }

  /** The first page for the current filters; without `reset`, the page on screen again. */
  loadUsers(reset: boolean = false) {
    if (reset) this.userPager.first();
    else this.userPager.reload();
  }

  private listFilters() {
    return {
      search: this.searchQuery ? this.searchQuery.trim() : undefined,
      state: this.selectedState || undefined,
      role_id: this.selectedRoleId || undefined,
      is_2fa_enabled: this.selected2fa !== null ? this.selected2fa : undefined
    };
  }

  private fetchUsers(cursor: string | null, limit: number) {
    return this.api.get<KeysetPage<User>>('/iam/users', { limit, cursor: cursor ?? undefined, ...this.listFilters() });
  }

  loadRoles() {
    this.api.get<Role[]>('/rbac/roles').subscribe({
      next: res => this.roles.set(res || []),
      error: () => {
        this.api.get<Role[]>('/iam/roles').subscribe({
          next: res => this.roles.set(res || []),
          error: () => {}
        });
      }
    });
  }

  loadCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'USER' }).subscribe(res => {
      this.customFields.set(res || []);
    });
  }

  // Filter delegates
  hasExtraFilters() { return this.filterService.hasExtraFilters(); }
  resetExtraFilters() { this.filterService.resetExtraFilters(() => this.loadUsers(true)); }
  clearStateFilter() { this.filterService.clearStateFilter(() => this.loadUsers(true)); }
  clear2faFilter() { this.filterService.clear2faFilter(() => this.loadUsers(true)); }
  hasAnyActiveFilters() { return this.filterService.hasAnyActiveFilters(); }
  resetAllFilters() {
    this.filterService.resetAllFilters(
      () => this.recordRouter?.navigate([], { relativeTo: this.recordRoute, queryParams: { roleId: null }, queryParamsHandling: 'merge' }),
      () => this.loadUsers(true)
    );
  }
  toggleFilterMenu(event: MouseEvent) { this.filterService.toggleFilterMenu(event); }
  setStateFilter(state: string) { this.filterService.setStateFilter(state, () => this.loadUsers(true)); }
  onSearchInput() {
    clearTimeout(this.searchDebounceTimer);
    // The search text has already changed: an answer or a next page of the
    // old query must not land (or page) under it while the user types.
    this.userPager.invalidate();
    this.searchDebounceTimer = setTimeout(() => this.loadUsers(true), 250);
  }
  clearSearch() { this.searchQuery = ''; this.loadUsers(true); }
  getSelectedRoleName() { return this.filterService.getSelectedRoleName(this.roles()); }
  clearRoleFilter() {
    this.filterService.clearRoleFilter(
      () => this.recordRouter?.navigate([], { relativeTo: this.recordRoute, queryParams: { roleId: null }, queryParamsHandling: 'merge' }),
      () => this.loadUsers(true)
    );
  }

  // User display helpers
  getManagerName(user: User) { return getManagerName(user, id => this.directory.nameOf(id)); }
  getUserRoleNames(user: User) { return getUserRoleNames(user, this.roles()); }

  // Modals & Record View
  loadRecordView(id: string | null) {
    if (this.destroyed) return;
    const requestId = ++this.recordRequestId;
    this.recordRequest?.unsubscribe();
    this.routeRecordId.set(id);
    this.viewingUser = null;
    this.isViewModalOpen.set(id !== null);
    this.recordLoading.set(false);
    this.recordError.set(false);
    this.recordNotFound.set(false);
    this.activeViewTab.set('info');
    this.userSecurity.set(null);
    if (id === null) return;
    if (!canonicalRecordId(id)) {
      this.recordError.set(true); this.recordNotFound.set(true); return;
    }
    this.recordLoading.set(true);
    this.recordRequest = this.api.get<User>(`/iam/users/${id}`, undefined, { notifyError: false }).subscribe({
      next: user => {
        if (requestId !== this.recordRequestId) return;
        this.recordLoading.set(false);
        if (recordResponseMatches(user?.id, id)) {
          this.viewingUser = user;
          this.directory.resolve([user.managerId]);
        }
        else this.recordError.set(true);
      },
      error: error => {
        if (requestId !== this.recordRequestId) return;
        this.recordLoading.set(false); this.recordError.set(true);
        this.recordNotFound.set(error?.status === 404 || error?.status === 403);
      }
    });
  }

  closeRecordView() {
    if (this.destroyed) return;
    if (this.routeRecordId() !== null) {
      this.recordRouter?.navigate(['/iam/users'], { queryParamsHandling: 'preserve' });
      return;
    }
    this.afterOrgPanelLeave(() => this.isViewModalOpen.set(false));
  }

  openViewModal(user: User) {
    if (this.destroyed) return;
    const routeId = this.routeRecordId();
    if (routeId !== null) {
      if (!safeNumericRecordId(user.id)) return;
      if (String(user.id) === routeId) this.afterOrgPanelLeave(() => this.loadRecordView(routeId));
      else this.recordRouter?.navigate(['/iam/users', String(user.id)], { queryParamsHandling: 'preserve' });
      return;
    }
    this.afterOrgPanelLeave(() => {
      this.viewingUser = user;
      this.activeViewTab.set('info');
      this.userSecurity.set(null);
      this.isViewModalOpen.set(true);
    });
  }

  openEditFromView() {
    if (this.viewingUser && safeNumericRecordId(this.viewingUser.id) && this.canUpdateUser()) {
      const u = this.viewingUser;
      this.afterOrgPanelLeave(() => {
        this.isViewModalOpen.set(false);
        this.openEditModal(u);
      });
    }
  }

  openCreateModal() {
    this.formsService.openCreateModal(this.roles());
  }
  submitCreateUser() { this.formsService.submitCreateUser(() => this.loadUsers(true)); }

  openEditModal(user: User) {
    this.formsService.openEditModal(user);
  }
  submitEditUser() {
    this.formsService.submitEditUser(
      () => this.destroyed,
      () => this.loadUsers(),
      (sessionId) => this.closeEditModal(sessionId)
    );
  }

  closeEditModal(expectedSessionId?: number) {
    if (this.destroyed) return;
    if (expectedSessionId !== undefined && expectedSessionId !== this.formsService.editSessionId) return;
    const closedSessionId = ++this.formsService.editSessionId;
    this.isEditModalOpen.set(false);
    this.editingUser = null;
    const routeId = this.routeRecordId();
    if (routeId !== null) this.afterOrgPanelLeave(() => {
      if (closedSessionId === this.formsService.editSessionId && routeId === this.routeRecordId()) {
        this.loadRecordView(routeId);
      }
    });
  }

  /** Asks before deleting and anonymising a user; the dialog stays open until the server answers. */
  openDeleteConfirmModal(user: User) {
    const t = (key: string, params?: Record<string, string>) => this.uiI18n.translate(key, params);
    this.modal.confirm({
      title: t('iam.udalenie_polzovatelya'),
      message: `${t('iam.delete_user_question', { name: user.name, login: user.login })}\n${t('iam.personalnye_dannye_budut_sterty_a_aktivnye_sessi')}`,
      yesLabel: t('common.delete'),
      noLabel: t('common.cancel'),
      destructive: true,
      action: () => {
        this.isSubmitting.set(true);
        return this.api.delete(`/iam/users/${user.id}`, { notifyError: false }).pipe(
          tap(() => {
            this.toast.success(t('iam.polzovatel_uspeshno_udalen'));
            this.loadUsers();
          }),
          finalize(() => this.isSubmitting.set(false))
        );
      },
      actionError: problemText
    }).subscribe();
  }

  toggleUserState(user: User, action: 'block' | 'unblock') {
    this.api.post(`/iam/users/${user.id}/${action}`).subscribe({
      next: () => {
        this.toast.success(action === 'block' ? this.uiI18n.translate('iam.polzovatel_zablokirovan') : this.uiI18n.translate('iam.polzovatel_razblokirovan'));
        this.loadUsers();
      }
    });
  }

  /**
   * Every user the filters match, not only the page on screen, walked page by
   * page with the filters as they were when the export started.
   */
  exportToCsv() {
    if (this.isExporting()) return;
    const filters = this.listFilters();
    this.isExporting.set(true);
    this.exportRequest = collectKeyset<User>(
      (cursor, limit) => this.api.get<KeysetPage<User>>('/iam/users', { limit, cursor: cursor ?? undefined, ...filters }, { notifyError: false }),
      EXPORT_PAGE_SIZE,
      EXPORT_MAX_ROWS
    ).subscribe({
      next: ({ rows, complete }) => {
        this.isExporting.set(false);
        exportUsersToCsv(rows, this.uiI18n, this.toast);
        if (!complete) this.toast.warning(this.uiI18n.translate('iam.users.export_truncated', { count: rows.length }));
      },
      error: () => {
        this.isExporting.set(false);
        this.toast.error(this.uiI18n.translate('iam.users.export_failed'));
      }
    });
  }

  // Security actions
  switchViewTab(tab: 'info' | 'security' | 'orgUnits' | 'permissions', userId?: number) {
    this.activeViewTab.set(tab);
    if (tab === 'security' && userId && (!this.userSecurity() || this.userSecurity()?.userId !== userId)) {
      this.loadUserSecurity(userId);
    }
  }

  loadUserSecurity(userId: number) { this.secService.loadUserSecurity(userId); }
  terminateUserSessions(userId: number) { this.secService.terminateUserSessions(userId); }
  terminateSingleSession(sessionId: number, userId: number) { this.secService.terminateSingleSession(sessionId, userId); }
  forcePasswordChange(userId: number) { this.secService.forcePasswordChange(userId, () => this.loadUsers()); }
  resetUser2fa(userId: number) { this.secService.resetUser2fa(userId, () => this.loadUsers()); }

  // Password helpers
  generateSecurePassword(): string {
    const pwd = generateSecurePassword(this.createForm.login);
    this.createForm.password = pwd;
    return pwd;
  }
  copyGeneratedPassword(): Promise<void> {
    return copyPasswordToClipboard(this.createForm.password, this.toast, this.uiI18n);
  }
  passwordStrength() {
    return calculatePasswordStrength(this.createForm.password, this.createForm.login, this.uiI18n);
  }
  hasMinLength(): boolean { return hasMinLength(this.createForm.password); }
  hasUpperAndLower(): boolean { return hasUpperAndLower(this.createForm.password); }
  hasDigitsOrSymbols(): boolean { return hasDigitsOrSymbols(this.createForm.password); }
  doesNotContainLogin(): boolean { return doesNotContainLogin(this.createForm.password, this.createForm.login); }
}
