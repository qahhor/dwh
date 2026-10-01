import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  computed,
  input,
  linkedSignal,
  output,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of } from 'rxjs';
import { lastLoaded } from '@features/iam/last-loaded';

import { UsersApi } from '../users.api';
import { ToastService } from '@core/services/toast.service';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { FormTreeItem } from '@core/models/rbac.models';
import { MODULE_ICON_MAP, MODULE_NAME_KEY_MAP } from '@features/iam/roles/roles.models';
import { EffectivePermissionItem, PersonalGrant } from '../users.models';
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

export interface GroupedPermissionAction {
  action: string;
  source: 'role' | 'personal';
}

export interface GroupedPermissionForm {
  formCode: string;
  formName: string;
  actions: GroupedPermissionAction[];
}

export interface GroupedPermissionModule {
  moduleCode: string;
  moduleName: string;
  icon: string;
  forms: GroupedPermissionForm[];
}

@Component({
  selector: 'app-user-effective-permissions-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTRadioGroupComponent, SMTSelectComponent, SMTInputComponent, TranslatePipe, SMTButtonComponent],
  templateUrl: './user-effective-permissions-panel.component.html',
  styleUrl: './user-effective-permissions-panel.component.css',
})
export class UserEffectivePermissionsPanelComponent {
  /** Texts of the radio options below; translated again when the language changes. */
  private readonly optionText = inject(I18nService);

