import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  Signal,
  TemplateRef,
  inject,
  signal,
  computed,
  viewChild,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormField, form, maxLength, required } from '@angular/forms/signals';
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
import {
  WebhookSubscription,
  CreatedWebhookSubscription,
  CreateWebhookSubscriptionDto,
  AVAILABLE_WEBHOOK_EVENTS,
  WebhookEventOption,
} from './webhooks-settings.models';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';

/** What the person types in the create dialog. */
interface WebhookCreateModel {
  name: string;
  targetUrl: string;
}

const EMPTY_CREATE: WebhookCreateModel = { name: '', targetUrl: '' };

@Component({
  selector: 'app-webhooks-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTCheckboxComponent,
    SMTInputComponent,
    FormField,
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
export class WebhooksSettingsComponent implements OnInit {
  private readonly webhooks = inject(WebhooksApi);
  private readonly toast = inject(ToastService);
  private readonly uiI18n = inject(I18nService);
  private readonly permService = inject(PermissionService);
  private readonly modal = inject(SMTModalService);

  private readonly idCell = viewChild.required<TemplateRef<unknown>>('idCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly urlCell = viewChild.required<TemplateRef<unknown>>('urlCell');
  private readonly eventsCell = viewChild.required<TemplateRef<unknown>>('eventsCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly subscriptions = signal<WebhookSubscription[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isSaving = signal<boolean>(false);
  readonly loadError = signal<boolean>(false);

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly createdSecretModalOpen = signal<boolean>(false);
  readonly recentlyCreatedSubscription = signal<CreatedWebhookSubscription | null>(null);

  readonly createModel = signal<WebhookCreateModel>({ ...EMPTY_CREATE });

  readonly tableConfig = computed<TableConfig<WebhookSubscription>>(() => {
    const i18n = this.uiI18n;
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    const columns: TableConfig<WebhookSubscription>['columns'] = {
      id: { header: header('ID'), content: cell(this.idCell), width: '80px' },
      name: { header: header(i18n.translate('settings.webhooks.name')), content: cell(this.nameCell) },
      url: { header: header(i18n.translate('settings.webhooks.target_url')), content: cell(this.urlCell) },
      events: { header: header(i18n.translate('settings.webhooks.events')), content: cell(this.eventsCell) },
      status: {
        header: header(i18n.translate('settings.webhooks.status')),
        content: cell(this.statusCell),
        width: '130px',
      },
    };
    const order = ['id', 'name', 'url', 'events', 'status'];
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

  readonly canManageWebhooks = computed(() => this.permService.hasPermission('platform.webhooks', 'manage'));

  /** Every subscription is loaded, so a header click sorts the whole list. */
  readonly sortValues = {
    id: (sub: WebhookSubscription) => sub.id,
    name: (sub: WebhookSubscription) => sub.name,
    url: (sub: WebhookSubscription) => sub.targetUrl,
    status: (sub: WebhookSubscription) => (sub.state === 'A' ? 0 : 1),
  };

  /** Both fields are required (the kit marks a blank one once it was left); maxLength also caps the typing. */
  readonly createForm = form(this.createModel, (path) => {
    required(path.name);
    maxLength(path.name, 100);
    required(path.targetUrl);
    maxLength(path.targetUrl, 500);
  });

  selectedEvents = new Set<string>(['*']);

  readonly availableEvents: WebhookEventOption[] = AVAILABLE_WEBHOOK_EVENTS;

  ngOnInit(): void {
    this.loadSubscriptions();
  }

  loadSubscriptions(): void {
    this.isLoading.set(true);
    this.loadError.set(false);
    this.webhooks.list().subscribe({
      next: (subs) => {
        this.subscriptions.set(Array.isArray(subs) ? subs : []);
        this.isLoading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.isLoading.set(false);
      },
    });
  }

  openCreateModal(): void {
    // A new dialog starts blank and untouched, so no field shows an error before it is used.
    this.createForm().reset({ ...EMPTY_CREATE });
    this.selectedEvents = new Set(['*']);
    this.isCreateModalOpen.set(true);
  }

  closeCreateModal(): void {
    this.isCreateModalOpen.set(false);
  }

  onEventCheck(code: string, checked: boolean): void {
    if (checked) {
      this.selectedEvents.add(code);
    } else {
      this.selectedEvents.delete(code);
    }
  }

  isAllEventsSelected(): boolean {
    return this.selectedEvents.size === this.availableEvents.length;
  }

  toggleAllEvents(): void {
    if (this.isAllEventsSelected()) {
      this.selectedEvents.clear();
    } else {
      for (const ev of this.availableEvents) {
        this.selectedEvents.add(ev.code);
      }
    }
  }

  isCreateValid(): boolean {
    const { name, targetUrl } = this.createModel();
    // Blank text passes required(), so the trimmed check stays.
    return (
      this.createForm().valid() && name.trim().length > 0 && targetUrl.trim().length > 0 && this.selectedEvents.size > 0
    );
  }

  submitCreate(): void {
    markSMTFormFieldsTouched(this.createForm);
    if (!this.isCreateValid()) return;
    this.isSaving.set(true);

    const body: CreateWebhookSubscriptionDto = {
      name: this.createModel().name.trim(),
      targetUrl: this.createModel().targetUrl.trim(),
      subscribedEvents: Array.from(this.selectedEvents),
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
        this.toast.error(problemText(err) || this.uiI18n.translate('common.error'));
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
    this.webhooks.setState(sub.id, nextState).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('settings.webhooks.updated_success'));
        this.loadSubscriptions();
      },
      error: () => {},
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
}
