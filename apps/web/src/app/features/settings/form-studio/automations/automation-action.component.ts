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
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { FormMeta } from '@core/models/form-meta.models';
import { FormMetaService } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { LookupSources, type UserRef } from '@shared/lookups/lookup-sources';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTMultiDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTMultiSelectComponent } from '@shared/ui-kit/components/forms/multi-select';
import { SMTSelectComponent, type SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea/textarea.component';
import { AutomationFormulaComponent } from './automation-formula.component';
import {
  NOTIFY_CHANNELS,
  fieldLabel,
  isUserField,
  readableNames,
  transitionLabel,
  writableFields,
} from './automation-words';
import type { AutomationAction, AutomationTexts, TriggerKind } from './automations.api';

/** A live form the editor may create a record of. */
export interface AutomationFormChoice {
  code: string;
  title: string;
}

type Language = 'ru' | 'uz' | 'en';

/**
 * One action of an automation in its editor (ADR-0046): the fields it writes with the formula of each value, the live
 * form a new record goes to, the transition it runs with its comment, or a notification — its channels, who gets it
 * and its texts in each language with the record's values in `{field}` placeholders. The server checks each part on
 * save; its messages stand under the part to fix.
 */
@Component({
  selector: 'app-automation-action',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AutomationFormulaComponent,
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTInputComponent,
    SMTMultiDataSelectComponent,
    SMTMultiSelectComponent,
    SMTSelectComponent,
    SMTTextareaComponent,
    TranslatePipe,
  ],
  templateUrl: './automation-action.component.html',
  styleUrl: './automation-action.component.css',
})
export class AutomationActionComponent {
  readonly lookups = inject(LookupSources);
  private readonly formMeta = inject(FormMetaService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly action = input.required<AutomationAction>();
  readonly index = input.required<number>();
  /** The live form of the automation and its form-meta. */
  readonly form = input.required<string>();
  readonly trigger = input.required<TriggerKind>();

  readonly count = input(1);
  readonly meta = input<FormMeta | null>(null);
  /** The live forms a record may be created in. */
  readonly forms = input<readonly AutomationFormChoice[]>([]);
  /** The channels this installation delivers on. */
  readonly channels = input<readonly string[]>(['in_app']);
  /** The server's messages by the part they name (`actions[1].values[0].formula`). */
  readonly errors = input<Record<string, string>>({});

  readonly changed = output<AutomationAction>();
  readonly removed = output<void>();
  readonly moved = output<-1 | 1>();

  readonly targetMeta = signal<FormMeta | null>(null);
  readonly knownUsers = signal<UserRef[]>([]);

  readonly prefix = computed(() => `actions[${this.index()}]`);
  readonly typeLabel = computed(() => {
    this.i18n.currentLang();
    return this.i18n.translate(`studio.automations.action.${this.action().type}`);
  });
  /** The form whose fields the action writes: the automation's own, or the target of a new record. */
  readonly writtenMeta = computed(() => (this.action().type === 'create_record' ? this.targetMeta() : this.meta()));
  readonly fieldOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return writableFields(this.writtenMeta()).map((field) => ({
      id: field.key,
      label: fieldLabel(field, (key) => this.i18n.translate(key)),
    }));
  });
  /** The names a value's formula reads: the record's fields and, for a change, the values before it. */
  readonly nameOptions = computed<SMTSelectOption<string>[]>(() => {
    const names = readableNames(this.meta());
    const change = ['updated', 'saved', 'transition'].includes(this.trigger());
    return [...names, ...(change ? names.map((name) => `old.${name}`) : [])].map((name) => ({
      id: name,
      label: name,
    }));
  });
  readonly formOptions = computed<SMTSelectOption<string>[]>(() =>
    this.forms().map((choice) => ({ id: choice.code, label: `${choice.title} (${choice.code})` })),
  );
  readonly transitionOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return (this.meta()?.workflow?.transitions ?? []).map((transition) => ({
      id: transition.code,
      label: transitionLabel(this.meta(), transition.code, (key) => this.i18n.translate(key)),
    }));
  });
  readonly channelOptions = computed(() => {
    this.i18n.currentLang();
    return NOTIFY_CHANNELS.filter((channel) => this.channels().includes(channel)).map((channel) => ({
      id: channel,
      label: this.i18n.translate(`studio.automations.channel.${channel}`),
    }));
  });
  readonly userFieldOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return (this.meta()?.fields ?? []).filter(isUserField).map((field) => ({
      id: field.key,
      label: fieldLabel(field, (key) => this.i18n.translate(key)),
    }));
  });
  /** The rights whose holders a notification may go to: the transitions of the form and its change. */
  readonly rightOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    const form = this.form();
    const rights = [
      { id: `${form}.update`, label: this.i18n.translate('studio.automations.right.update') },
      ...(this.meta()?.workflow?.transitions ?? []).map((transition) => ({
        id: `${form}.${transition.code}`,
        label: this.i18n.translate('studio.automations.right.transition', {
          name: transitionLabel(this.meta(), transition.code, (key) => this.i18n.translate(key)),
        }),
      })),
    ];
    return rights;
  });
  readonly placeholders = computed(() => ['form', ...readableNames(this.meta())].map((name) => `{${name}}`).join(', '));
  readonly rights = computed(() =>
    (this.action().recipients?.rights ?? []).map((right) => `${right.form}.${right.action}`),
  );

  readonly languages: readonly Language[] = ['ru', 'uz', 'en'];

  constructor() {
    effect((onCleanup) => {
      const target = this.action().type === 'create_record' ? this.action().form : null;
      if (!target) {
        this.targetMeta.set(null);
        return;
      }
      const request = this.formMeta
        .get(target)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: (meta) => this.targetMeta.set(meta), error: () => this.targetMeta.set(null) });
      onCleanup(() => request.unsubscribe());
    });
  }

  error(part: string): string {
    return this.errors()[`${this.prefix()}${part}`] ?? '';
  }

  change(changes: Partial<AutomationAction>): void {
    this.changed.emit({ ...this.action(), ...changes });
  }

  setValue(row: number, part: 'field' | 'formula', text: string | null | undefined): void {
    const values = [...(this.action().values ?? [])];
    values[row] = { ...values[row], [part]: text ?? '' };
    this.change({ values });
  }

  addValue(): void {
    this.change({ values: [...(this.action().values ?? []), { field: '', formula: '' }] });
  }

  removeValue(row: number): void {
    this.change({ values: (this.action().values ?? []).filter((_value, at) => at !== row) });
  }

  setTarget(form: string | null): void {
    this.change({ form: form ?? undefined, values: [{ field: '', formula: '' }] });
  }

  hasChannel(channel: string): boolean {
    return (this.action().channels ?? []).includes(channel as 'in_app');
  }

  toggleChannel(channel: string, on: boolean): void {
    const chosen = (this.action().channels ?? []).filter((candidate) => candidate !== channel);
    this.change({ channels: (on ? [...chosen, channel] : chosen) as AutomationAction['channels'] });
  }

  setRecipients(part: 'fields' | 'users' | 'author' | 'rights', value: unknown): void {
    const recipients = { ...(this.action().recipients ?? {}) };
    if (part === 'rights') {
      recipients.rights = (value as string[]).map((pair) => {
        const dot = pair.lastIndexOf('.');
        return { form: pair.slice(0, dot), action: pair.slice(dot + 1) };
      });
    } else if (part === 'author') {
      recipients.author = value === true ? true : undefined;
    } else if (part === 'users') {
      recipients.users = [...(value as number[])];
    } else {
      recipients.fields = [...(value as string[])];
    }
    this.change({ recipients });
  }

  text(part: 'title' | 'body', language: Language): string {
    return this.action()[part]?.[language] ?? '';
  }

  setText(part: 'title' | 'body', language: Language, text: string | null | undefined): void {
    const texts: AutomationTexts = { ru: '', ...(this.action()[part] ?? {}) };
    const value = text ?? '';
    if (language === 'ru') texts.ru = value;
    else if (value) texts[language] = value;
    else delete texts[language];
    this.change({ [part]: texts });
  }
}
