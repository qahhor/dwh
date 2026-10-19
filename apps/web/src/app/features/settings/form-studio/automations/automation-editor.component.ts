import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import type { Observable } from 'rxjs';
import type { FormMeta } from '@core/models/form-meta.models';
import { FormMetaService } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { leaveQuestion } from '@shared/ui/leave-question';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { SMTTimePickerComponent } from '@shared/ui-kit/components/forms/time-picker';
import { AutomationActionComponent, type AutomationFormChoice } from './automation-action.component';
import { AutomationDryRunComponent } from './automation-dry-run.component';
import { AutomationFormulaComponent } from './automation-formula.component';
import {
  ACTION_TYPES,
  TRIGGER_KINDS,
  WEEKDAYS,
  browserZone,
  fieldLabel,
  readableNames,
  stateLabel,
  timeZones,
} from './automation-words';
import {
  AutomationsApi,
  type ActionType,
  type Automation,
  type AutomationAction,
  type AutomationFormSummary,
  type AutomationOptions,
  type AutomationRequest,
  type TriggerKind,
} from './automations.api';

/**
 * The editor of an automation of a live form (ADR-0046), a page: what starts it, its condition, its actions in order,
 * whether it is on, and a dry run over a chosen record that shows what the actions would do — nothing applied. The
 * person who saves it becomes its author, the one it runs as; the server checks every part and its messages stand
 * under the part to fix.
 */
