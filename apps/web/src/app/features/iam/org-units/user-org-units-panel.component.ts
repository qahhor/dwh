import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  computed,
  DestroyRef,
  effect,
  HostListener,
  inject,
  OnChanges,
  signal,
  SimpleChanges,
  input,
  output,
} from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { safeNumericRecordId } from '@core/services/search-target';
import { ToastService } from '@core/services/toast.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { OrgUnitDraft } from './org-unit-draft';
import { SMTTreeTableComponent } from '@shared/ui-kit/components/tree-table/tree-table.component';
import { TreeRow } from '@shared/ui-kit/components/tree-table/tree.utils';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { OrgUnitTreeRows, orgUnitSearchText, orgUnitTreeColumns } from './org-unit-tree';
import { emptyScopeKey, normalizedIds, sameIds, scopeRuleKey, unsafeIdProblem } from './org-unit-assignments';
import { OrgUnitsApiService } from './org-units-api.service';
import { OrgUnit, UserScope } from './org-units.models';

@Component({
  selector: 'app-user-org-units-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTTreeTableComponent,
    SMTInputComponent,
  ],
  templateUrl: './user-org-units-panel.component.html',
  styleUrl: './user-org-units-panel.component.css',
})
export class UserOrgUnitsPanelComponent implements OnChanges {
  readonly permissions = inject(PermissionService);
  private readonly api = inject(OrgUnitsApiService);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  readonly userId = input.required<number>();

  readonly busyChange = output<boolean>();

  readonly selectedOrgUnitIds = signal<readonly number[]>([]);
  readonly search = signal('');
  readonly units = signal<OrgUnit[]>([]);
  readonly legacyOrgUnitId = signal<number | null>(null);
  readonly effectiveScope = signal<UserScope | null>(null);
  readonly pending = signal(false);
  readonly treeLoading = signal(false);
  readonly treeLoaded = signal(false);
  readonly treeError = signal<ProblemDetail | null>(null);
  readonly assignmentsLoading = signal(false);
  readonly assignmentsLoaded = signal(false);
  readonly assignmentsError = signal<ProblemDetail | null>(null);
  readonly scopeLoading = signal(false);
  readonly scopeLoaded = signal(false);
  readonly scopeError = signal<ProblemDetail | null>(null);
  readonly saveError = signal<ProblemDetail | null>(null);
  readonly savedRefreshFailed = signal(false);
  private readonly originalOrgUnitIds = signal<readonly number[]>([]);

  /** The tree table identifies rows by string id. */
  readonly checkedRowIds = computed(() => this.selectedOrgUnitIds().map(String));
  readonly treeColumns = computed(() => orgUnitTreeColumns((key) => this.i18n.translate(key)));
  readonly scopeRuleKey = computed(() => scopeRuleKey(this.effectiveScope()));
  readonly emptyScopeKey = computed(() => emptyScopeKey(this.effectiveScope()));

  private readonly writes = new Subscription();
  private treeRequest?: Subscription;
  private assignmentsRequest?: Subscription;
  private scopeRequest?: Subscription;
  private activeTarget: number | null = null;
  private deferredTarget: number | null = null;
  private viewEpoch = 0;
  private readonly treeRowCache = new OrgUnitTreeRows();
  readonly searchText = orgUnitSearchText((key) => this.i18n.translate(key));
  readonly discard = new OrgUnitDraft(
    () => this.dirty,
    () => this.pending(),
    () => this.restoreDraft(),
  );

  constructor() {
    effect(() => {
      const canView = this.permissions.hasPermission('iam.org_units', 'view');
      this.permissions.hasPermission('iam.org_units', 'assign');
      if (!canView) this.clearRevokedView();
    });
    inject(DestroyRef).onDestroy(() => {
      this.discard.cancel();
      this.cancelReads();
      this.writes.unsubscribe();
      this.setPending(false);
    });
  }

