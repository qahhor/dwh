import { Component, EventEmitter, Input, Output, Signal, TemplateRef, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiLocalTableComponent } from '../../../../shared/ui/ui-local-table.component';
import { TableConfig } from '../../../../shared/ui-kit/components/table/table.types';
import { ApiToken, TokenExpirationOption } from '../profile.models';
import { SMTControlComponent } from '../../../../shared/ui-kit/components/forms/control';
import { SMTRadioGroupComponent, SMTRadioOption } from '../../../../shared/ui-kit/components/forms/radio-group';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-profile-tokens-card',
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, 
    UiLocalTableComponent,
    CommonModule,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
    UiModalComponent,
    SMTControlComponent,
    SMTRadioGroupComponent
  ],
  template: `
    <div class="card section-card full-width">
      <div class="section-header">
        <div class="section-title-box">
          <span class="material-symbols-outlined section-icon" aria-hidden="true">key</span>
          <h4 class="section-title">{{ 'iam.api_tokeny_dostupa_bearer_tokens' | t }}</h4>
          <span class="badge-count">{{ tokens.length }}</span>
        </div>
        <button smt-button type="button" smtVariant="primary" smtSize="sm" smtIcon="add" (click)="openCreateTokenModal.emit()">
          {{ 'iam.vypustit_token' | t }}
        </button>
      </div>

      <div class="table-wrapper" role="region" [attr.aria-label]="'iam.tablica_api_tokenov' | t" tabindex="0">
        <div class="data-table">
          <ui-local-table [rows]="rows()" [config]="config()" [sortValues]="sortValues" [loading]="isLoadingTokens" [emptyTemplate]="emptyTokens" />
        </div>
      </div>
    </div>

    <ng-template #tokenNameCell let-t><span class="font-medium">{{ t.name }}</span></ng-template>
    <ng-template #tokenPrefixCell let-t><span class="tabular-nums font-mono token-prefix-cell">{{ t.tokenPrefix }}...</span></ng-template>
    <ng-template #tokenCreatedCell let-t><span class="tabular-nums text-muted">{{ t.createdAt | date:'dd.MM.yyyy' }}</span></ng-template>
    <ng-template #tokenExpiresCell let-t><span class="tabular-nums">{{ t.expiresAt ? (t.expiresAt | date:'dd.MM.yyyy') : ('common.never_expires' | t) }}</span></ng-template>
    <ng-template #tokenActionCell let-t>
      <div class="text-right">
        <button smt-button type="button" smtVariant="danger" smtSize="sm" smtIcon="delete" [attr.aria-label]="'iam.revoke_api_token_named' | t:{name: t.name}" (click)="requestRevoke.emit(t)">
          {{ 'iam.otozvat' | t }}
        </button>
      </div>
    </ng-template>
    <ng-template #emptyTokens><p class="empty-cell">{{ 'iam.net_sozdannyh_api_tokenov' | t }}</p></ng-template>

    <!-- Create Token Modal -->
    <ui-modal
      [isOpen]="isCreateTokenModalOpen"
      [title]="'iam.vypusk_novogo_api_tokena' | t"
      size="sm"
      (close)="closeCreateTokenModal.emit()"
    >
      <div body class="token-form">
        <div class="form-group">
          <label class="form-label" for="profile-token-name">{{ 'iam.nazvanie_tokena' | t }} <span class="req">*</span></label>
          <smt-input
            smtFieldId="profile-token-name"
            name="profileTokenName"
            required
            [smtInvalid]="isTokenSubmitted && !newTokenName.trim()"
            [smtDescribedBy]="isTokenSubmitted && !newTokenName.trim() ? 'profile-token-name-error' : null"
            [ngModel]="newTokenName"
            (ngModelChange)="nameChange.emit($event)"
            [placeholder]="'iam.naprimer_ci_cd_deployer_kafka_sync' | t" />
          <span id="profile-token-name-error" class="field-error" *ngIf="isTokenSubmitted && !newTokenName.trim()">
            {{ 'iam.vvedite_nazvanie_api_tokena' | t }}
          </span>
        </div>

        <smt-control class="mt-3" [smtLabel]="'iam.srok_deystviya_tokena' | t">
          <smt-radio-group
            smtAppearance="cards"
            smtOrientation="horizontal"
            [options]="expirationItems()"
            [value]="selectedTokenExpiration"
            (valueChange)="onExpirationChosen($event)" />
        </smt-control>
      </div>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="closeCreateTokenModal.emit()">
          {{ 'common.cancel' | t }}
        </button>
        <button smt-button type="button" smtVariant="primary" smtSize="md" [smtLoading]="isCreatingToken" (click)="createTokenSubmit.emit()">
          {{ 'iam.sgenerirovat' | t }}
        </button>
      </div>
    </ui-modal>

    <!-- Token Secret Reveal Modal -->
    <ui-modal
      [isOpen]="isTokenSecretModalOpen"
      [title]="'iam.api_token_uspeshno_sozdan' | t"
      size="md"
      [hasFooter]="false"
      (close)="closeSecretModal.emit()"
    >
      <div body class="secret-reveal-body">
        <div class="warning-box">
          <span class="material-symbols-outlined" aria-hidden="true">warning</span>
          <p>{{ 'iam.skopiruyte_i_sohranite_token_seychas_v_celyah_be' | t }}</p>
        </div>
        <div class="token-secret-box">
          <code>{{ createdTokenSecret }}</code>
          <button smt-button type="button"
            [smtVariant]="copiedSecret ? 'primary' : 'secondary'"
            smtSize="sm"
            [smtIcon]="copiedSecret ? 'check' : 'content_copy'"
            (click)="copySecret.emit()"
          >
            {{ (copiedSecret ? 'iam.skopirovano' : 'iam.skopirovat') | t }}
          </button>
        </div>
        <button smt-button type="button" smtVariant="primary" smtSize="md" class="mt-4" (click)="closeSecretModal.emit()">
          {{ 'iam.ya_sohranil_token' | t }}
        </button>
      </div>
    </ui-modal>
  `,
  styleUrl: './profile-tokens-card.component.css'
})
export class ProfileTokensCardComponent {
  private readonly i18n = inject(I18nService);

  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('tokenNameCell');
  private readonly prefixCell = viewChild.required<TemplateRef<unknown>>('tokenPrefixCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('tokenCreatedCell');
  private readonly expiresCell = viewChild.required<TemplateRef<unknown>>('tokenExpiresCell');
  private readonly actionCell = viewChild.required<TemplateRef<unknown>>('tokenActionCell');

  readonly rows = signal<ApiToken[]>([]);
  private readonly expirationSource = signal<TokenExpirationOption[]>([]);

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
        action: { header: header('audit.deystvie'), content: cell(this.actionCell), width: '140px', align: 'right' }
      },
      columnsOrder: ['name', 'prefix', 'created', 'expires', 'action']
    };
  });
  /** The lifetimes as radio items, translated again when the language changes. */
  readonly expirationItems = computed<SMTRadioOption<string>[]>(() =>
    this.expirationSource().map(option => ({ value: option.value, label: this.i18n.translate(option.labelKey) })));

  readonly sortValues = {
    name: (t: ApiToken) => t.name,
    prefix: (t: ApiToken) => t.tokenPrefix,
    created: (t: ApiToken) => new Date(t.createdAt),
    expires: (t: ApiToken) => (t.expiresAt ? new Date(t.expiresAt) : null)
  };
  @Input() isLoadingTokens = false;
  @Input() isCreatingToken = false;
  @Input() isCreateTokenModalOpen = false;
  @Input() isTokenSecretModalOpen = false;
  @Input() isTokenSubmitted = false;
  @Input() newTokenName = '';
  @Input() selectedTokenExpiration = '90';
  @Input() createdTokenSecret = '';
  @Input() copiedSecret = false;

  @Output() openCreateTokenModal = new EventEmitter<void>();
  @Output() closeCreateTokenModal = new EventEmitter<void>();
  @Output() createTokenSubmit = new EventEmitter<void>();
  @Output() nameChange = new EventEmitter<string>();
  @Output() expirationChange = new EventEmitter<string>();
  @Output() closeSecretModal = new EventEmitter<void>();
  @Output() copySecret = new EventEmitter<void>();
  @Output() requestRevoke = new EventEmitter<ApiToken>();

  @Input() set tokens(tokens: ApiToken[]) {
    this.rows.set(tokens ?? []);
  }
  get tokens(): ApiToken[] {
    return this.rows();
  }
  @Input() set tokenExpirationOptions(options: TokenExpirationOption[]) {
    this.expirationSource.set(options);
  }

  onExpirationChosen(value: string | null): void {
    if (value !== null) this.expirationChange.emit(value);
  }
}
