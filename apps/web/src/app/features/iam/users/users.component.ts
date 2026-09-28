import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  OnDestroy,
  signal,
  HostListener,
  ElementRef,
  inject,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { canonicalRecordId, recordResponseMatches, safeNumericRecordId } from '@core/services/search-target';

import { UsersApi } from './users.api';
import { PermissionService } from '@core/services/permission.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { User } from '@core/models/auth.models';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UserOrgUnitsPanelComponent } from '../org-units/public-api';
import { UserFilterBarComponent } from './components/user-filter-bar.component';
import { UserTableViewComponent } from './components/user-table-view.component';
import { UserCreateModalComponent } from './components/user-create-modal.component';
import { UserEditModalComponent } from './components/user-edit-modal.component';
import { UserDetailModalComponent } from './components/user-detail-modal.component';
import { UserEditForm, getManagerName, getUserRoleNames } from './users.models';
import { UserSecurityService } from './services/user-security.service';
import { UserFormsService } from './services/user-forms.service';
import { UserFilterService } from './services/user-filter.service';
import { UserDirectoryService } from './services/user-directory.service';
import { UsersListFacade } from './services/users-list.facade';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    TranslatePipe,
    SMTButtonComponent,
    SMTAlertComponent,
    UserFilterBarComponent,
    UserTableViewComponent,
    UserCreateModalComponent,
    UserEditModalComponent,
    UserDetailModalComponent,
  ],
  providers: [UserDirectoryService, UsersListFacade],
  templateUrl: './users.component.html',
  styleUrl: './users.component.css',
})
export class UsersComponent implements OnInit, OnDestroy {
  public readonly secService = inject(UserSecurityService);
  public readonly formsService = inject(UserFormsService);
  public readonly filterService = inject(UserFilterService);
  public readonly directory = inject(UserDirectoryService);
  /** The list: metadata, views, pager, quick filters and row actions. */
  public readonly list = inject(UsersListFacade);

  private readonly usersApi = inject(UsersApi);
  private readonly recordRoute = inject(ActivatedRoute, { optional: true });
  private readonly recordRouter = inject(Router, { optional: true });

  private readonly filterBar = viewChild(UserFilterBarComponent);
  private readonly userDetailModal = viewChild(UserDetailModalComponent);

  readonly routeRecordId = signal<string | null>(null);
  readonly recordLoading = signal(false);
  readonly recordError = signal(false);
  readonly recordNotFound = signal(false);
  readonly orgPanelBusy = signal(false);
  readonly isViewModalOpen = signal<boolean>(false);
  readonly activeViewTab = signal<'info' | 'security' | 'orgUnits' | 'permissions'>('info');

  readonly viewingUser = signal<User | null>(null);

  readonly getUserRoleNamesFn = (u: User) => getUserRoleNames(u, this.list.roles());
  readonly getManagerNameFn = (u: User) => getManagerName(u, (id) => this.directory.nameOf(id));

  private recordRouteSubscription?: Subscription;
  private recordRequest?: Subscription;
  private panelLeaveSubscription?: Subscription;
  private recordRequestId = 0;
  private destroyed = false;
  readonly safeRecordId = safeNumericRecordId;

  // The record-navigation guard tests drive the page through users,
  // isEditModalOpen, searchQuery and editForm, so these stay on the page.
  readonly users = this.list.users;
  readonly isEditModalOpen = this.formsService.isEditModalOpen;

  constructor(
    public permService: PermissionService,
    private elementRef: ElementRef,
    public i18n: I18nService,
  ) {}

