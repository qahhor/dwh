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
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { ApiToken, TokenExpirationOption } from '../profile.models';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-profile-tokens-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    UiLocalTableComponent,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTControlComponent,
    SMTRadioGroupComponent,
    DatePipe,
  ],
  templateUrl: './profile-tokens-card.component.html',
  styleUrl: './profile-tokens-card.component.css',
})
export class ProfileTokensCardComponent {
  private readonly i18n = inject(I18nService);

  readonly isLoadingTokens = input(false);
  readonly isCreatingToken = input(false);
  readonly isCreateTokenModalOpen = input(false);
  readonly isTokenSecretModalOpen = input(false);
  readonly isTokenSubmitted = input(false);
  readonly newTokenName = input('');
  readonly selectedTokenExpiration = input('90');
  readonly createdTokenSecret = input('');
  readonly copiedSecret = input(false);

  readonly tokens = input<ApiToken[]>([]);
  readonly tokenExpirationOptions = input<TokenExpirationOption[]>([]);

  readonly openCreateTokenModal = output<void>();
  readonly closeCreateTokenModal = output<void>();
  readonly createTokenSubmit = output<void>();
  readonly nameChange = output<string>();
  readonly expirationChange = output<string>();
  readonly closeSecretModal = output<void>();
  readonly copySecret = output<void>();
  readonly requestRevoke = output<ApiToken>();

  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('tokenNameCell');
  private readonly prefixCell = viewChild.required<TemplateRef<unknown>>('tokenPrefixCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('tokenCreatedCell');
  private readonly expiresCell = viewChild.required<TemplateRef<unknown>>('tokenExpiresCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('tokenActionCell');

  readonly rows = computed<ApiToken[]>(() => this.tokens() ?? []);

  readonly config = computed<TableConfig<ApiToken>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, t) => t.id,
      ariaLabel: this.i18n.translate('nav.tokens'),
      layout: 'fit',
      columns: {
        name: { header: header('iam.nazvanie_tokena'), content: cell(this.nameCell) },
        prefix: { header: header('iam.prefiks_tokena'), content: cell(this.prefixCell) },
        created: { header: header('iam.sozdan'), content: cell(this.createdCell), width: '130px' },
        expires: { header: header('iam.srok_deystviya'), content: cell(this.expiresCell), width: '150px' },
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '140px', align: 'right' },
      },
      columnsOrder: ['name', 'prefix', 'created', 'expires', 'action'],
    };
  });
  /** The lifetimes as radio items, translated again when the language changes. */
  readonly expirationItems = computed<SMTRadioOption<string>[]>(() =>
    this.expirationSource().map((option) => ({ value: option.value, label: this.i18n.translate(option.labelKey) })),
  );
  private readonly expirationSource = computed<TokenExpirationOption[]>(() => this.tokenExpirationOptions());

  readonly sortValues = {
    name: (t: ApiToken) => t.name,
    prefix: (t: ApiToken) => t.tokenPrefix,
    created: (t: ApiToken) => new Date(t.createdAt),
    expires: (t: ApiToken) => (t.expiresAt ? new Date(t.expiresAt) : null),
  };

  onExpirationChosen(value: string | null): void {
    if (value !== null) this.expirationChange.emit(value);
  }
}
