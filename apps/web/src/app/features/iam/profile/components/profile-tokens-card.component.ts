import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../../shared/ui/ui-modal.component';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { ApiToken, TokenExpirationOption } from '../profile.models';

@Component({
  selector: 'app-profile-tokens-card',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent,
    UiModalComponent
  ],
  template: `
    <div class="card section-card full-width">
      <div class="section-header">
        <div class="section-title-box">
          <span class="material-symbols-outlined section-icon" aria-hidden="true">key</span>
          <h4 class="section-title">{{ 'iam.api_tokeny_dostupa_bearer_tokens' | t }}</h4>
          <span class="badge-count">{{ tokens.length }}</span>
        </div>
        <ui-button variant="primary" size="sm" icon="add" (onClick)="openCreateTokenModal.emit()">
          {{ 'iam.vypustit_token' | t }}
        </ui-button>
      </div>

      <div class="table-wrapper" role="region" [attr.aria-label]="'iam.tablica_api_tokenov' | t" tabindex="0">
        <table class="data-table" [attr.aria-label]="'nav.tokens' | t">
          <thead>
            <tr>
              <th>{{ 'iam.nazvanie_tokena' | t }}</th>
              <th>{{ 'iam.prefiks_tokena' | t }}</th>
              <th>{{ 'iam.sozdan' | t }}</th>
              <th>{{ 'iam.srok_deystviya' | t }}</th>
              <th class="text-right">{{ 'audit.deystvie' | t }}</th>
            </tr>
          </thead>
          <tbody>
            <!-- Skeleton rows when loading -->
            <ng-container *ngIf="isLoadingTokens">
              <tr class="skeleton-row" *ngFor="let item of [1, 2]">
                <td><div class="skeleton-pill w-36"></div></td>
                <td><div class="skeleton-pill w-24"></div></td>
                <td><div class="skeleton-pill w-28"></div></td>
                <td><div class="skeleton-pill w-32"></div></td>
                <td class="text-right"><div class="skeleton-pill w-20 ml-auto"></div></td>
              </tr>
            </ng-container>

            <!-- Real token rows -->
            <ng-container *ngIf="!isLoadingTokens">
              <tr *ngFor="let t of tokens">
                <td class="font-medium">{{ t.name }}</td>
                <td class="tabular-nums font-mono token-prefix-cell">{{ t.tokenPrefix }}...</td>
                <td class="tabular-nums text-muted">{{ t.createdAt | date:'dd.MM.yyyy' }}</td>
                <td class="tabular-nums">{{ t.expiresAt ? (t.expiresAt | date:'dd.MM.yyyy') : ('common.never_expires' | t) }}</td>
                <td class="text-right">
                  <ui-button
                    variant="danger"
                    size="sm"
                    icon="delete"
                    [ariaLabel]="'iam.revoke_api_token_named' | t:{name: t.name}"
                    (onClick)="requestRevoke.emit(t)"
                  >
                    {{ 'iam.otozvat' | t }}
                  </ui-button>
                </td>
              </tr>
              <tr *ngIf="tokens.length === 0">
                <td colspan="5" class="empty-cell">{{ 'iam.net_sozdannyh_api_tokenov' | t }}</td>
              </tr>
            </ng-container>
          </tbody>
        </table>
      </div>
    </div>

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
          <input
            id="profile-token-name"
            name="profileTokenName"
            type="text"
            class="form-input"
            required
            [attr.aria-invalid]="isTokenSubmitted && !newTokenName.trim()"
            [attr.aria-describedby]="isTokenSubmitted && !newTokenName.trim() ? 'profile-token-name-error' : null"
            [ngModel]="newTokenName"
            (ngModelChange)="nameChange.emit($event)"
            [placeholder]="'iam.naprimer_ci_cd_deployer_kafka_sync' | t"
          />
          <span id="profile-token-name-error" class="field-error" *ngIf="isTokenSubmitted && !newTokenName.trim()">
            {{ 'iam.vvedite_nazvanie_api_tokena' | t }}
          </span>
        </div>

        <div class="form-group mt-3">
          <label class="form-label">{{ 'iam.srok_deystviya_tokena' | t }}</label>
          <div class="expiration-options">
            <label
              *ngFor="let opt of tokenExpirationOptions"
              class="expiration-pill"
              [class.selected]="selectedTokenExpiration === opt.value"
            >
              <input
                type="radio"
                name="tokenExpiration"
                [value]="opt.value"
                [ngModel]="selectedTokenExpiration"
                (ngModelChange)="expirationChange.emit($event)"
                class="sr-only"
              />
              <span>{{ opt.labelKey | t }}</span>
            </label>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="closeCreateTokenModal.emit()">
          {{ 'common.cancel' | t }}
        </ui-button>
        <ui-button variant="primary" size="md" [loading]="isCreatingToken" (onClick)="createTokenSubmit.emit()">
          {{ 'iam.sgenerirovat' | t }}
        </ui-button>
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
          <ui-button
            [variant]="copiedSecret ? 'primary' : 'secondary'"
            size="sm"
            [icon]="copiedSecret ? 'check' : 'content_copy'"
            (onClick)="copySecret.emit()"
          >
            {{ (copiedSecret ? 'iam.skopirovano' : 'iam.skopirovat') | t }}
          </ui-button>
        </div>
        <ui-button variant="primary" size="md" class="mt-4" (onClick)="closeSecretModal.emit()">
          {{ 'iam.ya_sohranil_token' | t }}
        </ui-button>
      </div>
    </ui-modal>

    <!-- Token Revocation Confirmation -->
    <ui-modal
      [isOpen]="tokenToRevoke !== null"
      [title]="'iam.otzyv_api_tokena' | t"
      size="sm"
      (close)="cancelRevoke.emit()"
    >
      <div body class="confirmation-body" *ngIf="tokenToRevoke as token">
        <p>{{ 'iam.otozvat_api_token' | t }} <strong>{{ token.name }}</strong>?</p>
        <span class="confirmation-hint">{{ 'iam.integracii_s_etim_tokenom_nemedlenno_poteryayut_' | t }}</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="cancelRevoke.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" [loading]="isRevokingToken" (onClick)="confirmRevoke.emit()">
          {{ 'iam.otozvat' | t }}
        </ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
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
      align-items: center;
      justify-content: space-between;
      margin-bottom: 14px;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--border-color);
    }

    .section-title-box {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--text-main);
    }

    .section-icon {
      font-size: 20px;
      color: var(--primary);
    }

    .section-title {
      font-size: 15px;
      font-weight: 600;
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
      border-radius: var(--radius-sm);
    }
    .table-wrapper:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: -2px;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      text-align: left;
    }

    .data-table th {
      background-color: var(--bg-hover);
      color: var(--text-muted);
      font-weight: 600;
      font-size: 12px;
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      white-space: nowrap;
    }

    .data-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }

    .data-table tr:last-child td {
      border-bottom: none;
    }

    .tabular-nums {
      font-variant-numeric: tabular-nums;
    }

    .font-mono {
      font-family: monospace;
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
      padding: 24px !important;
    }

    .token-prefix-cell {
      letter-spacing: 0.5px;
    }

    .skeleton-row td {
      padding: 12px 14px;
    }

    .skeleton-pill {
      height: 14px;
      background-color: var(--bg-hover);
      border-radius: 4px;
      animation: pulse 1.5s infinite;
    }

    .w-20 { width: 80px; }
    .w-24 { width: 96px; }
    .w-28 { width: 112px; }
    .w-32 { width: 128px; }
    .w-36 { width: 144px; }
    .ml-auto { margin-left: auto; }

    @keyframes pulse {
      0%, 100% { opacity: 0.6; }
      50% { opacity: 0.3; }
    }

    /* Form within modal */
    .token-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req {
      color: var(--danger);
    }

    .field-error {
      font-size: 11px;
      color: var(--danger);
    }

    .form-input {
      height: 36px;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      width: 100%;
      box-sizing: border-box;
      transition: border-color 0.15s ease, box-shadow 0.15s ease;
    }
    .form-input:focus {
      border-color: var(--primary);
      box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.15);
    }

    .mt-3 { margin-top: 12px; }
    .mt-4 { margin-top: 16px; }

    .expiration-options {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }

    .expiration-pill {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 6px 12px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      user-select: none;
      transition: all 0.15s ease;
    }
    .expiration-pill:hover {
      border-color: var(--primary);
      color: var(--text-main);
    }
    .expiration-pill.selected {
      background-color: rgba(99, 102, 241, 0.1);
      border-color: var(--primary);
      color: var(--primary);
      font-weight: 600;
    }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      border: 0;
    }

    .secret-reveal-body {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .warning-box {
      background-color: var(--warning-bg, rgba(245, 158, 11, 0.1));
      color: var(--warning, #f59e0b);
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      display: flex;
      gap: 8px;
      font-size: 12px;
      align-items: center;
    }
    .warning-box p {
      margin: 0;
    }

    .token-secret-box {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background-color: var(--bg-hover);
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      font-family: monospace;
      font-size: 13px;
      word-break: break-all;
      gap: 12px;
    }

    .confirmation-body {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .confirmation-body p {
      margin: 0;
      font-size: 14px;
    }
    .confirmation-hint {
      color: var(--text-muted);
      font-size: 12px;
    }
  `]
})
export class ProfileTokensCardComponent {
  @Input() tokens: ApiToken[] = [];
  @Input() isLoadingTokens = false;
  @Input() isCreatingToken = false;
  @Input() isRevokingToken = false;
  @Input() isCreateTokenModalOpen = false;
  @Input() isTokenSecretModalOpen = false;
  @Input() isTokenSubmitted = false;
  @Input() newTokenName = '';
  @Input() selectedTokenExpiration = '90';
  @Input() createdTokenSecret = '';
  @Input() copiedSecret = false;
  @Input() tokenToRevoke: ApiToken | null = null;
  @Input() tokenExpirationOptions: TokenExpirationOption[] = [];

  @Output() openCreateTokenModal = new EventEmitter<void>();
  @Output() closeCreateTokenModal = new EventEmitter<void>();
  @Output() createTokenSubmit = new EventEmitter<void>();
  @Output() nameChange = new EventEmitter<string>();
  @Output() expirationChange = new EventEmitter<string>();
  @Output() closeSecretModal = new EventEmitter<void>();
  @Output() copySecret = new EventEmitter<void>();
  @Output() requestRevoke = new EventEmitter<ApiToken>();
  @Output() confirmRevoke = new EventEmitter<void>();
  @Output() cancelRevoke = new EventEmitter<void>();
}