@Component({
  selector: 'app-automation-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AutomationActionComponent,
    AutomationDryRunComponent,
    AutomationFormulaComponent,
    RouterLink,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTMultiSelectComponent,
    SMTSelectComponent,
    SMTSwitchComponent,
    SMTTimePickerComponent,
    TranslatePipe,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    UiPageHeaderComponent,
  ],
  templateUrl: './automation-editor.component.html',
  styleUrl: './automation-editor.component.css',
})
export class AutomationEditorComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(AutomationsApi);
  private readonly formMeta = inject(FormMetaService);
  private readonly i18n = inject(I18nService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  readonly stored = signal<Automation | null>(null);
  readonly options = signal<AutomationOptions | null>(null);
  readonly forms = signal<AutomationFormChoice[]>([]);
  readonly form = signal('');
  readonly meta = signal<FormMeta | null>(null);
  readonly name = signal('');
  readonly active = signal(true);
  readonly kind = signal<TriggerKind>('saved');
  readonly fields = signal<string[]>([]);
  readonly from = signal<string[]>([]);
  readonly to = signal<string[]>([]);
  readonly frequency = signal<'daily' | 'weekly' | 'monthly'>('daily');
  readonly weekday = signal(1);
  readonly monthDay = signal('1');
  readonly time = signal('09:00');
  readonly timeZone = signal(browserZone());
  readonly condition = signal<string | undefined>(undefined);
  readonly actions = signal<AutomationAction[]>([]);
  readonly newAction = signal<ActionType>('notify');
  readonly busy = signal(false);
  readonly dirty = signal(false);
  readonly errors = signal<Record<string, string>>({});
  readonly alert = signal('');
  readonly loadError = signal('');

  readonly creating = computed(() => this.stored() === null);
  /** The automation as the editor holds it, for a save and a dry run. */
  readonly request = computed(() => this.body());
  readonly change = computed(() => ['updated', 'saved', 'transition'].includes(this.kind()));
  readonly formOptions = computed<SMTSelectOption<string>[]>(() =>
    this.forms().map((choice) => ({ id: choice.code, label: `${choice.title} (${choice.code})` })),
  );
  readonly kindOptions = computed(() => this.choices('studio.automations.trigger', [...TRIGGER_KINDS]));
  readonly actionOptions = computed(() => this.choices('studio.automations.action', [...ACTION_TYPES]));
  readonly frequencyOptions = computed(() =>
    this.choices('studio.automations.frequency', ['daily', 'weekly', 'monthly']),
  );
  readonly weekdayOptions = computed<SMTSelectOption<number>[]>(() => {
    this.i18n.currentLang();
    return WEEKDAYS.map((day, index) => ({
      id: index + 1,
      label: this.i18n.translate(`studio.automations.weekday.${day}`),
    }));
  });
  readonly zoneOptions = computed<SMTSelectOption<string>[]>(() =>
    timeZones(this.timeZone()).map((zone) => ({ id: zone, label: zone })),
  );
  readonly fieldOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return (this.meta()?.fields ?? [])
      .filter((field) => readableNames(this.meta()).includes(field.key))
      .map((field) => ({ id: field.key, label: fieldLabel(field, (key) => this.i18n.translate(key)) }));
  });
  readonly stateOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return (this.meta()?.workflow?.states ?? []).map((state) => ({
      id: state.code,
      label: stateLabel(this.meta(), state.code, (key) => this.i18n.translate(key)),
    }));
  });
  /** The names the condition reads: the fields, and for a change their old values and whether they changed. */
  readonly conditionNames = computed<SMTSelectOption<string>[]>(() => {
    const names = readableNames(this.meta());
    const extra = this.change()
      ? [...names.map((name) => `old.${name}`), ...names.map((name) => `changed.${name}`)]
      : [];
    return [...names, ...extra].map((name) => ({ id: name, label: name }));
  });

  private readonly askLeave = leaveQuestion();

  ngOnInit(): void {
    this.api
      .options()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (options) => this.options.set(options), error: () => this.options.set(null) });
    this.api
      .forms()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.forms.set(page.items.map((form) => this.formChoice(form))),
        error: () => this.forms.set([]),
      });
    const id = this.route.snapshot.paramMap.get('id');
    if (id && id !== 'new') {
      this.api
        .get(Number(id))
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (automation) => this.reset(automation),
          error: (failure: unknown) =>
            this.loadError.set(problemText(failure) || this.i18n.translate('studio.automations.load_failed')),
        });
      return;
    }
    this.chooseForm(this.route.snapshot.queryParamMap.get('form') ?? '');
    this.actions.set([this.blank('notify')]);
  }

  chooseForm(code: string | null): void {
    this.form.set(code ?? '');
    this.meta.set(null);
    if (!code) return;
    this.formMeta
      .get(code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (meta) => this.meta.set(meta), error: () => this.meta.set(null) });
  }

  touch(): void {
    this.dirty.set(true);
  }

  error(part: string): string {
    return this.errors()[part] ?? '';
  }

  addAction(): void {
    this.actions.update((actions) => [...actions, this.blank(this.newAction())]);
    this.touch();
  }

  changeAction(index: number, action: AutomationAction): void {
    this.actions.update((actions) => actions.map((current, at) => (at === index ? action : current)));
    this.touch();
  }

  removeAction(index: number): void {
    this.actions.update((actions) => actions.filter((_action, at) => at !== index));
    this.touch();
  }

  moveAction(index: number, step: -1 | 1): void {
    this.actions.update((actions) => {
      const next = [...actions];
      const target = index + step;
      if (target < 0 || target >= next.length) return actions;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    this.touch();
  }

  submit(): void {
    if (this.busy()) return;
    const current = this.stored();
    this.busy.set(true);
    this.errors.set({});
    this.alert.set('');
    const body = this.request();
    const request: Observable<Automation> = current
      ? this.api.update(current.id, current.revision, body)
      : this.api.create({ ...body, form: this.form() });
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (automation) => {
        this.busy.set(false);
        this.dirty.set(false);
        this.toast.success(this.i18n.translate('studio.automations.saved', { name: automation.name }));
        void this.router.navigate(['/settings/form-studio/automations'], { queryParams: { form: automation.form } });
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        const found = problemFieldErrors(failure);
        this.errors.set({ ...found.fields });
        const status = (failure as { status?: number }).status;
        this.alert.set(
          status === 409 || status === 428
            ? this.i18n.translate('studio.automations.conflict')
            : [problemText(failure) || this.i18n.translate('studio.automations.save_failed'), ...found.other].join(' '),
        );
      },
    });
  }

  cancel(): void {
    void this.router.navigate(['/settings/form-studio/automations'], {
      queryParams: this.form() ? { form: this.form() } : {},
    });
  }

  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (this.busy()) return false;
    return this.dirty() ? this.askLeave() : true;
  }

  private body(): AutomationRequest {
    const kind = this.kind();
    const frequency = this.frequency();
    const condition = this.condition()?.trim();
    return {
      name: this.name().trim(),
      active: this.active(),
      timeZone: this.timeZone(),
      trigger: {
        kind,
        ...((kind === 'updated' || kind === 'saved') && this.fields().length ? { fields: this.fields() } : {}),
        ...(kind === 'transition' && this.from().length ? { from: this.from() } : {}),
        ...(kind === 'transition' && this.to().length ? { to: this.to() } : {}),
        ...(kind === 'scheduled'
          ? {
              schedule: {
                frequency,
                time: this.time(),
                ...(frequency === 'weekly' ? { weekday: this.weekday() } : {}),
                ...(frequency === 'monthly' ? { monthDay: Number(this.monthDay()) } : {}),
              },
            }
          : {}),
      },
      ...(condition ? { condition } : {}),
      actions: this.actions(),
    };
  }

  private blank(type: ActionType): AutomationAction {
    switch (type) {
      case 'set_field':
        return { type, values: [{ field: '', formula: '' }] };
      case 'create_record':
        return { type, values: [] };
      case 'transition':
        return { type };
      default:
        return { type: 'notify', channels: ['in_app'], recipients: {}, title: { ru: '' }, body: { ru: '' } };
    }
  }

  private reset(automation: Automation): void {
    this.stored.set(automation);
    this.chooseForm(automation.form);
    this.name.set(automation.name);
    this.active.set(automation.active);
    this.kind.set(automation.trigger.kind);
    this.fields.set([...(automation.trigger.fields ?? [])]);
    this.from.set([...(automation.trigger.from ?? [])]);
    this.to.set([...(automation.trigger.to ?? [])]);
    const schedule = automation.trigger.schedule;
    this.frequency.set(schedule?.frequency ?? 'daily');
    this.weekday.set(schedule?.weekday ?? 1);
    this.monthDay.set(String(schedule?.monthDay ?? 1));
    this.time.set(schedule?.time ?? '09:00');
    this.timeZone.set(automation.timeZone);
    this.condition.set(automation.condition ?? undefined);
    this.actions.set(automation.actions.map((action) => ({ ...action })));
    this.dirty.set(false);
  }

  private formChoice(form: AutomationFormSummary): AutomationFormChoice {
    const language = this.i18n.currentLang() as 'ru' | 'uz' | 'en';
    return { code: form.entityCode, title: form.title[language] || form.title.ru || form.entityCode };
  }

  private choices<T extends string>(prefix: string, values: T[]): SMTSelectOption<T>[] {
    this.i18n.currentLang();
    return values.map((value) => ({ id: value, label: this.i18n.translate(`${prefix}.${value}`) }));
  }
}