  get treeRows(): TreeRow<OrgUnit>[] {
    return this.treeRowCache.of(this.units());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['userId'] || changes['userId'].currentValue === this.activeTarget) return;
    this.activateTarget(this.userId());
  }

  can(action: 'view' | 'assign'): boolean {
    return (
      this.permissions.hasPermission('iam.org_units', 'view') && this.permissions.hasPermission('iam.org_units', action)
    );
  }
  get dirty(): boolean {
    return this.assignmentsLoaded() && !sameIds(this.selectedOrgUnitIds(), this.originalOrgUnitIds());
  }
  get inactiveAssignments(): OrgUnit[] {
    const selected = new Set(this.selectedOrgUnitIds());
    return this.units().filter((unit) => selected.has(unit.id) && unit.state === 'P');
  }
  get unresolvedAssignmentIds(): number[] {
    if (!this.treeLoaded()) return [];
    const known = new Set(this.units().map((unit) => unit.id));
    return this.selectedOrgUnitIds().filter((id) => !known.has(id));
  }

  toggleAssignment(unit: OrgUnit): void {
    if (!this.can('assign') || !this.assignmentsLoaded() || this.pending() || !safeNumericRecordId(unit.id)) return;
    const selected = new Set(this.selectedOrgUnitIds());
    if (!selected.delete(unit.id)) selected.add(unit.id);
    this.selectedOrgUnitIds.set([...selected].sort((a, b) => a - b));
    this.saveError.set(null);
  }

  save(): void {
    const target = this.activeTarget;
    const ids = [...this.selectedOrgUnitIds()];
    if (
      !this.can('assign') ||
      !this.assignmentsLoaded() ||
      !this.dirty ||
      this.pending() ||
      target === null ||
      target !== this.userId() ||
      !safeNumericRecordId(target) ||
      !ids.every(safeNumericRecordId)
    )
      return;
    const epoch = this.viewEpoch;
    this.setPending(true);
    this.saveError.set(null);
    this.changeDetector.markForCheck();
    this.writes.add(
      this.api.saveAssignments(target, ids).subscribe({
        next: () => {
          this.setPending(false);
          if (!this.currentView(epoch, target)) {
            this.loadDeferredTarget();
            return;
          }
          this.originalOrgUnitIds.set([...ids]);
          this.selectedOrgUnitIds.set([...ids]);
          this.assignmentsLoaded.set(true);
          this.toast.success(this.i18n.translate('iam.org_units.assignments_saved'));
          this.reloadScope(true);
        },
        error: (error) => {
          this.setPending(false);
          if (this.currentView(epoch, target)) this.saveError.set(error);
          else this.loadDeferredTarget();
          this.changeDetector.markForCheck();
        },
      }),
    );
  }

  reloadAll(): void {
    if (this.pending() || !this.can('view')) return;
    const userId = this.userId();
    if (this.activeTarget !== userId) {
      this.activateTarget(userId);
      return;
    }
    this.reloadTree();
    this.reloadAssignments();
    this.reloadScope();
  }
  reloadTree(): void {
    const target = this.activeTarget;
    if (!this.can('view') || this.pending() || target === null || !safeNumericRecordId(target)) return;
    const epoch = this.viewEpoch;
    this.treeRequest?.unsubscribe();
    this.treeLoading.set(true);
    this.treeLoaded.set(false);
    this.treeError.set(null);
    this.treeRequest = this.api.list().subscribe({
      next: (units) => {
        if (!this.currentView(epoch, target)) return;
        this.units.set(units);
        this.treeLoading.set(false);
        this.treeLoaded.set(true);
        this.changeDetector.markForCheck();
      },
      error: (error) => {
        if (this.currentView(epoch, target)) {
          this.treeLoading.set(false);
          this.treeLoaded.set(false);
          this.treeError.set(error);
          this.changeDetector.markForCheck();
        }
      },
    });
  }
  reloadAssignments(): void {
    const target = this.activeTarget;
    if (!this.can('view') || this.pending() || this.dirty || target === null || !safeNumericRecordId(target)) return;
    const epoch = this.viewEpoch;
    this.assignmentsRequest?.unsubscribe();
    this.assignmentsLoading.set(true);
    this.assignmentsLoaded.set(false);
    this.assignmentsError.set(null);
    this.saveError.set(null);
    this.assignmentsRequest = this.api.assignments(target).subscribe({
      next: (snapshot) => {
        if (!this.currentView(epoch, target)) return;
        this.assignmentsLoading.set(false);
        if (snapshot.userId !== target || !snapshot.orgUnitIds.every(safeNumericRecordId)) {
          this.assignmentsError.set(this.unavailable());
          this.assignmentsLoaded.set(false);
          this.changeDetector.markForCheck();
          return;
        }
        const ids = normalizedIds(snapshot.orgUnitIds);
        this.originalOrgUnitIds.set(ids);
        this.selectedOrgUnitIds.set([...ids]);
        this.legacyOrgUnitId.set(snapshot.legacyOrgUnitId ?? null);
        this.assignmentsLoaded.set(true);
        this.changeDetector.markForCheck();
      },
      error: (error) => {
        if (this.currentView(epoch, target)) {
          this.assignmentsLoading.set(false);
          this.assignmentsLoaded.set(false);
          this.assignmentsError.set(error);
          this.changeDetector.markForCheck();
        }
      },
    });
  }
  reloadScope(afterSave = false): void {
    const target = this.activeTarget;
    if (!this.can('view') || this.pending() || target === null || !safeNumericRecordId(target)) return;
    const epoch = this.viewEpoch;
    this.scopeRequest?.unsubscribe();
    this.scopeLoading.set(true);
    this.scopeLoaded.set(false);
    this.scopeError.set(null);
    this.scopeRequest = this.api.scope(target).subscribe({
      next: (scope) => {
        if (!this.currentView(epoch, target)) return;
        this.effectiveScope.set(scope);
        this.scopeLoading.set(false);
        this.scopeLoaded.set(true);
        this.savedRefreshFailed.set(false);
        this.changeDetector.markForCheck();
      },
      error: (error) => {
        if (this.currentView(epoch, target)) {
          this.scopeLoading.set(false);
          this.scopeLoaded.set(false);
          this.scopeError.set(error);
          this.savedRefreshFailed.set(afterSave || this.savedRefreshFailed());
          this.changeDetector.markForCheck();
        }
      },
    });
  }

  unitLabel(id: number): string {
    const unit = this.units().find((item) => item.id === id);
    return unit ? `${unit.code} · ${unit.name}` : String(id);
  }
  cancelEdits(): void {
    this.discard.request(() => this.restoreDraft());
  }
  canLeave(): boolean | Observable<boolean> {
    return this.discard.canLeave();
  }
  hasUnsavedWork(): boolean {
    return this.pending() || this.dirty;
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.hasUnsavedWork()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  private activateTarget(target: number): void {
    this.viewEpoch++;
    this.cancelReads();
    this.discard.cancel();
    this.resetProtectedState();
    this.activeTarget = target;
    if (this.pending()) {
      this.deferredTarget = target;
      this.changeDetector.markForCheck();
      return;
    }
    this.deferredTarget = null;
    this.loadActiveTarget();
  }
  private loadActiveTarget(): void {
    const target = this.activeTarget;
    if (target === null) return;
    if (!this.can('view') || !safeNumericRecordId(target)) {
      if (this.can('view')) this.assignmentsError.set(this.unavailable());
      this.changeDetector.markForCheck();
      return;
    }
    this.reloadTree();
    this.reloadAssignments();
    this.reloadScope();
  }
  private clearRevokedView(): void {
    this.viewEpoch++;
    this.deferredTarget = null;
    this.cancelReads();
    this.discard.cancel();
    this.resetProtectedState();
    this.changeDetector.markForCheck();
  }
  private resetProtectedState(): void {
    this.units.set([]);
    this.treeLoading.set(false);
    this.treeLoaded.set(false);
    this.treeError.set(null);
    this.originalOrgUnitIds.set([]);
    this.selectedOrgUnitIds.set([]);
    this.legacyOrgUnitId.set(null);
    this.assignmentsLoading.set(false);
    this.assignmentsLoaded.set(false);
    this.assignmentsError.set(null);
    this.effectiveScope.set(null);
    this.scopeLoading.set(false);
    this.scopeLoaded.set(false);
    this.scopeError.set(null);
    this.saveError.set(null);
    this.savedRefreshFailed.set(false);
  }
  private restoreDraft(): void {
    this.selectedOrgUnitIds.set([...this.originalOrgUnitIds()]);
    this.saveError.set(null);
    this.changeDetector.markForCheck();
  }
  private cancelReads(): void {
    this.treeRequest?.unsubscribe();
    this.assignmentsRequest?.unsubscribe();
    this.scopeRequest?.unsubscribe();
    this.treeRequest = undefined;
    this.assignmentsRequest = undefined;
    this.scopeRequest = undefined;
  }
  private currentView(epoch: number, target: number): boolean {
    return this.can('view') && epoch === this.viewEpoch && target === this.activeTarget && target === this.userId();
  }
  private loadDeferredTarget(): void {
    if (
      this.deferredTarget === null ||
      this.deferredTarget !== this.activeTarget ||
      this.deferredTarget !== this.userId()
    )
      return;
    this.deferredTarget = null;
    this.loadActiveTarget();
  }
  private setPending(value: boolean): void {
    if (this.pending() === value) return;
    this.pending.set(value);
    this.busyChange.emit(value);
    this.changeDetector.markForCheck();
  }
  private unavailable(): ProblemDetail {
    return unsafeIdProblem((key) => this.i18n.translate(key));
  }
}
