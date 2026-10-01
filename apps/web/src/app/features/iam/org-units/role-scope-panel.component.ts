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
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { isRevisionConflict, SaveErrorNotifier } from '@shared/ui/save-errors';
import { latestRevision } from './org-unit-assignments';
import { OrgUnitDraft } from './org-unit-draft';
import { OrgUnitsApiService } from './org-units-api.service';
import { ScopeRule } from './org-units.models';

export interface ScopeRuleOption {
  value: ScopeRule;
  labelKey: string;
  descriptionKey: string;
}

@Component({
  selector: 'app-role-scope-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTButtonComponent, SMTDialogComponent, SMTDialogContentDirective, SMTRadioGroupComponent],
  templateUrl: './role-scope-panel.component.html',
  styleUrl: './role-scope-panel.component.css',
})
export class RoleScopePanelComponent implements OnChanges {
  readonly permissions = inject(PermissionService);
  private readonly api = inject(OrgUnitsApiService);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly roleId = input.required<number>();
  /** The role's revision as the host screen holds it: the rule is saved from it (plan item 3.6). */
  readonly revision = input<number | undefined>(undefined);

  readonly busyChange = output<boolean>();
  /** The role's revision after a save of the rule, for the host screen to keep its copy in step. */
  readonly revisionChange = output<number>();
  /** The role changed since the screen read it: the host screen reads the roles again. */
  readonly staleRole = output<void>();

  readonly selectedRule = signal<ScopeRule>('ALL');
  readonly loaded = signal(false);
  readonly loading = signal(false);
  readonly pending = signal(false);
  readonly loadError = signal<ProblemDetail | null>(null);
  readonly saveError = signal<ProblemDetail | null>(null);
  readonly savedRefreshFailed = signal(false);
  readonly confirmationOpen = signal(false);
  private readonly originalRule = signal<ScopeRule | null>(null);
  private readonly loadedRevision = signal<number | undefined>(undefined);

  /** The rules as radio items, translated; each description says what the rule lets the role see. */
  readonly ruleOptions = computed<SMTRadioOption<ScopeRule>[]>(() =>
    this.options.map((option) => ({
      value: option.value,
      label: this.i18n.translate(option.labelKey),
      hint: this.i18n.translate(option.descriptionKey),
    })),
  );

  private readonly writes = new Subscription();
  private readRequest?: Subscription;
  private activeTarget: number | null = null;
  private deferredTarget: number | null = null;
  private viewEpoch = 0;

