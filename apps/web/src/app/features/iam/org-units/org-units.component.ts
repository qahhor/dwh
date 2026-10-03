import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  computed,
  DestroyRef,
  effect,
  HostListener,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { safeNumericRecordId } from '@core/services/search-target';
import { ToastService } from '@core/services/toast.service';
import { SaveErrorNotifier, isRevisionConflict } from '@shared/ui/save-errors';
import { ProblemDetail } from '@core/models/common.models';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { OrgUnitsApiService } from './org-units.api';
import { OrgUnitTreeRows, orgUnitKindKeys, orgUnitSearchText, orgUnitTreeColumns } from './org-unit-tree';
import { SMTTreeTableComponent } from '@shared/ui-kit/components/tree-table/tree-table.component';
import { TreeRow } from '@shared/ui-kit/components/tree-table/tree.utils';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { OrgUnitDraft } from './org-unit-draft';
import { OrgUnit, OrgUnitCreate } from './org-units.models';
import { OrgUnitEditorComponent, OrgUnitSubmission } from './org-unit-editor.component';
@Component({
  selector: 'app-org-units',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTTreeTableComponent,
    OrgUnitEditorComponent,
    SMTInputComponent,
    UiPageHeaderComponent,
  ],
  templateUrl: './org-units.component.html',
  styleUrl: './org-units.component.css',
})
export class OrgUnitsComponent implements OnInit {
  readonly permissions = inject(PermissionService);
  private readonly api = inject(OrgUnitsApiService);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly editor = viewChild(OrgUnitEditorComponent);

  readonly search = signal('');
  readonly units = signal<OrgUnit[]>([]);
  readonly selected = signal<OrgUnit | null>(null);
  readonly editorInitial = signal<OrgUnit | OrgUnitCreate | null>(null);
  readonly editorOpen = signal(false);
  readonly pending = signal(false);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly treeError = signal<ProblemDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<ProblemDetail | null>(null);
  readonly saveError = signal<ProblemDetail | null>(null);
  readonly savedRefreshFailed = signal(false);
  readonly deleteTarget = signal<OrgUnit | null>(null);
  readonly deleteError = signal<ProblemDetail | null>(null);

  readonly treeColumns = computed(() => orgUnitTreeColumns((key) => this.i18n.translate(key)));
  /** The tree's selected row id, as the tree takes it. */
  readonly selectedId = computed(() => {
    const selected = this.selected();
    return selected ? '' + selected.id : null;
  });
  /** The editor's record as a one-item list: the editor is created anew for each record. */
  readonly editorInitials = computed(() => {
    const initial = this.editorInitial();
    return initial ? [initial] : [];
  });

  readonly kindKeys = orgUnitKindKeys;
  readonly safeId = safeNumericRecordId;
  private readonly subscriptions = new Subscription();
  private treeRequest?: Subscription;
  private detailRequest?: Subscription;
  private detailId: number | null = null;
  private viewEpoch = 0;
  private readonly treeRowCache = new OrgUnitTreeRows();
  readonly searchText = orgUnitSearchText((key) => this.i18n.translate(key));
  readonly discard = new OrgUnitDraft(
    () => this.editorOpen() && !!this.editor()?.dirty,
    () => this.pending(),
    () => this.clearEditor(),
  );

  constructor() {
    effect(() => {
      if (!this.can('view')) this.clearRevokedView();
    });
    inject(DestroyRef).onDestroy(() => {
      this.discard.cancel();
      this.treeRequest?.unsubscribe();
      this.detailRequest?.unsubscribe();
      this.subscriptions.unsubscribe();
    });
  }

