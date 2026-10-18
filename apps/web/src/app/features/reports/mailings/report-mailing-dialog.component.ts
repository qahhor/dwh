import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { QueryListMeta } from '@core/models/query-meta.models';
import { AuthService } from '@core/services/auth.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';
import type { ParameterValue } from '@shared/entity/report/report-analytics';
import { measureLabel } from '@shared/entity/report/entity-reports';
import { SMTReportParametersComponent } from '@shared/entity/report/smt-report-parameters.component';
import { LookupSources, type UserRef } from '@shared/lookups/lookup-sources';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTMultiDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { SMTTimePickerComponent } from '@shared/ui-kit/components/forms/time-picker';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import {
  MAILING_CHANNELS,
  MAILING_CONDITIONS,
  MAILING_OPS,
  MAILING_WEEKDAYS,
  browserZone,
  timeZones,
  weekdayKey,
} from './mailing-words';
import {
  ReportMailingsApi,
  type MailingChannel,
  type ReportMailing,
  type ReportMailingOptions,
  type ReportMailingRequest,
} from './report-mailings.api';

/**
 * The dialog of a report mailing (ADR-0045): its name, the users it goes to, the channels this installation delivers
 * on, the format (an XLSX in a letter, a link), the schedule at a local time of a time zone, the values of the report's
 * parameters, the condition and whether it is active. The server checks every part; its messages stand under the part
 * to fix. Each recipient later gets the report built with their own rights.
 */
@Component({
  selector: 'app-report-mailing-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTInputComponent,
    SMTMultiDataSelectComponent,
    SMTReportParametersComponent,
    SMTSelectComponent,
    SMTSwitchComponent,
    SMTTimePickerComponent,
    TranslatePipe,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
  ],
  templateUrl: './report-mailing-dialog.component.html',
  styleUrl: './report-mailing-dialog.component.css',
})
export class ReportMailingDialogComponent {
  readonly lookups = inject(LookupSources);
  private readonly api = inject(ReportMailingsApi);
  private readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  readonly report = input.required<ReportViewItem>();
  readonly options = input.required<ReportMailingOptions>();
  /** The metadata of the report's list, for its parameters and measures; null while it loads. */
  readonly meta = input<QueryListMeta | null>(null);
  /** The mailing to change; null — a new one. */
  readonly mailing = input<ReportMailing | null>(null);

  readonly saved = output<ReportMailing>();
  readonly closed = output<void>();

  readonly name = signal('');
  readonly recipients = signal<number[]>([]);
  readonly knownRecipients = signal<UserRef[]>([]);
  readonly channels = signal<MailingChannel[]>([]);
  readonly attachXlsx = signal(true);
  readonly includeLink = signal(true);
  readonly frequency = signal<string>('daily');
  readonly weekday = signal<number>(1);
  readonly monthDay = signal<string>('1');
  readonly time = signal('08:00');
  readonly timeZone = signal(browserZone());
  readonly parameters = signal<Record<string, ParameterValue>>({});
  readonly conditionKind = signal<string>('always');
  readonly measure = signal<number>(0);
  readonly op = signal<string>('gt');
  readonly value = signal('');
  readonly active = signal(true);
  readonly busy = signal(false);
  readonly errors = signal<Record<string, string>>({});
  readonly alert = signal('');

  readonly creating = computed(() => this.mailing() === null);
  /** The server's messages about the parameters, which stand under the parameters block. */
  readonly parameterProblems = computed(() =>
    Object.entries(this.errors())
      .filter(([field]) => field.startsWith('parameters'))
      .map(([, message]) => message),
  );
  readonly reportParameters = computed(() => [...(this.report().state.parameters ?? [])]);
  readonly maxMegabytes = computed(() => Math.max(1, Math.floor(this.options().maxAttachmentBytes / (1024 * 1024))));
  readonly channelOptions = computed(() => {
    this.i18n.currentLang();
    return MAILING_CHANNELS.filter((channel) => this.options().channels.includes(channel)).map((channel) => ({
      id: channel,
      label: this.i18n.translate(`reports.mailings.channel.${channel}`),
    }));
  });
  readonly frequencyOptions = computed(() =>
    this.choices('reports.mailings.frequency', ['daily', 'weekly', 'monthly']),
  );
  readonly weekdayOptions = computed<SMTSelectOption<number>[]>(() => {
    this.i18n.currentLang();
    return MAILING_WEEKDAYS.map((_day, index) => ({
      id: index + 1,
      label: this.i18n.translate(weekdayKey(index + 1)),
    }));
  });
  readonly zoneOptions = computed<SMTSelectOption<string>[]>(() =>
    timeZones(this.timeZone()).map((zone) => ({ id: zone, label: zone })),
  );
  readonly conditionOptions = computed(() => this.choices('reports.mailings.condition', [...MAILING_CONDITIONS]));
  readonly opOptions = computed(() => this.choices('reports.mailings.op', [...MAILING_OPS]));
  readonly measureOptions = computed<SMTSelectOption<number>[]>(() => {
    this.i18n.currentLang();
    return (this.report().state.measures ?? []).map((measure, index) => ({
      id: index,
      label: measureLabel(measure, this.meta(), (key) => this.i18n.translate(key)),
    }));
  });

