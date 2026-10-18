import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  Signal,
  TemplateRef,
  inject,
  signal,
  computed,
  viewChild,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormField, form, maxLength, required, validate } from '@angular/forms/signals';
import { WebhooksApi } from './webhooks.api';
import { ToastService } from '@core/services/toast.service';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { finalize, tap } from 'rxjs';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { problemText } from '@shared/ui/problem-text';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import {
  WebhookSubscription,
  CreatedWebhookSubscription,
  CreateWebhookSubscriptionDto,
  AVAILABLE_WEBHOOK_EVENTS,
  WebhookEventOption,
  WebhookScope,
} from './webhooks-settings.models';
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective, focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { UiFormErrorItem, UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { messageForError } from '@shared/ui-kit/components/forms/control/control-messages';

/** What the person types and picks in the create dialog. */
interface WebhookCreateModel {
  name: string;
  targetUrl: string;
  /** Event codes; `*` is every event. */
  events: string[];
  /** Which records are sent; the narrower choice is the default (ADR-0041, owner decisions of 2026-10-18). */
  scope: WebhookScope;
}

function emptyCreate(): WebhookCreateModel {
  return { name: '', targetUrl: '', events: ['*'], scope: 'creator' };
}

@Component({
  selector: 'app-webhooks-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTRadioGroupComponent,
    SMTInputComponent,
    FormField,
    UiFormActionsComponent,
    UiFormErrorSummaryComponent,
    UiFocusFirstInvalidDirective,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiLocalTableComponent,
    DatePipe,
  ],
  templateUrl: './webhooks-settings.component.html',
  styleUrl: './webhooks-settings.component.css',
})
export class WebhooksSettingsComponent {
  private readonly webhooks = inject(WebhooksApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly permService = inject(PermissionService);
  private readonly modal = inject(SMTModalService);
  private readonly saveErrors = inject(SaveErrorNotifier);
  private readonly injector = inject(Injector);
  private readonly kitI18n = inject(SMTI18nService);

  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly urlCell = viewChild.required<TemplateRef<unknown>>('urlCell');
  private readonly eventsCell = viewChild.required<TemplateRef<unknown>>('eventsCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly scopeCell = viewChild.required<TemplateRef<unknown>>('scopeCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly isSaving = signal<boolean>(false);

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly createdSecretModalOpen = signal<boolean>(false);
  readonly recentlyCreatedSubscription = signal<CreatedWebhookSubscription | null>(null);

  readonly createModel = signal<WebhookCreateModel>(emptyCreate());
  /** The server's refusal of the new subscription by field, already in words. */
  readonly serverErrors = signal<Readonly<Record<string, string>>>({});
  readonly otherErrors = signal<readonly string[]>([]);
  readonly availableEvents = signal<WebhookEventOption[]>(AVAILABLE_WEBHOOK_EVENTS);

  readonly tableConfig = computed<TableConfig<WebhookSubscription>>(() => {
    const i18n = this.uiI18n;
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const columns: TableConfig<WebhookSubscription>['columns'] = {
      id: { header: header('ID'), content: cell(this.idCell), width: '80px' },
      name: { header: header(i18n.translate('settings.webhooks.name')), content: cell(this.nameCell) },
      url: { header: header(i18n.translate('settings.webhooks.target_url')), content: cell(this.urlCell) },
      events: { header: header(i18n.translate('settings.webhooks.events')), content: cell(this.eventsCell) },
      scope: { header: header(i18n.translate('settings.webhooks.scope')), content: cell(this.scopeCell) },
      status: {
        header: header(i18n.translate('settings.webhooks.status')),
        content: cell(this.statusCell),
        width: '130px',
      },
    };
    const order = ['id', 'name', 'url', 'events', 'scope', 'status'];
    if (this.canManageWebhooks()) {
      columns['actions'] = {
        header: header(i18n.translate('common.actions')),
        content: cell(this.actionsCell),
        width: '120px',
        align: 'right',
      };
      order.push('actions');
    }
    return {
      trackBy: (_index, sub) => sub.id,
      ariaLabel: i18n.translate('settings.webhooks.title'),
      layout: 'fit',
      columns,
      columnsOrder: order,
    };
  });

  /** After a failed load the error banner replaces the list, so no stale rows are kept. */
  readonly subscriptions = computed(() => {
    const subs = this.subscriptionsResource.hasValue() ? this.subscriptionsResource.value() : [];
    return Array.isArray(subs) ? subs : [];
  });
  readonly isLoading = computed(() => this.subscriptionsResource.isLoading());
  readonly loadError = computed(() => this.subscriptionsResource.error() !== undefined);

  /** The two scopes, each with what it sends; the labels follow the language of work. */
  readonly scopeOptions = computed<readonly SMTRadioOption<WebhookScope>[]>(() => [
    {
      value: 'creator',
      label: this.uiI18n.translate('settings.webhooks.scope_creator'),
      hint: this.uiI18n.translate('settings.webhooks.scope_creator_hint'),
    },
    {
      value: 'all',
      label: this.uiI18n.translate('settings.webhooks.scope_all'),
      hint: this.uiI18n.translate('settings.webhooks.scope_all_hint'),
    },
  ]);

  readonly canManageWebhooks = computed(() => this.permService.hasPermission('webhook.subscriptions', 'manage'));

  private readonly askDiscard = discardChangesQuestion();

  /** Every subscription is loaded, so a header click sorts the whole list. */
  readonly sortValues = {
    id: (sub: WebhookSubscription) => sub.id,
    name: (sub: WebhookSubscription) => sub.name,
    url: (sub: WebhookSubscription) => sub.targetUrl,
    scope: (sub: WebhookSubscription) => sub.scope,
    status: (sub: WebhookSubscription) => (sub.state === 'A' ? 0 : 1),
  };

  /**
   * Name, address and at least one event are required; a text of spaces counts as empty. Errors show once a field
   * is left or a save is tried (forms standard, section 4); maxLength also caps the typing.
   */
  readonly createForm = form(this.createModel, (path) => {
    required(path.name);
    maxLength(path.name, 100);
    required(path.targetUrl);
    maxLength(path.targetUrl, 500);
    for (const text of [path.name, path.targetUrl]) {
      validate(text, ({ value }) => (value().length > 0 && !value().trim() ? { kind: 'required' } : null));
    }
    validate(path.events, ({ value }) =>
      value().length === 0
        ? { kind: 'events', message: this.uiI18n.translate('settings.webhooks.events_required') }
        : null,
    );
  });

  private readonly subscriptionsResource = rxResource({ stream: () => this.webhooks.list() });

  constructor() {
    this.webhooks
      .events()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (events) => {
          if (Array.isArray(events) && events.length > 0) {
            this.availableEvents.set(events);
          }
        },
        error: () => {},
      });
  }