  get treeRows(): TreeRow<OrgUnit>[] {
    return this.treeRowCache.of(this.units());
  }
  ngOnInit(): void {
    this.reload();
  }
  can(action: string): boolean {
    return (
      this.permissions.hasPermission('md.org_units', 'view') && this.permissions.hasPermission('md.org_units', action)
    );
  }
  get canCreate(): boolean {
    return (
      this.can('create') &&
      this.loaded() &&
      !this.loading() &&
      !this.treeError() &&
      (!this.units().length || safeNumericRecordId(this.selected()?.id))
    );
  }
  get hasChildren(): boolean {
    return !!this.selected() && this.units().some((unit) => unit.parentId === this.selected()!.id);
  }
  parentName(unit: OrgUnit): string {
    if (unit.parentId === null) return this.i18n.translate('iam.org_units.root');
    const parent = this.units().find((item) => item.id === unit.parentId);
    return parent ? `${parent.code} · ${parent.name}` : String(unit.parentId);
  }
  reload(afterSave = false): void {
    if (!this.can('view') || this.pending() || this.editorOpen() || this.deleteTarget()) return;
    const epoch = this.viewEpoch;
    this.treeRequest?.unsubscribe();
    this.loading.set(true);
    this.treeError.set(null);
    this.changeDetector.markForCheck();
    this.treeRequest = this.api.list().subscribe({
      next: (units) => {
        if (!this.currentView(epoch)) return;
        const id = this.selected()?.id;
        this.units.set(units);
        this.loaded.set(true);
        this.loading.set(false);
        this.savedRefreshFailed.set(false);
        this.selected.set(units.find((unit) => unit.id === id) ?? units[0] ?? null);
        this.changeDetector.markForCheck();
      },
      error: (error) => {
        if (this.currentView(epoch)) {
          this.loading.set(false);
          this.treeError.set(error);
          this.savedRefreshFailed.set(afterSave || this.savedRefreshFailed());
          this.changeDetector.markForCheck();
        }
      },
    });
  }
  select(unit: OrgUnit): void {
    if (!this.can('view')) return;
    this.discard.request(() => {
      this.clearEditor();
      this.deleteTarget.set(null);
      this.selected.set(unit);
    });
  }
  create(): void {
    if (!this.canCreate || this.pending()) return;
    const parentId = this.units().length ? this.selected()!.id : null;
    this.discard.request(() => {
      this.clearEditor();
      this.editorOpen.set(true);
      this.editorInitial.set({
        parentId,
        code: '',
        name: '',
        kind: parentId === null ? 'company' : 'department',
        orderNo: 0,
      });
    });
  }
  edit(): void {
    const selected = this.selected();
    if (!this.can('update') || !selected || !safeNumericRecordId(selected.id) || this.pending() || this.loading())
      return;
    const id = selected.id;
    this.discard.request(() => {
      this.clearEditor();
      this.editorOpen.set(true);
      this.detailId = id;
      this.loadDetail();
    });
  }
  loadDetail(): void {
    const id = this.detailId;
    if (id === null || this.pending() || !this.can('view')) return;
    const epoch = this.viewEpoch;
    this.detailRequest?.unsubscribe();
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.changeDetector.markForCheck();
    this.detailRequest = this.api.get(id).subscribe({
      next: (unit) => {
        if (!this.currentView(epoch) || this.detailId !== id || !this.editorOpen()) return;
        this.detailLoading.set(false);
        this.changeDetector.markForCheck();
        if (unit.id !== id || !safeNumericRecordId(unit.id)) {
          this.detailError.set(this.unavailable());
          return;
        }
        this.editorInitial.set({ ...unit });
      },
      error: (error) => {
        if (this.currentView(epoch) && this.detailId === id) {
          this.detailLoading.set(false);
          this.detailError.set(error);
          this.changeDetector.markForCheck();
        }
      },
    });
  }
  closeEditor(): void {
    this.discard.request(() => this.clearEditor());
  }
  save(submission: OrgUnitSubmission): void {
    const initial = this.editorInitial();
    if (!this.can('view') || this.pending() || !this.editorOpen() || !initial) return;
    let request: Observable<unknown>;
    if (submission.mode === 'edit') {
      if (
        !this.can('update') ||
        !('id' in initial) ||
        submission.id !== initial.id ||
        !safeNumericRecordId(submission.id)
      )
        return;
      request = this.api.update(submission.id, submission.patch, 'revision' in initial ? initial.revision : undefined);
    } else {
      if (!this.can('create') || 'id' in initial || submission.body.parentId !== initial.parentId) return;
      request = this.api.create(submission.body);
    }
    const epoch = this.viewEpoch;
    this.pending.set(true);
    this.saveError.set(null);
    this.changeDetector.markForCheck();
    this.subscriptions.add(
      request.subscribe({
        next: (value) => {
          if (!this.finishMutation(epoch)) return;
          if (submission.mode === 'create') this.selected.set(value as OrgUnit);
          this.clearEditor();
          this.toast.success(this.i18n.translate('iam.org_units.saved'));
          this.reload(true);
        },
        error: (error) => {
          if (!this.finishMutation(epoch)) return;
          if (!isRevisionConflict(error)) {
            this.saveError.set(error);
            return;
          }
          // Saved by someone else since it was opened: the tree is read again and the unit opened from it.
          this.saveErrors.show(error, {
            fallbackKey: 'common.error',
            reload: () => {
              this.clearEditor();
              this.reload();
            },
          });
        },
      }),
    );
  }
  requestDelete(): void {
    const selected = this.selected();
    if (
      !this.can('delete') ||
      !selected ||
      !safeNumericRecordId(selected.id) ||
      this.hasChildren ||
      this.pending() ||
      this.loading()
    )
      return;
    const target = { ...selected };
    this.discard.request(() => {
      this.clearEditor();
      this.deleteTarget.set(target);
      this.deleteError.set(null);
    });
  }
  closeDelete(): void {
    if (!this.pending()) {
      this.deleteTarget.set(null);
      this.deleteError.set(null);
      this.changeDetector.markForCheck();
    }
  }
  confirmDelete(): void {
    const target = this.deleteTarget();
    if (!target || this.pending() || !this.can('delete') || !safeNumericRecordId(target.id)) return;
    const epoch = this.viewEpoch;
    const targetId = target.id;
    this.pending.set(true);
    this.deleteError.set(null);
    this.changeDetector.markForCheck();
    this.subscriptions.add(
      this.api.remove(targetId).subscribe({
        next: () => {
          if (this.finishMutation(epoch)) {
            if (this.selected()?.id === targetId) this.selected.set(null);
            this.units.set(this.units().filter((unit) => unit.id !== targetId));
            this.deleteTarget.set(null);
            this.toast.success(this.i18n.translate('iam.org_units.deleted'));
            this.reload(true);
          }
        },
        error: (error) => {
          if (this.finishMutation(epoch)) this.deleteError.set(error);
        },
      }),
    );
  }
  canLeaveRecordPage(): boolean | Observable<boolean> {
    return this.discard.canLeave();
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.pending() || (this.editorOpen() && this.editor()?.dirty)) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  private clearRevokedView(): void {
    this.viewEpoch++;
    this.treeRequest?.unsubscribe();
    this.discard.cancel();
    this.clearEditor();
    this.units.set([]);
    this.selected.set(null);
    this.loaded.set(false);
    this.loading.set(false);
    this.treeError.set(null);
    this.savedRefreshFailed.set(false);
    this.deleteTarget.set(null);
    this.deleteError.set(null);
    // An issued write remains pending until its HTTP result; revocation is not rollback.
    this.changeDetector.markForCheck();
  }
  private currentView(epoch: number): boolean {
    return this.can('view') && epoch === this.viewEpoch;
  }
  private finishMutation(epoch: number): boolean {
    this.pending.set(false);
    this.changeDetector.markForCheck();
    return this.currentView(epoch);
  }
  private clearEditor(): void {
    this.detailRequest?.unsubscribe();
    this.detailId = null;
    this.detailLoading.set(false);
    this.detailError.set(null);
    this.editorOpen.set(false);
    this.editorInitial.set(null);
    this.saveError.set(null);
    this.changeDetector.markForCheck();
  }
  private unavailable(): ProblemDetail {
    return {
      status: 400,
      code: 'ORG_UNIT_UNSAFE_ID',
      title: this.i18n.translate('iam.org_units.unavailable'),
      detail: this.i18n.translate('iam.org_units.readonly_id'),
    };
  }
}
