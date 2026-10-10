import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  Injector,
  viewChild,
  input,
  output,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { form, FormField, maxLength, required, validate } from '@angular/forms/signals';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { ProfileApi } from '../profile.api';
import { UserChannel } from '../profile.models';

/** Codes of a refused confirmation code: the code is wrong or old, so the message goes under the field. */
const CODE_FIELD_CODES = new Set(['otp_invalid', 'otp_expired']);

/**
 * The person's delivery channels: the list, binding a new one (a code is sent to it) and confirming it with that
 * code (docs/guidelines/forms-ux-standard.md). Both dialogs are Signal Forms: required fields explain themselves
 * under the field on blur and on submit, a refusal the server ties to a field goes under it, any other refusal is an
 * alert in the dialog, and a typed dialog asks before it closes. Unbinding is confirmed by the page.
 */
@Component({
  selector: 'app-profile-channels-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
    SMTControlComponent,
    SMTAlertComponent,
    FormField,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    TranslatePipe,
    SMTButtonComponent,
    SMTBadgeComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiLocalTableComponent,
    DatePipe,
  ],
  templateUrl: './profile-channels-card.component.html',
  styleUrl: './profile-channels-card.component.css',
})
export class ProfileChannelsCardComponent {
  private readonly i18n = inject(I18nService);
  private readonly profile = inject(ProfileApi);
  private readonly toast = inject(ToastService);
  private readonly askDiscard = discardChangesQuestion();
  private readonly injector = inject(Injector);

  readonly isLoadingChannels = input(false);
  readonly canManageChannels = input(true);

  readonly channels = input<UserChannel[]>([]);