  loadSubscriptions(): void {
    this.subscriptionsResource.reload();
  }

  openCreateModal(): void {
    // A new dialog starts blank and untouched, so no field shows an error before it is used.
    this.createForm().reset(emptyCreate());
    this.serverErrors.set({});
    this.otherErrors.set([]);
    this.isCreateModalOpen.set(true);
  }

  closeCreateModal(): void {
    this.isCreateModalOpen.set(false);
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before typed values are dropped (forms standard, 8). */
  requestCloseCreate(): void {
    if (this.isSaving()) return;
    const dirty = JSON.stringify(this.createModel()) !== JSON.stringify(emptyCreate());
    this.askDiscard(dirty).subscribe((discard) => {
      if (discard) this.closeCreateModal();
    });
  }

  /** "Pick at least one event", once the events were touched or a save was tried. */
  eventsError(): string {
    const events = this.createForm.events();
    return events.touched() && events.invalid()
      ? (events.errors()[0]?.message ?? '')
      : (this.serverErrors()['events'] ?? '');
  }

  onEventCheck(code: string, checked: boolean): void {
    this.setEvents(
      checked
        ? [...new Set([...this.createModel().events, code])]
        : this.createModel().events.filter((event) => event !== code),
    );
  }

  isAllEventsSelected(): boolean {
    return this.createModel().events.length === this.availableEvents().length;
  }

  toggleAllEvents(): void {
    this.setEvents(this.isAllEventsSelected() ? [] : this.availableEvents().map((event) => event.code));
  }

  isCreateValid(): boolean {
    return this.createForm().valid();
  }

  /** Enter and the primary button land here; one request while a save runs. */
  submitCreate(): void {
    if (this.isSaving()) return;
    markSMTFormFieldsTouched(this.createForm);
    if (!this.isCreateValid()) return;
    this.isSaving.set(true);
    this.serverErrors.set({});
    this.otherErrors.set([]);

    const model = this.createModel();
    const body: CreateWebhookSubscriptionDto = {
      name: model.name.trim(),
      targetUrl: model.targetUrl.trim(),
      subscribedEvents: [...model.events],
      scope: model.scope,
    };

    this.webhooks.create(body).subscribe({
      next: (created) => {
        this.isSaving.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('settings.webhooks.created_success'));
        this.recentlyCreatedSubscription.set(created);
        this.createdSecretModalOpen.set(true);
        this.loadSubscriptions();
      },
      error: (err: unknown) => {
        this.isSaving.set(false);
        const { fields, other } = problemFieldErrors(err, {
          known: ['name', 'targetUrl', 'events', 'scope'],
          rename: { subscribedEvents: 'events' },
        });
        this.serverErrors.set(fields);
        this.otherErrors.set(other);
        if (Object.keys(fields).length > 0) {
          const formElement = document.getElementById('webhook-create');
          if (formElement) focusFirstInvalid(formElement, this.injector);
        }
        if (Object.keys(fields).length === 0 && other.length === 0) {
          this.toast.error(problemText(err) || this.uiI18n.translate('common.error'));
        }
      },
    });
  }

