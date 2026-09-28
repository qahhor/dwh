import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  viewChild,
  input,
  output,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiBadgeComponent } from '@shared/ui/ui-badge.component';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UserChannel } from '../profile.models';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';

@Component({
  selector: 'app-profile-channels-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
    UiBadgeComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiLocalTableComponent,
    DatePipe,
  ],
  templateUrl: './profile-channels-card.component.html',
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
        grid-column: 1 / -1;
      }

      .card {
        background-color: var(--bg-surface);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-lg);
        padding: 18px 22px;
      }

      .section-card {
        min-width: 0;
      }

      .full-width {
        width: 100%;
        box-sizing: border-box;
      }

      .section-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 16px;
        margin-bottom: 14px;
        padding-bottom: 12px;
        border-bottom: 1px solid var(--border-color);
      }

      .section-title-box {
        display: flex;
        align-items: flex-start;
        gap: 10px;
        color: var(--text-main);
      }

      .section-icon {
        font-size: 22px;
        color: var(--primary);
        margin-top: 2px;
      }

      .title-with-desc {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .title-row {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .section-title {
        font-size: 15px;
        font-weight: 600;
        margin: 0;
      }

      .section-subtitle {
        font-size: 13px;
        color: var(--text-muted);
        margin: 0;
      }

      .badge-count {
        background-color: var(--bg-hover);
        color: var(--primary);
        font-size: 11px;
        font-weight: 600;
        padding: 1px 6px;
        border-radius: 10px;
        border: 1px solid var(--border-color);
      }

      .table-wrapper {
        overflow-x: auto;
        border: 1px solid var(--border-color);
        border-radius: var(--radius-md);
        background-color: var(--bg-surface);
      }

      .table-wrapper:focus-visible {
        outline: 2px solid var(--primary);
        outline-offset: 2px;
      }

      .channel-type-cell {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .channel-icon {
        font-size: 18px;
        color: var(--primary);
      }

      .row-actions {
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }

      .tabular-nums {
        font-variant-numeric: tabular-nums;
      }

      .font-mono {
        font-family: var(--font-mono, monospace);
      }

      .font-medium {
        font-weight: 500;
      }

      .text-muted {
        color: var(--text-muted);
      }

      .text-right {
        text-align: right;
      }

      .empty-cell {
        text-align: center;
        color: var(--text-muted);
        padding: 24px 14px;
        font-style: italic;
      }

      /* Modal Form Styles */
      .channel-form {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }

      .form-group {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }

      .form-label {
        font-size: 13px;
        font-weight: 500;
        color: var(--text-main);
      }

      .req {
        color: var(--danger);
      }

      .form-input {
        padding: 8px 12px;
        border: 1px solid var(--border-color);
        border-radius: var(--radius-md);
        background-color: var(--bg-surface);
        color: var(--text-main);
        font-size: 13px;
        outline: none;
        transition: border-color 0.15s;
      }

      .form-input:focus {
        border-color: var(--primary);
      }

      .form-input[aria-invalid='true'] {
        border-color: var(--danger);
      }

      .field-error {
        font-size: 11px;
        color: var(--danger);
      }

      .otp-input {
        font-size: 20px;
        letter-spacing: 6px;
        text-align: center;
      }

      .confirm-info-text {
        font-size: 13px;
        color: var(--text-main);
        margin: 0;
        line-height: 1.5;
      }

      .unbind-confirm-body {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }

      .confirm-prompt {
        font-size: 14px;
        font-weight: 500;
        color: var(--text-main);
        margin: 0;
      }

      .confirm-warning {
        font-size: 12px;
        margin: 0;
      }

      .modal-actions {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
        width: 100%;
      }
    `,
  ],
})
export class ProfileChannelsCardComponent {
  private readonly i18n = inject(I18nService);

  readonly isLoadingChannels = input(false);
  readonly isBindingChannel = input(false);
  readonly isConfirmingChannel = input(false);
  readonly canManageChannels = input(true);

  readonly channels = input<UserChannel[]>([]);

  readonly bindChannel = output<{
    channel: string;
    address: string;
  }>();
  readonly confirmChannel = output<{
    verifyToken: string;
    code: string;
  }>();
  /** Asks the page to unbind a channel; the page confirms it first. */
  readonly unbindChannel = output<UserChannel>();

  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('channelTypeCell');
  private readonly addressCell = viewChild.required<TemplateRef<unknown>>('channelAddressCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('channelCreatedCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('channelStatusCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('channelActionCell');

  readonly rows = computed<UserChannel[]>(() => this.channels() ?? []);

  readonly config = computed<TableConfig<UserChannel>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, c) => c.id,
      ariaLabel: this.i18n.translate('iam.kanaly_svyazi'),
      layout: 'fit',
      columns: {
        type: { header: header('iam.tip_kanala'), content: cell(this.typeCell), width: '160px' },
        address: { header: header('iam.adres_ili_login'), content: cell(this.addressCell) },
        created: { header: header('iam.sozdan'), content: cell(this.createdCell), width: '150px' },
        status: { header: header('common.status'), content: cell(this.statusCell), width: '200px' },
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '260px', align: 'right' },
      },
      columnsOrder: ['type', 'address', 'created', 'status', 'action'],
    };
  });

  readonly channelTypeOptions = computed<SMTSelectOption<string>[]>(() => {
    this.i18n.currentLang();
    return [
      { id: 'email', label: this.i18n.translate('iam.kanal_email') },
      { id: 'telegram', label: this.i18n.translate('iam.kanal_telegram') },
      { id: 'sms', label: this.i18n.translate('iam.kanal_sms') },
    ];
  });

  isBindModalOpen = false;
  isConfirmModalOpen = false;
  isBindSubmitted = false;
  isConfirmSubmitted = false;

  selectedChannelType = 'email';
  newAddress = '';

  activeVerifyToken = '';
  activeVerifyAddress = '';
  verificationCode = '';

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
    return this.i18n.translate(c.isVerified ? 'iam.kanal_podtverzhden' : 'iam.ozhidaet_podtverzhdeniya');
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
    if (norm === 'email') return 'iam.kanal_email';
    if (norm === 'telegram') return 'iam.kanal_telegram';
    if (norm === 'sms') return 'iam.kanal_sms';
    return channel;
  }

  getChannelPlaceholder(): string {
    if (this.selectedChannelType === 'email') return 'user@example.com';
    if (this.selectedChannelType === 'telegram') return '@username / Chat ID';
    return '+998901234567';
  }

  openBindModal(): void {
    this.selectedChannelType = 'email';
    this.newAddress = '';
    this.isBindSubmitted = false;
    this.isBindModalOpen = true;
  }

  closeBindModal(): void {
    this.isBindModalOpen = false;
    this.isBindSubmitted = false;
  }

  submitBind(): void {
    this.isBindSubmitted = true;
    if (!this.newAddress.trim()) return;

    this.activeVerifyAddress = this.newAddress.trim();
    this.bindChannel.emit({
      channel: this.selectedChannelType,
      address: this.newAddress.trim(),
    });
  }

  openConfirmModal(verifyToken: string, address: string): void {
    this.activeVerifyToken = verifyToken;
    this.activeVerifyAddress = address;
    this.verificationCode = '';
    this.isConfirmSubmitted = false;
    this.isBindModalOpen = false;
    this.isConfirmModalOpen = true;
  }

  closeConfirmModal(): void {
    this.isConfirmModalOpen = false;
    this.verificationCode = '';
    this.isConfirmSubmitted = false;
  }

  requestConfirm(channel: UserChannel): void {
    this.selectedChannelType = channel.channel;
    this.newAddress = channel.address;
    this.activeVerifyAddress = channel.address;
    this.bindChannel.emit({
      channel: channel.channel,
      address: channel.address,
    });
  }

  submitConfirm(): void {
    this.isConfirmSubmitted = true;
    const cleanCode = this.verificationCode.trim();
    if (cleanCode.length !== 6) return;

    this.confirmChannel.emit({
      verifyToken: this.activeVerifyToken,
      code: cleanCode,
    });
  }

  requestUnbind(channel: UserChannel): void {
    this.unbindChannel.emit(channel);
  }
}