  constructor() {
    effect(() => {
      const mailing = this.mailing();
      untracked(() => this.reset(mailing));
    });
  }

  toggleChannel(channel: MailingChannel, on: boolean): void {
    this.channels.update((chosen) =>
      on ? [...new Set([...chosen, channel])] : chosen.filter((candidate) => candidate !== channel),
    );
  }

  hasChannel(channel: string): boolean {
    return this.channels().includes(channel as MailingChannel);
  }

  addMe(): void {
    const me = this.auth.currentUser();
    if (!me) return;
    this.knownRecipients.update((known) => [...known, { id: me.id, name: me.name, login: me.login }]);
    this.recipients.update((ids) => (ids.includes(me.id) ? ids : [...ids, me.id]));
  }

  error(field: string): string {
    return this.errors()[field] ?? '';
  }

  submit(): void {
    if (this.busy()) return;
    const current = this.mailing();
    this.busy.set(true);
    this.errors.set({});
    this.alert.set('');
    const body = this.body();
    const request = current ? this.api.update(current.id, current.revision, body) : this.api.create(body);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (mailing) => {
        this.busy.set(false);
        this.saved.emit(mailing);
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        const found = problemFieldErrors(failure);
        this.errors.set({ ...found.fields });
        const status = (failure as { status?: number }).status;
        this.alert.set(
          status === 409 || status === 428
            ? this.i18n.translate('reports.mailings.conflict')
            : [problemText(failure) || this.i18n.translate('reports.mailings.save_failed'), ...found.other].join(' '),
        );
      },
    });
  }

  private body(): ReportMailingRequest {
    const frequency = this.frequency();
    const threshold = this.conditionKind() === 'threshold';
    return {
      ...(this.creating() ? { viewId: this.report().id } : {}),
      name: this.name().trim(),
      recipientIds: this.recipients(),
      channels: this.channels(),
      attachXlsx: this.attachXlsx(),
      includeLink: this.includeLink(),
      schedule: {
        frequency,
        time: this.time(),
        timeZone: this.timeZone(),
        ...(frequency === 'weekly' ? { weekday: this.weekday() } : {}),
        ...(frequency === 'monthly' ? { monthDay: Number(this.monthDay()) } : {}),
      },
      parameters: this.parameters(),
      condition: {
        kind: this.conditionKind(),
        ...(threshold ? { measure: this.measure(), op: this.op(), value: Number(this.value()) } : {}),
      },
      active: this.active(),
    } as ReportMailingRequest;
  }

  private choices(prefix: string, values: string[]): SMTSelectOption<string>[] {
    this.i18n.currentLang();
    return values.map((value) => ({ id: value, label: this.i18n.translate(`${prefix}.${value}`) }));
  }

  private reset(mailing: ReportMailing | null): void {
    this.errors.set({});
    this.alert.set('');
    if (!mailing) {
      this.name.set(this.report().name);
      this.channels.set(this.options().channels.includes('email') ? ['email', 'in_app'] : ['in_app']);
      this.parameters.set({});
      this.addMe();
      return;
    }
    this.name.set(mailing.name);
    this.knownRecipients.set(mailing.recipients.map((user) => ({ id: user.id, name: user.name, login: user.login })));
    this.recipients.set(mailing.recipients.map((user) => user.id));
    this.channels.set([...mailing.channels] as MailingChannel[]);
    this.attachXlsx.set(mailing.attachXlsx);
    this.includeLink.set(mailing.includeLink);
    this.frequency.set(mailing.schedule.frequency);
    this.weekday.set(mailing.schedule.weekday ?? 1);
    this.monthDay.set(String(mailing.schedule.monthDay ?? 1));
    this.time.set(mailing.schedule.time);
    this.timeZone.set(mailing.schedule.timeZone);
    this.parameters.set({ ...(mailing.parameters as Record<string, ParameterValue>) });
    this.conditionKind.set(mailing.condition.kind);
    this.measure.set(mailing.condition.measure ?? 0);
    this.op.set(mailing.condition.op ?? 'gt');
    this.value.set(mailing.condition.value === undefined ? '' : String(mailing.condition.value));
    this.active.set(mailing.active);
  }
}