  /** A channel was bound or confirmed: the page reads the list again. */
  readonly changed = output<void>();
  /** Asks the page to unbind a channel; the page confirms it first. */
  readonly unbindChannel = output<UserChannel>();

  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('channelTypeCell');
  private readonly addressCell = viewChild.required<TemplateRef<unknown>>('channelAddressCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('channelCreatedCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('channelStatusCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('channelActionCell');

  readonly bindModel = signal({ channel: 'email', address: '' });
  readonly confirmModel = signal({ code: '' });

  readonly isBindModalOpen = signal(false);
  readonly isConfirmModalOpen = signal(false);
  readonly isBindingChannel = signal(false);
  readonly isConfirmingChannel = signal(false);
  /** The server's word on the address or the code, shown under the field. */
  readonly bindFieldError = signal('');
  readonly codeFieldError = signal('');
  /** A refusal of no field, shown as an alert in the dialog. */
  readonly bindError = signal('');
  readonly confirmError = signal('');

  readonly activeVerifyToken = signal('');
  readonly activeVerifyAddress = signal('');

  readonly rows = computed<UserChannel[]>(() => this.channels() ?? []);

  readonly config = computed<TableConfig<UserChannel>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, c) => c.id,
      ariaLabel: this.i18n.translate('iam.profile.channels.title'),
      layout: 'fit',
      columns: {
        type: { header: header('iam.profile.channels.channel_type'), content: cell(this.typeCell), width: '160px' },
        address: { header: header('iam.profile.channels.address_or_account'), content: cell(this.addressCell) },
        created: { header: header('iam.common.created_masculine'), content: cell(this.createdCell), width: '150px' },
        status: { header: header('common.status'), content: cell(this.statusCell), width: '200px' },
        action: {
          header: header('audit.common.action'),
          content: cell(this.actionCell),
          width: '260px',
          align: 'right',
        },
      },
      columnsOrder: ['type', 'address', 'created', 'status', 'action'],
    };
  });

  readonly channelTypeOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return [
      { id: 'email', label: this.i18n.translate('iam.profile.channels.type_email') },
      { id: 'telegram', label: this.i18n.translate('iam.profile.channels.type_telegram') },
      { id: 'sms', label: this.i18n.translate('iam.profile.channels.type_sms') },
    ];
  });

  readonly bindForm = form(this.bindModel, (path) => {
    required(path.channel);
    required(path.address, { message: () => this.i18n.translate('iam.profile.channels.channel_address_required') });
    validate(path.address, ({ value }) =>
      !value() || value().trim()
        ? null
        : { kind: 'required', message: this.i18n.translate('iam.profile.channels.channel_address_required') },
    );
  });

  readonly confirmForm = form(this.confirmModel, (path) => {
    const format = () => this.i18n.translate('iam.profile.channels.code_format');
    required(path.code, { message: format });
    validate(path.code, ({ value }) =>
      !value() || /^[0-9]{6}$/.test(value().trim()) ? null : { kind: 'code_format', message: format() },
    );
    maxLength(path.code, 6);
  });

  /** A person has only a few channels and all are shown, so a header click sorts them all. */
  readonly sortValues = {
    type: (c: UserChannel) => this.channelLabel(c.channel),
    address: (c: UserChannel) => c.address,
    created: (c: UserChannel) => new Date(c.createdAt),
    status: (c: UserChannel) => this.channelStatus(c),
  };

  channelLabel(channel: string): string {
    return this.i18n.translate(this.getChannelLabelKey(channel));
  }

  channelStatus(c: UserChannel): string {
    return this.i18n.translate(
      c.isVerified ? 'iam.profile.channels.verified' : 'iam.profile.channels.pending_confirmation',
    );
  }

  getChannelIcon(channel: string): string {
    const norm = (channel || '').toLowerCase();
    if (norm === 'email') return 'mail';
    if (norm === 'telegram') return 'send';
    if (norm === 'sms') return 'sms';
    return 'alternate_email';
  }

  getChannelLabelKey(channel: string): string {
    const norm = (channel || '').toLowerCase();
    if (norm === 'email') return 'iam.profile.channels.type_email';
    if (norm === 'telegram') return 'iam.profile.channels.type_telegram';
    if (norm === 'sms') return 'iam.profile.channels.type_sms';
    return channel;
  }

  getChannelPlaceholder(): string {
    const channel = this.bindModel().channel;
    if (channel === 'email') return 'user@example.com';
    if (channel === 'telegram') return '@username / Chat ID';
    return '+998901234567';
  }

  openBindModal(): void {
    this.bindModel.set({ channel: 'email', address: '' });
    this.bindForm().reset();
    this.clearBindErrors();
    this.isBindModalOpen.set(true);
  }

  /** Escape, the backdrop, the close button and Cancel: a typed address is lost only after a question. */
  requestCloseBind(): void {
    if (this.isBindingChannel()) return;
    this.askDiscard(!!this.bindModel().address.trim()).subscribe((discard) => {
      if (discard) this.isBindModalOpen.set(false);
    });
  }

  clearBindErrors(): void {
    this.bindFieldError.set('');
    this.bindError.set('');
  }

  submitBind(): void {
    if (this.isBindingChannel()) return;
    markSMTFormFieldsTouched(this.bindForm);
    this.clearBindErrors();
    if (!this.bindForm().valid()) return;
    const { channel, address } = this.bindModel();
    this.bind(channel, address.trim());
  }

  openConfirmModal(verifyToken: string, address: string): void {
    this.activeVerifyToken.set(verifyToken);
    this.activeVerifyAddress.set(address);
    this.confirmModel.set({ code: '' });
    this.confirmForm().reset();
    this.clearConfirmErrors();
    this.isBindModalOpen.set(false);
    this.isConfirmModalOpen.set(true);
  }

  /** Escape, the backdrop, the close button and Cancel: a typed code is lost only after a question. */
  requestCloseConfirm(): void {
    if (this.isConfirmingChannel()) return;
    this.askDiscard(!!this.confirmModel().code.trim()).subscribe((discard) => {
      if (discard) this.closeConfirmModal();
    });
  }

  closeConfirmModal(): void {
    this.isConfirmModalOpen.set(false);
    this.confirmModel.set({ code: '' });
  }

  clearConfirmErrors(): void {
    this.codeFieldError.set('');
    this.confirmError.set('');
  }

  /** "Confirm with a code" of an unconfirmed channel: a new code is sent to it. */
  requestConfirm(channel: UserChannel): void {
    if (this.isBindingChannel()) return;
    this.bind(channel.channel, channel.address);
  }

  submitConfirm(): void {
    if (this.isConfirmingChannel()) return;
    markSMTFormFieldsTouched(this.confirmForm);
    this.clearConfirmErrors();
    if (!this.confirmForm().valid()) return;

    this.isConfirmingChannel.set(true);
    this.profile.confirmChannel(this.activeVerifyToken(), this.confirmModel().code.trim()).subscribe({
      next: () => {
        this.isConfirmingChannel.set(false);
        this.toast.success(this.i18n.translate('iam.profile.channel_bound'));
        this.closeConfirmModal();
        this.changed.emit();
      },
      error: (err: unknown) => {
        this.isConfirmingChannel.set(false);
        const message = problemText(err) || this.i18n.translate('iam.profile.channel_verify_failed');
        const field = problemFieldErrors(err, { known: ['code'] }).fields['code'];
        if (field || CODE_FIELD_CODES.has(errorCode(err))) {
          this.codeFieldError.set(field ?? message);
          this.focusField('profile-channel-confirm-form');
          return;
        }
        this.confirmError.set(message);
      },
    });
  }

  requestUnbind(channel: UserChannel): void {
    this.unbindChannel.emit(channel);
  }

  private bind(channel: string, address: string): void {
    this.isBindingChannel.set(true);
    this.activeVerifyAddress.set(address);
    this.profile.bindChannel(channel, address).subscribe({
      next: (res) => {
        this.isBindingChannel.set(false);
        this.toast.info(this.i18n.translate('iam.profile.verification_code_sent', { address }));
        this.openConfirmModal(res.verifyToken, address);
        this.changed.emit();
      },
      error: (err: unknown) => {
        this.isBindingChannel.set(false);
        const message = problemText(err) || this.i18n.translate('iam.profile.channel_bind_failed');
        // A row's "confirm with a code" has no dialog open: its refusal is a toast.
        if (!this.isBindModalOpen()) {
          this.toast.error(message);
          return;
        }
        const field = problemFieldErrors(err, { known: ['address'] }).fields['address'];
        if (field) {
          this.bindFieldError.set(field);
          this.focusField('profile-channel-bind-form');
          return;
        }
        this.bindError.set(message);
      },
    });
  }

  private focusField(formId: string): void {
    const form = document.getElementById(formId);
    if (form) focusFirstInvalid(form, this.injector);
  }
}

function errorCode(error: unknown): string {
  const code = error && typeof error === 'object' ? (error as { code?: unknown }).code : null;
  return typeof code === 'string' ? code.toLowerCase() : '';
}
