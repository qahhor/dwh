import { ChangeDetectorRef, Component, computed, DestroyRef, effect, HostListener, inject, OnInit, signal, ViewChild } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { PermissionService } from '../../../core/services/permission.service';
import { safeNumericRecordId } from '../../../core/services/search-target';
import { ToastService } from '../../../core/services/toast.service';
import { ProblemDetail } from '../../../core/models/common.models';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { OrgUnitsApiService } from './org-units-api.service';
import { orderedTree, orgUnitKindKeys } from './org-unit-tree';
import { SMTTreeTableComponent, TreeTableColumns } from '../../../shared/ui-kit/components/tree-table/tree-table.component';
import { flattenTree, TreeRow } from '../../../shared/ui-kit/components/tree-table/tree.utils';
import { OrgUnitDraft } from './org-unit-draft';
import { OrgUnit, OrgUnitCreate } from './org-units.models';
import { OrgUnitEditorComponent, OrgUnitSubmission } from './org-unit-editor.component';
@Component({ selector: 'app-org-units', standalone: true, imports: [TranslatePipe, UiButtonComponent, UiModalComponent, SMTTreeTableComponent, OrgUnitEditorComponent], templateUrl: './org-units.component.html', styleUrl: './org-units.component.css' })
export class OrgUnitsComponent implements OnInit {
  readonly permissions = inject(PermissionService);
  readonly kindKeys = orgUnitKindKeys;
  readonly safeId = safeNumericRecordId;
  private readonly api = inject(OrgUnitsApiService);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly subscriptions = new Subscription();
  private treeRequest?: Subscription;
  private detailRequest?: Subscription;
  private detailId: number | null = null;
  private viewEpoch = 0;
  @ViewChild(OrgUnitEditorComponent) editor?: OrgUnitEditorComponent;
  units: OrgUnit[] = [];
  selected: OrgUnit | null = null;
  editorInitial: OrgUnit | OrgUnitCreate | null = null;
  editorOpen = false;
  pending = false;
  loading = false;
  loaded = false;
  treeError: ProblemDetail | null = null;
  detailLoading = false;
  detailError: ProblemDetail | null = null;
  saveError: ProblemDetail | null = null;
  savedRefreshFailed = false;
  deleteTarget: OrgUnit | null = null;
  deleteError: ProblemDetail | null = null;
  readonly search = signal('');
  private treeSource: OrgUnit[] | null = null;
  private treeCache: TreeRow<OrgUnit>[] = [];
  /** Rebuilt only when the unit list is replaced, not on every change detection pass. */
  get treeRows(): TreeRow<OrgUnit>[] {
    if (this.treeSource !== this.units) {
      this.treeSource = this.units;
      this.treeCache = flattenTree(orderedTree(this.units), { id: node => node.unit.id, children: node => node.children, data: node => node.unit });
    }
    return this.treeCache;
  }
  kindLabel(kind: string): string { return this.kindKeys[kind] ? this.i18n.translate(this.kindKeys[kind]) : kind; }
  stateLabel(unit: OrgUnit): string { return this.i18n.translate(unit.state === 'A' ? 'iam.org_units.active' : 'iam.org_units.passive'); }
  readonly searchText = (row: TreeRow<OrgUnit>) => `${row.data.code} ${row.data.name} ${this.kindLabel(row.data.kind)}`;
  readonly treeColumns = computed<TreeTableColumns<OrgUnit>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    return {
      treeColumn: 'name',
      columnsOrder: ['name', 'kind', 'state'],
      columns: {
        // Rows are separate grids, so tracks are fixed or a share of the width, never content-sized.
        name: { header: header('iam.org_units.name'), width: 'max(200px, calc(100% - 300px))', content: { type: 'primitive', value: row => `${row.data.code} · ${row.data.name}` } },
        kind: { header: header('iam.org_units.kind'), width: '150px', content: { type: 'primitive', value: row => this.kindLabel(row.data.kind) } },
        state: { header: header('iam.org_units.state'), width: '150px', content: { type: 'primitive', value: row => this.stateLabel(row.data) } },
      },
    };
  });
  readonly discard = new OrgUnitDraft(() => this.editorOpen && !!this.editor?.dirty, () => this.pending, () => this.clearEditor());

  constructor() {
    effect(() => { if (!this.can('view')) this.clearRevokedView(); });
    inject(DestroyRef).onDestroy(() => {
      this.discard.cancel(); this.treeRequest?.unsubscribe(); this.detailRequest?.unsubscribe(); this.subscriptions.unsubscribe();
    });
  }
  ngOnInit(): void { this.reload(); }
  can(action: string): boolean {
    return this.permissions.hasPermission('iam.org_units', 'view') && this.permissions.hasPermission('iam.org_units', action);
  }
  private clearRevokedView(): void {
    this.viewEpoch++;
    this.treeRequest?.unsubscribe(); this.discard.cancel(); this.clearEditor();
    this.units = []; this.selected = null; this.loaded = false; this.loading = false;
    this.treeError = null; this.savedRefreshFailed = false; this.deleteTarget = null; this.deleteError = null;
    // An issued write remains pending until its HTTP result; revocation is not rollback.
    this.changeDetector.markForCheck();
  }
  private currentView(epoch: number): boolean { return this.can('view') && epoch === this.viewEpoch; }
  private finishMutation(epoch: number): boolean {
    this.pending = false; this.changeDetector.markForCheck();
    return this.currentView(epoch);
  }
  get canCreate(): boolean { return this.can('create') && this.loaded && !this.loading && !this.treeError && (!this.units.length || !!this.selected && safeNumericRecordId(this.selected.id)); }
  get hasChildren(): boolean { return !!this.selected && this.units.some(unit => unit.parentId === this.selected!.id); }
  parentName(unit: OrgUnit): string {
    if (unit.parentId === null) return this.i18n.translate('iam.org_units.root');
    const parent = this.units.find(item => item.id === unit.parentId);
    return parent ? `${parent.code} · ${parent.name}` : String(unit.parentId);
  }
  reload(afterSave = false): void {
    if (!this.can('view') || this.pending || this.editorOpen || this.deleteTarget) return;
    const epoch = this.viewEpoch;
    this.treeRequest?.unsubscribe(); this.loading = true; this.treeError = null; this.changeDetector.markForCheck();
    this.treeRequest = this.api.list().subscribe({
      next: units => {
        if (!this.currentView(epoch)) return;
        const id = this.selected?.id;
        this.units = units; this.loaded = true; this.loading = false; this.savedRefreshFailed = false;
        this.selected = units.find(unit => unit.id === id) ?? units[0] ?? null;
        this.changeDetector.markForCheck();
      },
      error: error => { if (this.currentView(epoch)) { this.loading = false; this.treeError = error; this.savedRefreshFailed = afterSave || this.savedRefreshFailed; this.changeDetector.markForCheck(); } }
    });
  }
  select(unit: OrgUnit): void {
    if (!this.can('view')) return;
    this.discard.request(() => { this.clearEditor(); this.deleteTarget = null; this.selected = unit; });
  }
  create(): void {
    if (!this.canCreate || this.pending) return;
    const parentId = this.units.length ? this.selected!.id : null;
    this.discard.request(() => {
      this.clearEditor(); this.editorOpen = true;
      this.editorInitial = { parentId, code: '', name: '', kind: parentId === null ? 'company' : 'department', orderNo: 0 };
    });
  }
  edit(): void {
    if (!this.can('update') || !this.selected || !safeNumericRecordId(this.selected.id) || this.pending || this.loading) return;
    const id = this.selected.id;
    this.discard.request(() => { this.clearEditor(); this.editorOpen = true; this.detailId = id; this.loadDetail(); });
  }
  loadDetail(): void {
    const id = this.detailId;
    if (id === null || this.pending || !this.can('view')) return;
    const epoch = this.viewEpoch;
    this.detailRequest?.unsubscribe(); this.detailLoading = true; this.detailError = null; this.changeDetector.markForCheck();
    this.detailRequest = this.api.get(id).subscribe({
      next: unit => {
        if (!this.currentView(epoch) || this.detailId !== id || !this.editorOpen) return;
        this.detailLoading = false;
        this.changeDetector.markForCheck();
        if (unit.id !== id || !safeNumericRecordId(unit.id)) { this.detailError = this.unavailable(); return; }
        this.editorInitial = { ...unit };
      },
      error: error => { if (this.currentView(epoch) && this.detailId === id) { this.detailLoading = false; this.detailError = error; this.changeDetector.markForCheck(); } }
    });
  }
  closeEditor(): void { this.discard.request(() => this.clearEditor()); }
  private clearEditor(): void {
    this.detailRequest?.unsubscribe(); this.detailId = null; this.detailLoading = false; this.detailError = null;
    this.editorOpen = false; this.editorInitial = null; this.saveError = null;
    this.changeDetector.markForCheck();
  }
  save(submission: OrgUnitSubmission): void {
    if (!this.can('view') || this.pending || !this.editorOpen || !this.editorInitial) return;
    let request: Observable<unknown>;
    if (submission.mode === 'edit') {
      if (!this.can('update') || !('id' in this.editorInitial) || submission.id !== this.editorInitial.id || !safeNumericRecordId(submission.id)) return;
      request = this.api.update(submission.id, submission.patch);
    } else {
      if (!this.can('create') || 'id' in this.editorInitial || submission.body.parentId !== this.editorInitial.parentId) return;
      request = this.api.create(submission.body);
    }
    const epoch = this.viewEpoch;
    this.pending = true; this.saveError = null; this.changeDetector.markForCheck();
    this.subscriptions.add(request.subscribe({
      next: value => {
        if (!this.finishMutation(epoch)) return;
        if (submission.mode === 'create') this.selected = value as OrgUnit;
        this.clearEditor(); this.toast.success(this.i18n.translate('iam.org_units.saved')); this.reload(true);
      },
      error: error => { if (this.finishMutation(epoch)) this.saveError = error; }
    }));
  }
  requestDelete(): void {
    if (!this.can('delete') || !this.selected || !safeNumericRecordId(this.selected.id) || this.hasChildren || this.pending || this.loading) return;
    const target = { ...this.selected };
    this.discard.request(() => { this.clearEditor(); this.deleteTarget = target; this.deleteError = null; });
  }
  closeDelete(): void { if (!this.pending) { this.deleteTarget = null; this.deleteError = null; this.changeDetector.markForCheck(); } }
  confirmDelete(): void {
    if (!this.deleteTarget || this.pending || !this.can('delete') || !safeNumericRecordId(this.deleteTarget.id)) return;
    const epoch = this.viewEpoch;
    const targetId = this.deleteTarget.id;
    this.pending = true; this.deleteError = null; this.changeDetector.markForCheck();
    this.subscriptions.add(this.api.remove(targetId).subscribe({
      next: () => {
        if (this.finishMutation(epoch)) {
          if (this.selected?.id === targetId) this.selected = null;
          this.units = this.units.filter(unit => unit.id !== targetId);
          this.deleteTarget = null;
          this.toast.success(this.i18n.translate('iam.org_units.deleted'));
          this.reload(true);
        }
      },
      error: error => { if (this.finishMutation(epoch)) this.deleteError = error; }
    }));
  }
  canLeaveRecordPage(): boolean | Observable<boolean> { return this.discard.canLeave(); }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.pending || (this.editorOpen && this.editor?.dirty)) { event.preventDefault(); event.returnValue = ''; }
  }
  private unavailable(): ProblemDetail {
    return { status: 400, code: 'ORG_UNIT_UNSAFE_ID', title: this.i18n.translate('iam.org_units.unavailable'), detail: this.i18n.translate('iam.org_units.readonly_id') };
  }
}