  private readonly usersApi = inject(UsersApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly userId = input.required<number>();
  /** The user's revision the screen read: the personal rights are saved from it (plan item 3.6). */
  readonly revision = input<number | undefined>(undefined);

  readonly canAssign = input<boolean>(false);
  readonly userRoleNames = input<string[]>([]);

  /** The user's revision after a save of the personal rights, for the screen to keep its copy in step. */
  readonly revisionChange = output<number>();
  /** The user changed since the screen read it: the screen reads the user again. */
  readonly staleUser = output<void>();

  readonly isSaving = signal<boolean>(false);
  /** Another user drops the draft of the previous one. */
  readonly hasUnsavedChanges = linkedSignal({ source: this.userId, computation: () => false });

  readonly searchQuery = signal<string>('');
  readonly sourceFilter = signal<'all' | 'role' | 'personal'>('all');

  readonly selectedFormCode = signal<string>('');
  readonly selectedAction = signal<string>('');

  readonly isLoading = computed(() => this.effectiveRead.isLoading());
  readonly loadError = computed(() => this.effectiveRead.value() === null);

  readonly roleCount = computed(() => this.effectiveItems().filter((i) => i.source === 'role').length);
  readonly personalCount = computed(() => this.effectiveItems().filter((i) => i.source === 'personal').length);

  readonly uniqueForms = computed(() => {
    const map = new Map<string, { formCode: string; formName: string }>();
    for (const f of this.formCatalog()) {
      if (!map.has(f.formCode)) {
        map.set(f.formCode, { formCode: f.formCode, formName: f.formName });
      }
    }
    return Array.from(map.values()).sort((a, b) => a.formName.localeCompare(b.formName));
  });

  readonly availableActionsForSelectedForm = computed(() => {
    const code = this.selectedFormCode();
    if (!code) return [];
    return this.formCatalog().filter((f) => f.formCode === code);
  });

  readonly formOptions = computed<SMTSelectOption<string>[]>(() =>
    this.uniqueForms().map((f) => ({ id: f.formCode, label: `${f.formName} (${f.formCode})` })),
  );

  readonly actionOptions = computed<SMTSelectOption<string>[]>(() =>
    this.availableActionsForSelectedForm().map((act) => ({
      id: act.action,
      label: `${act.actionName} (${act.action})`,
    })),
  );

  readonly filteredItems = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const sf = this.sourceFilter();
    return this.effectiveItems().filter((item) => {
      if (sf !== 'all' && item.source !== sf) return false;
      if (!q) return true;
      return item.form.toLowerCase().includes(q) || item.action.toLowerCase().includes(q);
    });
  });

  readonly groupedModules = computed<GroupedPermissionModule[]>(() => {
    const items = this.filteredItems();
    const catalog = this.formCatalog();

    // Map formCode to metadata
    const metaMap = new Map<string, { formName: string; module: string }>();
    for (const cat of catalog) {
      if (!metaMap.has(cat.formCode)) {
        metaMap.set(cat.formCode, { formName: cat.formName, module: cat.module });
      }
    }

    const modMap = new Map<string, Map<string, GroupedPermissionAction[]>>();

    for (const item of items) {
      const meta = metaMap.get(item.form) || { formName: item.form, module: 'system' };
      const modCode = meta.module;
      if (!modMap.has(modCode)) {
        modMap.set(modCode, new Map());
      }
      const formsMap = modMap.get(modCode)!;
      if (!formsMap.has(item.form)) {
        formsMap.set(item.form, []);
      }
      formsMap.get(item.form)!.push({ action: item.action, source: item.source });
    }

    const result: GroupedPermissionModule[] = [];
    for (const [modCode, formsMap] of modMap.entries()) {
      const forms: GroupedPermissionForm[] = [];
      for (const [formCode, actions] of formsMap.entries()) {
        const meta = metaMap.get(formCode);
        forms.push({
          formCode,
          formName: meta ? meta.formName : formCode,
          actions,
        });
      }
      forms.sort((a, b) => a.formName.localeCompare(b.formName));

      const nameKey = MODULE_NAME_KEY_MAP[modCode];
      const moduleName = nameKey ? this.uiI18n.translate(nameKey) : modCode.toUpperCase();
      const icon = MODULE_ICON_MAP[modCode] || 'widgets';

      result.push({ moduleCode: modCode, moduleName, icon, forms });
    }

    result.sort((a, b) => a.moduleName.localeCompare(b.moduleName));
    return result;
  });

  /** The sources as chips with how many rights come from each; the pills before announced no choice at all. */
  readonly sourceOptions = computed<SMTRadioOption<'all' | 'role' | 'personal'>[]>(() => {
    this.optionText.currentLang();
    return [
      {
        value: 'all',
        label: this.optionText.translate('iam.users.permissions.all_sources'),
        count: this.effectiveItems().length,
      },
      { value: 'role', label: this.optionText.translate('iam.users.permissions.source_role'), count: this.roleCount() },
      {
        value: 'personal',
        label: this.optionText.translate('iam.users.permissions.source_personal'),
        count: this.personalCount(),
      },
    ];
  });
  /** Its own computed, so that another user (still some user) does not ask for the catalog again. */
  private readonly hasUser = computed(() => !!this.userId());

  /* The server's answers; the panel edits the effective list and the grants as a draft
     until the next answer replaces them. */
  private readonly effectiveRead = rxResource({
    params: () => this.userId() || undefined,
    stream: ({ params }) =>
      orNull(this.usersApi.effectivePermissions(params).pipe(map((res) => [...(res?.items || [])]))),
  });
  private readonly personalRead = rxResource({
    params: () => this.userId() || undefined,
    stream: ({ params }) =>
      orNull(this.usersApi.personalPermissions(params).pipe(map((res) => [...(res?.grants || [])]))),
  });
  /** The same for every user: asked once, and again by a retry while still empty. */
  private readonly catalogRead = rxResource({
    params: () => this.hasUser() || undefined,
    stream: () =>
      orNull(this.usersApi.permissionForms().pipe(map((catalog) => (Array.isArray(catalog) ? catalog : null)))),
  });

  readonly effectiveItems = lastLoaded<EffectivePermissionItem[]>(() => this.effectiveRead.value(), []);
  readonly personalGrants = lastLoaded<PersonalGrant[]>(() => this.personalRead.value(), []);
  readonly formCatalog = lastLoaded<FormTreeItem[]>(() => this.catalogRead.value(), []);

  /** Asks the server again: the rights, the grants and, while it is still empty, the catalog. */
  loadAll(): void {
    if (!this.userId()) return;
    this.effectiveRead.reload();
    this.personalRead.reload();
    if (this.formCatalog().length === 0) this.catalogRead.reload();
  }

  onFormSelect(formCode: string): void {
    this.selectedFormCode.set(formCode);
    this.selectedAction.set('');
  }

  addPersonalGrant(): void {
    const form = this.selectedFormCode();
    const action = this.selectedAction();
    if (!form || !action) return;

    const currentGrants = [...this.personalGrants()];
    const exists = currentGrants.some((g) => g.form === form && g.action === action);
    if (exists) return;

    const updated = [...currentGrants, { form, action }];
    this.personalGrants.set(updated);
    this.hasUnsavedChanges.set(true);

    // Also optimistically add to effective items if not present
    const currentEffective = [...this.effectiveItems()];
    const effectiveIndex = currentEffective.findIndex((i) => i.form === form && i.action === action);
    if (effectiveIndex < 0) {
      this.effectiveItems.set([...currentEffective, { form, action, source: 'personal' }]);
    }

    this.selectedAction.set('');
  }

  removePersonalGrant(form: string, action: string): void {
    const updatedGrants = this.personalGrants().filter((g) => !(g.form === form && g.action === action));
    this.personalGrants.set(updatedGrants);
    this.hasUnsavedChanges.set(true);

    // Update effective items
    const updatedEffective = this.effectiveItems().filter(
      (i) => !(i.form === form && i.action === action && i.source === 'personal'),
    );
    this.effectiveItems.set(updatedEffective);
  }

  savePersonalGrants(): void {
    this.isSaving.set(true);
    this.usersApi.savePersonalPermissions(this.userId(), this.personalGrants(), this.revision()).subscribe({
      next: (saved) => {
        this.isSaving.set(false);
        this.hasUnsavedChanges.set(false);
        this.toast.success(this.uiI18n.translate('iam.users.permissions.saved'));
        if (typeof saved?.revision === 'number') this.revisionChange.emit(saved.revision);
        this.loadAll();
      },
      error: (err: unknown) => {
        this.isSaving.set(false);
        this.saveErrors.show(err, {
          fallbackKey: 'iam.common.save_permissions_failed',
          reload: () => {
            this.hasUnsavedChanges.set(false);
            this.loadAll();
            this.staleUser.emit();
          },
        });
      },
    });
  }
}

/** A failed read gives null: the panel keeps what it shows. */
function orNull<T>(request: Observable<T | null>): Observable<T | null> {
  return request.pipe(catchError(() => of(null)));
}