  closeSecretModal(): void {
    this.createdSecretModalOpen.set(false);
    this.recentlyCreatedSubscription.set(null);
  }

  copySecret(secret: string): void {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(secret);
      this.toast.success(this.uiI18n.translate('settings.webhooks.secret_copied'));
    }
  }

  toggleState(sub: WebhookSubscription): void {
    const nextState = sub.state === 'A' ? 'P' : 'A';
    this.webhooks.setState(sub.id, nextState, sub.revision).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('settings.webhooks.updated_success'));
        this.loadSubscriptions();
      },
      error: (err: unknown) => {
        this.saveErrors.show(err, { fallbackKey: 'common.error', reload: () => this.loadSubscriptions() });
      },
    });
  }

  /** Asks before deleting a subscription; the dialog stays open until the server answers. */
  confirmDelete(sub: WebhookSubscription): void {
    this.modal
      .confirm({
        title: this.uiI18n.translate('common.confirm'),
        message: this.uiI18n.translate('settings.webhooks.delete_confirm', { name: sub.name }),
        yesLabel: this.uiI18n.translate('common.delete'),
        noLabel: this.uiI18n.translate('common.cancel'),
        destructive: true,
        action: () => {
          this.isSaving.set(true);
          return this.webhooks.remove(sub.id).pipe(
            tap(() => {
              this.toast.success(this.uiI18n.translate('settings.webhooks.deleted_success'));
              this.loadSubscriptions();
            }),
            finalize(() => this.isSaving.set(false)),
          );
        },
        actionError: problemText,
      })
      .subscribe();
  }

  errorSummary(): UiFormErrorItem[] {
    const fields = [
      ['name', 'webhook-name', 'settings.webhooks.name'],
      ['targetUrl', 'webhook-url', 'settings.webhooks.target_url'],
      ['events', 'webhook-events', 'settings.webhooks.events'],
      ['scope', 'webhook-scope', 'settings.webhooks.scope'],
    ] as const;
    const errors: UiFormErrorItem[] = fields.flatMap(([key, fieldId, label]) => {
      const field = this.createForm[key]();
      const message =
        this.serverErrors()[key] ??
        (field.touched() ? messageForError(field.errors()[0], this.kitI18n.messages().control) : '');
      return message ? [{ fieldId, label: this.uiI18n.translate(label), message }] : [];
    });
    return [...errors, ...this.otherErrors().map((message) => ({ message }))];
  }

  clearServerError(key: string): void {
    this.serverErrors.update(({ [key]: _previous, ...rest }) => rest);
  }

  private setEvents(events: string[]): void {
    this.clearServerError('events');
    const field = this.createForm.events();
    field.value.set(events);
    field.markAsTouched();
  }
}
