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
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UserChannel } from '../profile.models';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';

@Component({
  selector: 'app-profile-channels-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTSelectComponent,
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

  /** What the dialogs edit; signals, since the page resets them from its request callbacks. */
  readonly selectedChannelType = signal('email');
  readonly newAddress = signal('');
  readonly verificationCode = signal('');

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

  activeVerifyToken = '';
  activeVerifyAddress = '';

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
    if (this.selectedChannelType() === 'email') return 'user@example.com';
    if (this.selectedChannelType() === 'telegram') return '@username / Chat ID';
    return '+998901234567';
  }

  openBindModal(): void {
    this.selectedChannelType.set('email');
    this.newAddress.set('');
    this.isBindSubmitted = false;
    this.isBindModalOpen = true;
  }

  closeBindModal(): void {
    this.isBindModalOpen = false;
    this.isBindSubmitted = false;
  }

  submitBind(): void {
    this.isBindSubmitted = true;
    if (!this.newAddress().trim()) return;

    this.activeVerifyAddress = this.newAddress().trim();
    this.bindChannel.emit({
      channel: this.selectedChannelType(),
      address: this.newAddress().trim(),
    });
  }

  openConfirmModal(verifyToken: string, address: string): void {
    this.activeVerifyToken = verifyToken;
    this.activeVerifyAddress = address;
    this.verificationCode.set('');
    this.isConfirmSubmitted = false;
    this.isBindModalOpen = false;
    this.isConfirmModalOpen = true;
  }

  closeConfirmModal(): void {
    this.isConfirmModalOpen = false;
    this.verificationCode.set('');
    this.isConfirmSubmitted = false;
  }

  requestConfirm(channel: UserChannel): void {
    this.selectedChannelType.set(channel.channel);
    this.newAddress.set(channel.address);
    this.activeVerifyAddress = channel.address;
    this.bindChannel.emit({
      channel: channel.channel,
      address: channel.address,
    });
  }

  submitConfirm(): void {
    this.isConfirmSubmitted = true;
    const cleanCode = this.verificationCode().trim();
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