  readonly options: readonly ScopeRuleOption[] = [
    { value: 'ALL', labelKey: 'iam.data_scope.rule_all', descriptionKey: 'iam.data_scope.rule_all_help' },
    { value: 'SUBTREE', labelKey: 'iam.data_scope.rule_subtree', descriptionKey: 'iam.data_scope.rule_subtree_help' },
    { value: 'UNITS', labelKey: 'iam.data_scope.rule_units', descriptionKey: 'iam.data_scope.rule_units_help' },
    { value: 'SELF', labelKey: 'iam.data_scope.rule_self', descriptionKey: 'iam.data_scope.rule_self_help' },
  ];
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
      this.readRequest?.unsubscribe();
      this.writes.unsubscribe();
      this.setPending(false);
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['roleId'] || changes['roleId'].currentValue === this.activeTarget) return;
    this.activateTarget(this.roleId());
  }

  can(action: 'view' | 'assign'): boolean {
    return (
      this.permissions.hasPermission('iam.org_units', 'view') && this.permissions.hasPermission('iam.org_units', action)
    );
  }
  get dirty(): boolean {
    return this.loaded() && this.originalRule() !== null && this.selectedRule() !== this.originalRule();
  }

  selectRule(rule: ScopeRule): void {
    if (!this.can('assign') || !this.loaded() || this.pending() || !isScopeRule(rule)) return;
    this.selectedRule.set(rule);
    this.saveError.set(null);
    this.confirmationOpen.set(false);
  }

  save(): void {
    if (
      !this.can('assign') ||
      !this.loaded() ||
      !this.dirty ||
      this.pending() ||
      this.confirmationOpen() ||
      this.activeTarget === null ||
      this.activeTarget !== this.roleId() ||
      !safeNumericRecordId(this.activeTarget)
    )
      return;
    this.confirmationOpen.set(true);
    this.changeDetector.markForCheck();
  }

  closeConfirmation(): void {
    if (!this.pending()) {
      this.confirmationOpen.set(false);
      this.changeDetector.markForCheck();
    }
  }

  confirmSave(): void {
    const target = this.activeTarget;
    const rule = this.selectedRule();
    if (
      !this.confirmationOpen() ||
      !this.can('assign') ||
      !this.loaded() ||
      !this.dirty ||
      this.pending() ||
      target === null ||
      target !== this.roleId() ||
      !safeNumericRecordId(target) ||
      !isScopeRule(rule)
    )
      return;
    const epoch = this.viewEpoch;
    const revision = latestRevision(this.revision(), this.loadedRevision());
    this.confirmationOpen.set(false);
    this.setPending(true);
    this.saveError.set(null);
    this.writes.add(
      this.api.saveRoleRule(target, rule, revision).subscribe({
        next: () => {
          this.setPending(false);
          if (!this.currentView(epoch, target)) {
            this.loadDeferredTarget();
            return;
          }
          this.originalRule.set(rule);
          this.selectedRule.set(rule);
          this.loaded.set(true);
          // The save raised the role's revision by one: the next change of the role names the new one.
          if (revision !== undefined) {
            this.loadedRevision.set(revision + 1);
            this.revisionChange.emit(revision + 1);
          }
          this.toast.success(this.i18n.translate('iam.data_scope.saved'));
          this.reload(true);
        },
        error: (error) => {
          this.setPending(false);
          if (!this.currentView(epoch, target)) this.loadDeferredTarget();
          else if (isRevisionConflict(error)) this.offerReload(error);
          else this.saveError.set(error);
          this.changeDetector.markForCheck();
        },
      }),
    );
  }

  reload(afterSave = false): void {
    const target = this.activeTarget;
    if (!this.can('view') || this.pending() || this.dirty || target === null || !safeNumericRecordId(target)) return;
    const epoch = this.viewEpoch;
    this.readRequest?.unsubscribe();
    this.loading.set(true);
    this.loaded.set(false);
    this.loadError.set(null);
    this.confirmationOpen.set(false);
    this.readRequest = this.api.roleRule(target).subscribe({
      next: (snapshot) => {
        if (!this.currentView(epoch, target)) return;
        this.loading.set(false);
        if (snapshot.roleId !== target || !isScopeRule(snapshot.rule)) {
          this.loadError.set(this.unavailable());
          this.loaded.set(false);
          this.changeDetector.markForCheck();
          return;
        }
        this.originalRule.set(snapshot.rule);
        this.selectedRule.set(snapshot.rule);
        this.loadedRevision.set(snapshot.revision);
        this.loaded.set(true);
        this.savedRefreshFailed.set(false);
        this.changeDetector.markForCheck();
      },
      error: (error) => {
        if (this.currentView(epoch, target)) {
          this.loading.set(false);
          this.loaded.set(false);
          this.loadError.set(error);
          this.savedRefreshFailed.set(afterSave || this.savedRefreshFailed());
          this.changeDetector.markForCheck();
        }
      },
    });
  }

  originalRuleKey(): string {
    return this.ruleKey(this.originalRule() ?? 'ALL');
  }
  selectedRuleKey(): string {
    return this.ruleKey(this.selectedRule());
  }
  cancelEdits(): void {
    this.discard.request(() => this.restoreDraft());
  }
  canLeave(): boolean | Observable<boolean> {
    return this.discard.canLeave();
  }
  onRuleChosen(rule: ScopeRule | null): void {
    if (rule) this.selectRule(rule);
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
    this.readRequest?.unsubscribe();
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
      if (this.can('view')) this.loadError.set(this.unavailable());
      this.changeDetector.markForCheck();
      return;
    }
    this.reload();
  }
  private clearRevokedView(): void {
    this.viewEpoch++;
    this.deferredTarget = null;
    this.readRequest?.unsubscribe();
    this.readRequest = undefined;
    this.discard.cancel();
    this.resetProtectedState();
    this.changeDetector.markForCheck();
  }
  private resetProtectedState(): void {
    this.originalRule.set(null);
    this.loadedRevision.set(undefined);
    this.selectedRule.set('ALL');
    this.loaded.set(false);
    this.loading.set(false);
    this.loadError.set(null);
    this.saveError.set(null);
    this.savedRefreshFailed.set(false);
    this.confirmationOpen.set(false);
  }
  private restoreDraft(): void {
    const original = this.originalRule();
    if (original !== null) this.selectedRule.set(original);
    this.confirmationOpen.set(false);
    this.saveError.set(null);
    this.changeDetector.markForCheck();
  }
  private currentView(epoch: number, target: number): boolean {
    return this.can('view') && epoch === this.viewEpoch && target === this.activeTarget && target === this.roleId();
  }
  private loadDeferredTarget(): void {
    if (
      this.deferredTarget === null ||
      this.deferredTarget !== this.activeTarget ||
      this.deferredTarget !== this.roleId()
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
  /** The role moved on since the screen read it: the draft gives way to what the server has now. */
  private offerReload(error: unknown): void {
    this.saveErrors.show(error, {
      fallbackKey: 'common.operation_failed',
      reload: () => {
        this.restoreDraft();
        this.reload();
        this.staleRole.emit();
      },
    });
  }
  private ruleKey(rule: ScopeRule): string {
    return `iam.data_scope.rule_${rule.toLowerCase()}`;
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

function isScopeRule(rule: unknown): rule is ScopeRule {
  return rule === 'ALL' || rule === 'SUBTREE' || rule === 'UNITS' || rule === 'SELF';
}
