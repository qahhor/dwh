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
import { SMTDialogComponent, SMTDialogContentDirective, SMTModalService } from '@shared/ui-kit/components/modal';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { confirmDiscard, formChanged } from '@shared/ui/confirm-discard';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { problemText } from '@shared/ui/problem-text';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { ProfileApi } from '../profile.api';
import { ApiToken, TokenExpirationOption, tokenExpiresAt } from '../profile.models';

/** The longest token name the list shows whole. */
const TOKEN_NAME_MAX = 100;

const TOKEN_EXPIRATIONS: readonly TokenExpirationOption[] = [
  { value: '30', labelKey: 'iam.profile.expiry_30_days' },
  { value: '90', labelKey: 'iam.profile.expiry_90_days' },
  { value: '365', labelKey: 'iam.profile.expiry_1_year' },
  { value: 'never', labelKey: 'iam.profile.no_expiry' },
];

const FRESH = { name: '', expiration: '90' };

/**
 * The person's API tokens: the list, issuing one and showing its secret once (docs/guidelines/forms-ux-standard.md).
 * The issue dialog is a Signal Form: the name is required and explained under the field on blur and on submit, a
 * refusal the server ties to the name goes under it, any other refusal is an alert in the dialog, and a typed dialog
 * asks before it closes. Revoking is confirmed by the page.
 */
@Component({
  selector: 'app-profile-tokens-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTInputComponent,
    SMTAlertComponent,
    UiLocalTableComponent,
    TranslatePipe,
    FormField,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTControlComponent,
    SMTRadioGroupComponent,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    DatePipe,
  ],
  templateUrl: './profile-tokens-card.component.html',
  styleUrl: './profile-tokens-card.component.css',
})
export class ProfileTokensCardComponent {
  private readonly i18n = inject(I18nService);
  private readonly profile = inject(ProfileApi);
  private readonly toast = inject(ToastService);
  private readonly modal = inject(SMTModalService);
  private readonly injector = inject(Injector);

  readonly isLoadingTokens = input(false);
  readonly tokens = input<ApiToken[]>([]);

  /** A token was issued: the page reads the list again. */
  readonly changed = output<void>();
  readonly requestRevoke = output<ApiToken>();

  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('tokenNameCell');
  private readonly prefixCell = viewChild.required<TemplateRef<unknown>>('tokenPrefixCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('tokenCreatedCell');
  private readonly expiresCell = viewChild.required<TemplateRef<unknown>>('tokenExpiresCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('tokenActionCell');

  readonly isCreateTokenModalOpen = signal(false);
  readonly isTokenSecretModalOpen = signal(false);
  readonly isCreatingToken = signal(false);
  readonly createdTokenSecret = signal('');
  readonly copiedSecret = signal(false);
  /** The server's word on the name, shown under the field. */
  readonly nameError = signal('');
  /** A refusal of no field, shown as an alert in the dialog. */
  readonly createError = signal('');

  readonly model = signal({ ...FRESH });

  readonly tokenForm = form(this.model, (path) => {
    const message = () => this.i18n.translate('iam.profile.token_name_placeholder');
    required(path.name, { message });
    validate(path.name, ({ value }) => (!value() || value().trim() ? null : { kind: 'required', message: message() }));
    maxLength(path.name, TOKEN_NAME_MAX);
  });

  readonly rows = computed<ApiToken[]>(() => this.tokens() ?? []);

  readonly config = computed<TableConfig<ApiToken>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, t) => t.id,
      ariaLabel: this.i18n.translate('nav.tokens'),
      layout: 'fit',
      columns: {
        name: { header: header('iam.profile.tokens.token_name'), content: cell(this.nameCell) },
        prefix: { header: header('iam.profile.tokens.token_prefix'), content: cell(this.prefixCell) },
        created: { header: header('iam.common.created_masculine'), content: cell(this.createdCell), width: '130px' },
        expires: {
          header: header('iam.profile.tokens.validity_period'),
          content: cell(this.expiresCell),
          width: '150px',
        },
        action: {
          header: header('audit.common.action'),
          content: cell(this.actionCell),
          width: '140px',
          align: 'right',
        },
      },
      columnsOrder: ['name', 'prefix', 'created', 'expires', 'action'],
    };
  });

  /** The lifetimes as radio items, translated again when the language changes. */
  readonly expirationItems = computed<SMTRadioOption<string>[]>(() => {
    this.i18n.currentLang();
    return TOKEN_EXPIRATIONS.map((option) => ({ value: option.value, label: this.i18n.translate(option.labelKey) }));
  });

  readonly sortValues = {
    name: (t: ApiToken) => t.name,
    prefix: (t: ApiToken) => t.tokenPrefix,
    created: (t: ApiToken) => new Date(t.createdAt),
    expires: (t: ApiToken) => (t.expiresAt ? new Date(t.expiresAt) : null),
  };

  openCreateTokenModal(): void {
    this.model.set({ ...FRESH });
    this.tokenForm().reset();
    this.clearErrors();
    this.isCreateTokenModalOpen.set(true);
  }

  /** Escape, the backdrop, the close button and Cancel: a typed token is lost only after a question. */
  requestCloseCreate(): void {
    if (this.isCreatingToken()) return;
    confirmDiscard(this.modal, this.i18n, formChanged(FRESH, this.model())).subscribe((discard) => {
      if (discard) this.isCreateTokenModalOpen.set(false);
    });
  }

  onExpirationChosen(value: string | null): void {
    if (value !== null) this.model.update((current) => ({ ...current, expiration: value }));
  }

  clearErrors(): void {
    this.nameError.set('');
    this.createError.set('');
  }

  createTokenSubmit(): void {
    if (this.isCreatingToken()) return;
    markSMTFormFieldsTouched(this.tokenForm);
    this.clearErrors();
    if (!this.tokenForm().valid()) return;

    const { name, expiration } = this.model();
    this.isCreatingToken.set(true);
    this.profile.createToken(name.trim(), tokenExpiresAt(expiration, new Date())).subscribe({
      next: (res) => {
        this.isCreatingToken.set(false);
        this.isCreateTokenModalOpen.set(false);
        this.createdTokenSecret.set(res.rawSecretToken);
        this.copiedSecret.set(false);
        this.isTokenSecretModalOpen.set(true);
        this.changed.emit();
      },
      error: (err: unknown) => {
        this.isCreatingToken.set(false);
        const field = problemFieldErrors(err, { known: ['name'] }).fields['name'];
        if (field) {
          this.nameError.set(field);
          const formElement = document.getElementById('profile-token-form');
          if (formElement) focusFirstInvalid(formElement, this.injector);
          return;
        }
        this.createError.set(problemText(err) || this.i18n.translate('iam.profile.token_create_failed'));
      },
    });
  }

  closeSecretModal(): void {
    this.isTokenSecretModalOpen.set(false);
    this.createdTokenSecret.set('');
  }

  copySecret(): void {
    if (!this.createdTokenSecret()) return;
    void navigator.clipboard.writeText(this.createdTokenSecret());
    this.copiedSecret.set(true);
    this.toast.success(this.i18n.translate('iam.profile.token_copied'));
    setTimeout(() => this.copiedSecret.set(false), 2000);
  }
}