  get searchQuery() {
    return this.filterService.searchQuery;
  }
  set searchQuery(v: string) {
    this.filterService.searchQuery = v;
  }
  get editForm(): UserEditForm {
    return this.formsService.editForm;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    // A select's options open in the CDK overlay, outside this element; picking one is not a click away.
    const target = event.target as Element | null;
    if (target?.closest?.('.cdk-overlay-container')) return;
    if (!this.elementRef.nativeElement.contains(target)) {
      this.filterService.isFilterMenuOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (!this.filterService.isFilterMenuOpen()) return;
    this.filterService.isFilterMenuOpen.set(false);
    queueMicrotask(() => this.filterBar()?.focusTrigger());
  }

  ngOnInit() {
    this.recordRouteSubscription = this.recordRoute?.paramMap?.subscribe((params) =>
      this.loadRecordView(params.get('id')),
    );
    this.list.init();
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.panelLeaveSubscription?.unsubscribe();
    this.recordRouteSubscription?.unsubscribe();
    this.recordRequest?.unsubscribe();
    this.recordRequestId++;
  }

  // Permissions
  canCreateUser() {
    return this.permService.canCreate('iam.users') || this.permService.canCreate('md_users');
  }
  canUpdateUser() {
    return this.permService.canUpdate('iam.users') || this.permService.canUpdate('md_users');
  }
  canDeleteUser() {
    return this.permService.canDelete('iam.users') || this.permService.canDelete('md_users');
  }
  canBlockUser() {
    return this.permService.hasPermission('iam.users', 'block') || this.permService.hasPermission('md_users', 'block');
  }
  canUnblockUser() {
    return (
      this.permService.hasPermission('iam.users', 'unblock') || this.permService.hasPermission('md_users', 'unblock')
    );
  }
  canViewOrgUnits() {
    return (
      this.permService.hasPermission('iam.org_units', 'view') ||
      this.permService.hasPermission('iam.org_units', 'assign') ||
      this.orgPanelBusy()
    );
  }
  canViewAssignments() {
    return (
      this.permService.hasPermission('rbac.assignments', 'view') ||
      this.permService.hasPermission('rbac.assignments', 'assign')
    );
  }
  canAssignPermissions() {
    return this.permService.hasPermission('rbac.assignments', 'assign');
  }
  canLeaveRecordPage(): boolean | Observable<boolean> {
    return this.userOrgUnitsPanel?.canLeave() ?? true;
  }

  // Modals & Record View
  loadRecordView(id: string | null) {
    if (this.destroyed) return;
    const requestId = ++this.recordRequestId;
    this.recordRequest?.unsubscribe();
    this.routeRecordId.set(id);
    this.viewingUser.set(null);
    this.isViewModalOpen.set(id !== null);
    this.recordLoading.set(false);
    this.recordError.set(false);
    this.recordNotFound.set(false);
    this.activeViewTab.set('info');
    this.secService.userSecurity.set(null);
    if (id === null) return;
    if (!canonicalRecordId(id)) {
      this.recordError.set(true);
      this.recordNotFound.set(true);
      return;
    }
    this.recordLoading.set(true);
    this.recordRequest = this.usersApi.get(id).subscribe({
      next: (user) => {
        if (requestId !== this.recordRequestId) return;
        this.recordLoading.set(false);
        if (recordResponseMatches(user?.id, id)) {
          this.viewingUser.set(user);
          this.directory.resolve([user.managerId]);
        } else this.recordError.set(true);
      },
      error: (error) => {
        if (requestId !== this.recordRequestId) return;
        this.recordLoading.set(false);
        this.recordError.set(true);
        this.recordNotFound.set(error?.status === 404 || error?.status === 403);
      },
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
      this.viewingUser.set(user);
      this.activeViewTab.set('info');
      this.secService.userSecurity.set(null);
      this.isViewModalOpen.set(true);
    });
  }

  openEditFromView() {
    const u = this.viewingUser();
    if (u && safeNumericRecordId(u.id) && this.canUpdateUser()) {
      this.afterOrgPanelLeave(() => {
        this.isViewModalOpen.set(false);
        this.openEditModal(u);
      });
    }
  }

  openCreateModal() {
    this.formsService.openCreateModal(this.list.roles());
  }
  submitCreateUser() {
    this.formsService.submitCreateUser(() => this.list.loadUsers(true));
  }

  openEditModal(user: User) {
    this.formsService.openEditModal(user);
  }
  submitEditUser() {
    this.formsService.submitEditUser(
      () => this.destroyed,
      () => this.list.loadUsers(),
      (sessionId) => this.closeEditModal(sessionId),
    );
  }

  closeEditModal(expectedSessionId?: number) {
    if (this.destroyed) return;
    if (expectedSessionId !== undefined && expectedSessionId !== this.formsService.editSessionId) return;
    const closedSessionId = ++this.formsService.editSessionId;
    this.isEditModalOpen.set(false);
    this.formsService.editingUser = null;
    const routeId = this.routeRecordId();
    if (routeId !== null)
      this.afterOrgPanelLeave(() => {
        if (closedSessionId === this.formsService.editSessionId && routeId === this.routeRecordId()) {
          this.loadRecordView(routeId);
        }
      });
  }

  // Security actions
  switchViewTab(tab: 'info' | 'security' | 'orgUnits' | 'permissions', userId?: number) {
    this.activeViewTab.set(tab);
    const security = this.secService.userSecurity();
    if (tab === 'security' && userId && (!security || security.userId !== userId)) {
      this.secService.loadUserSecurity(userId);
    }
  }
  forcePasswordChange(userId: number) {
    this.secService.forcePasswordChange(userId, () => this.list.loadUsers());
  }
  resetUser2fa(userId: number) {
    this.secService.resetUser2fa(userId, () => this.list.loadUsers());
  }
  private get userOrgUnitsPanel(): UserOrgUnitsPanelComponent | undefined {
    return this.userDetailModal()?.orgUnitsPanel();
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
}
