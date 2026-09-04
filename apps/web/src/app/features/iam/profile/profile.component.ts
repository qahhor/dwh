import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/services/auth.service';
import { ApiService } from '../../../core/services/api.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UserSession, ApiToken, CreatedTokenResponse } from '../../../core/models/auth.models';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    TranslatePipe,CommonModule, FormsModule, UiButtonComponent, UiBadgeComponent, UiModalComponent],
  template: `
    <div class="profile-container">
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'nav.profile' | t }}</h1>
          <span class="count-badge">Security & Settings</span>
        </div>
      </div>

      <!-- User Info Card -->
      <div class="card user-card" *ngIf="authService.currentUser() as user">
        <div class="user-avatar-large">
          {{ user.name ? user.name.charAt(0).toUpperCase() : 'U' }}
        </div>
        <div class="user-details">
          <div class="user-title-row">
            <h3 class="user-fullname">{{ user.name }}</h3>
            <ui-badge [variant]="user.state === 'A' ? 'active' : 'passive'" [dot]="true">
              {{ (user.state === 'A' ? 'common.active_masculine' : 'common.blocked_masculine') | t }}
            </ui-badge>
            <ui-badge *ngIf="user.is2faEnabled" variant="active">
              <span class="material-symbols-outlined badge-icon" aria-hidden="true">verified_user</span> {{ 'iam.2fa_vklyuchena' | t }}
            </ui-badge>
          </div>
          <div class="user-info-grid">
            <div class="info-item">
              <span class="info-label">{{ 'iam.login' | t }}</span>
              <span class="info-value font-mono">&#64;{{ user.login }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">Email:</span>
              <span class="info-value font-mono">{{ user.email }}</span>
            </div>
            <div class="info-item" *ngIf="user.phone">
              <span class="info-label">{{ 'iam.telefon' | t }}</span>
              <span class="info-value font-mono">{{ user.phone }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">{{ 'iam.yazyk_zona' | t }}</span>
              <span class="info-value">{{ user.language || 'ru' }} ({{ user.timezone || 'Asia/Tashkent' }})</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Main Grid Sections -->
      <div class="sections-grid">
        <!-- Change Password Card -->
        <div class="card section-card">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">lock_reset</span>
              <h4 class="section-title">{{ 'iam.smena_parolya' | t }}</h4>
            </div>
          </div>

          <form class="password-form" (submit)="submitChangePassword($event)">
            <div class="form-group">
              <label class="form-label" for="profile-current-password">{{ 'iam.tekuschiy_parol' | t }} <span class="req">*</span></label>
              <input
                id="profile-current-password"
                type="password"
                class="form-input font-mono"
                autocomplete="current-password"
                [(ngModel)]="passwordForm.oldPassword"
                name="oldPassword"
                [attr.aria-invalid]="isPasswordSubmitted && !passwordForm.oldPassword"
                [attr.aria-describedby]="isPasswordSubmitted && !passwordForm.oldPassword ? 'profile-current-password-error' : null"
                [placeholder]="'iam.vvedite_tekuschiy_parol' | t"
                required
              />
              <span id="profile-current-password-error" class="field-error" *ngIf="isPasswordSubmitted && !passwordForm.oldPassword">{{ 'iam.vvedite_tekuschiy_parol' | t }}</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="profile-new-password">{{ 'auth.novyy_parol' | t }} <span class="req">*</span></label>
              <div class="password-input-box">
                <input
                  id="profile-new-password"
                  [type]="showNewPassword() ? 'text' : 'password'"
                  class="form-input font-mono"
                  autocomplete="new-password"
                  minlength="10"
                  [(ngModel)]="passwordForm.newPassword"
                  name="newPassword"
                  [attr.aria-invalid]="isPasswordSubmitted && passwordForm.newPassword.length < 10"
                  [attr.aria-describedby]="isPasswordSubmitted && passwordForm.newPassword.length < 10 ? 'profile-new-password-hint profile-new-password-error' : 'profile-new-password-hint'"
                  [placeholder]="'iam.minimum_10_simvolov' | t"
                  required
                />
                <button type="button" class="pwd-toggle-btn" [attr.aria-label]="(showNewPassword() ? 'iam.hide_new_password' : 'iam.show_new_password') | t" [attr.aria-pressed]="showNewPassword()" (click)="showNewPassword.update(v => !v)">
                  <span class="material-symbols-outlined" aria-hidden="true">{{ showNewPassword() ? 'visibility_off' : 'visibility' }}</span>
                </button>
              </div>
              <span id="profile-new-password-hint" class="field-hint">{{ 'iam.minimum_10_simvolov_ne_iz_chernogo_spiska_i_ne_s' | t }}</span>
              <span id="profile-new-password-error" class="field-error" *ngIf="isPasswordSubmitted && passwordForm.newPassword.length < 10">{{ 'iam.parol_dolzhen_soderzhat_ne_menee_10_simvolov' | t }}</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="profile-confirm-password">{{ 'iam.podtverzhdenie_novogo_parolya' | t }} <span class="req">*</span></label>
              <input
                id="profile-confirm-password"
                type="password"
                class="form-input font-mono"
                autocomplete="new-password"
                [(ngModel)]="passwordForm.confirmPassword"
                name="confirmPassword"
                [attr.aria-invalid]="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword)"
                [attr.aria-describedby]="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword) ? 'profile-confirm-password-error' : null"
                [placeholder]="'auth.povtorite_novyy_parol' | t"
                required
              />
              <span id="profile-confirm-password-error" class="field-error" *ngIf="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword)">
                {{ (!passwordForm.confirmPassword ? 'iam.confirm_new_password' : 'iam.passwords_do_not_match') | t }}
              </span>
            </div>

            <div class="form-actions">
              <ui-button variant="primary" size="md" [loading]="isChangingPassword()" type="submit">
                {{ 'iam.obnovit_parol' | t }}
              </ui-button>
            </div>
          </form>
        </div>

        <!-- Security & 2FA Info Card -->
        <div class="card section-card">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">security</span>
              <h4 class="section-title">{{ 'iam.bezopasnost_i_2fa' | t }}</h4>
            </div>
          </div>

          <div class="security-info-box">
            <div class="twofa-status-banner" [class.enabled]="authService.currentUser()?.is2faEnabled">
              <span class="material-symbols-outlined twofa-big-icon" aria-hidden="true">
                {{ authService.currentUser()?.is2faEnabled ? 'verified_user' : 'gpp_maybe' }}
              </span>
              <div class="twofa-status-text">
                <div class="twofa-status-title">
                  {{ (authService.currentUser()?.is2faEnabled ? 'iam.two_factor_active' : 'iam.two_factor_disabled') | t }}
                </div>
                <div class="twofa-status-desc">
                  {{ authService.currentUser()?.is2faEnabled
                    ? ('iam.two_factor_active_description' | t)
                    : ('iam.two_factor_disabled_description' | t) }}
                </div>
              </div>
            </div>

            <div class="security-tips">
              <div class="tip-item">
                <span class="material-symbols-outlined tip-icon" aria-hidden="true">check_circle</span>
                <span>{{ 'iam.zaschita_ot_podbora_paroley_5_nevernyh_popytok_b' | t }}</span>
              </div>
              <div class="tip-item">
                <span class="material-symbols-outlined tip-icon" aria-hidden="true">check_circle</span>
                <span>{{ 'iam.sessii_avtomaticheski_zakryvayutsya_pri_bezdeyst' | t }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Active Sessions Card -->
        <div class="card section-card full-width">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">devices</span>
              <h4 class="section-title">{{ 'iam.aktivnye_sessii' | t }}</h4>
              <span class="badge-count">{{ sessions().length }}</span>
            </div>
            <div class="sessions-header-actions">
              <ui-button
                *ngIf="sessions().length > 1"
                variant="danger"
                size="sm"
                icon="logout"
                [title]="'iam.zavershit_vse_ostalnye_sessii_krome_tekuschey' | t"
                (onClick)="requestTerminateOtherSessions()"
              >
                {{ 'iam.zavershit_drugie_sessii' | t }}
              </ui-button>
              <ui-button variant="secondary" size="sm" icon="refresh" (onClick)="loadSessions()">
                {{ 'common.refresh' | t }}
              </ui-button>
            </div>
          </div>

          <div class="table-wrapper" role="region" [attr.aria-label]="'iam.tablica_aktivnyh_sessiy' | t" tabindex="0">
            <table class="data-table" [attr.aria-label]="'iam.aktivnye_sessii' | t">
              <thead>
                <tr>
                  <th>{{ 'iam.ip_adres' | t }}</th>
                  <th>{{ 'iam.ustroystvo_brauzer' | t }}</th>
                  <th>{{ 'iam.sozdana' | t }}</th>
                  <th>{{ 'iam.poslednyaya_aktivnost' | t }}</th>
                  <th class="text-right">{{ 'audit.deystvie' | t }}</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let s of sessions()">
                  <td class="tabular-nums font-mono">{{ s.ip }}</td>
                  <td>{{ s.deviceInfo || s.userAgent || ('iam.unknown_device' | t) }}</td>
                  <td class="tabular-nums text-muted">{{ s.createdAt | date:'dd.MM.yyyy HH:mm' }}</td>
                  <td class="tabular-nums font-medium">{{ s.lastSeenAt | date:'dd.MM.yyyy HH:mm:ss' }}</td>
                  <td class="text-right">
                    <ui-button variant="danger" size="sm" [ariaLabel]="'iam.terminate_session_ip' | t:{ip: s.ip}" (onClick)="requestTerminateSession(s)">{{ 'iam.zavershit' | t }}</ui-button>
                  </td>
                </tr>
                <tr *ngIf="sessions().length === 0">
                  <td colspan="5" class="empty-cell">{{ 'iam.net_aktivnyh_sessiy' | t }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- API Tokens Card -->
        <div class="card section-card full-width">
          <div class="section-header">
            <div class="section-title-box">
              <span class="material-symbols-outlined section-icon" aria-hidden="true">key</span>
              <h4 class="section-title">{{ 'iam.api_tokeny_dostupa_bearer_tokens' | t }}</h4>
              <span class="badge-count">{{ tokens().length }}</span>
            </div>
            <ui-button variant="primary" size="sm" icon="add" (onClick)="openCreateTokenModal()">{{ 'iam.vypustit_token' | t }}</ui-button>
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
                <tr *ngFor="let t of tokens()">
                  <td class="font-medium">{{ t.name }}</td>
                  <td class="tabular-nums font-mono token-prefix-cell">{{ t.tokenPrefix }}...</td>
                  <td class="tabular-nums text-muted">{{ t.createdAt | date:'dd.MM.yyyy' }}</td>
                  <td class="tabular-nums">{{ t.expiresAt ? (t.expiresAt | date:'dd.MM.yyyy') : ('common.never_expires' | t) }}</td>
                  <td class="text-right">
                    <ui-button variant="danger" size="sm" icon="delete" [ariaLabel]="'iam.revoke_api_token_named' | t:{name: t.name}" (onClick)="requestRevokeToken(t)">{{ 'iam.otozvat' | t }}</ui-button>
                  </td>
                </tr>
                <tr *ngIf="tokens().length === 0">
                  <td colspan="5" class="empty-cell">{{ 'iam.net_sozdannyh_api_tokenov' | t }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Create Token Modal -->
    <ui-modal
      [isOpen]="isCreateTokenModalOpen()"
      [title]="'iam.vypusk_novogo_api_tokena' | t"
      size="sm"
      (close)="isCreateTokenModalOpen.set(false)"
    >
      <div body class="token-form">
        <div class="form-group">
          <label class="form-label" for="profile-token-name">{{ 'iam.nazvanie_tokena' | t }} <span class="req">*</span></label>
          <input id="profile-token-name" name="profileTokenName" type="text" class="form-input" required
            [attr.aria-invalid]="isTokenSubmitted && !newTokenName.trim()"
            [attr.aria-describedby]="isTokenSubmitted && !newTokenName.trim() ? 'profile-token-name-error' : null"
            [(ngModel)]="newTokenName" [placeholder]="'iam.naprimer_ci_cd_deployer_kafka_sync' | t" />
          <span id="profile-token-name-error" class="field-error" *ngIf="isTokenSubmitted && !newTokenName.trim()">{{ 'iam.vvedite_nazvanie_api_tokena' | t }}</span>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateTokenModalOpen.set(false)">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" (onClick)="createTokenSubmit()">{{ 'iam.sgenerirovat' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- Token Secret Reveal Modal -->
    <ui-modal
      [isOpen]="isTokenSecretModalOpen()"
      [title]="'iam.api_token_uspeshno_sozdan' | t"
      size="md"
      [hasFooter]="false"
      (close)="isTokenSecretModalOpen.set(false)"
    >
      <div body class="secret-reveal-body">
        <div class="warning-box">
          <span class="material-symbols-outlined" aria-hidden="true">warning</span>
          <p>{{ 'iam.skopiruyte_i_sohranite_token_seychas_v_celyah_be' | t }}</p>
        </div>
        <div class="token-secret-box">
          <code>{{ createdTokenSecret }}</code>
          <ui-button variant="secondary" size="sm" icon="content_copy" (onClick)="copySecret()">{{ 'iam.skopirovat' | t }}</ui-button>
        </div>
        <ui-button variant="primary" size="md" class="mt-4" (onClick)="isTokenSecretModalOpen.set(false)">{{ 'iam.ya_sohranil_token' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- Session Termination Confirmation -->
    <ui-modal
      [isOpen]="sessionToTerminate !== null"
      [title]="'iam.zavershenie_sessii' | t"
      size="sm"
      (close)="sessionToTerminate = null"
    >
      <div body class="confirmation-body" *ngIf="sessionToTerminate as target">
        <p *ngIf="target === 'others'">{{ 'iam.zavershit_vse_ostalnye_aktivnye_sessii_krome_tek' | t }}</p>
        <p *ngIf="target !== 'others'">{{ 'iam.zavershit_sessiyu_s_ip' | t }} <strong>{{ target.ip }}</strong>?</p>
        <span class="confirmation-hint">{{ 'iam.na_zavershennyh_ustroystvah_potrebuetsya_vypolni' | t }}</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="sessionToTerminate = null">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmTerminateSession()">{{ 'iam.zavershit' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- Token Revocation Confirmation -->
    <ui-modal
      [isOpen]="tokenToRevoke !== null"
      [title]="'iam.otzyv_api_tokena' | t"
      size="sm"
      (close)="tokenToRevoke = null"
    >
      <div body class="confirmation-body" *ngIf="tokenToRevoke as token">
        <p>{{ 'iam.otozvat_api_token' | t }} <strong>{{ token.name }}</strong>?</p>
        <span class="confirmation-hint">{{ 'iam.integracii_s_etim_tokenom_nemedlenno_poteryayut_' | t }}</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="tokenToRevoke = null">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmRevokeToken()">{{ 'iam.otozvat' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .profile-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
      min-width: 0;
    }

    .page-header {
      margin-bottom: 4px;
    }

    .page-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
    }

    .page-subtitle {
      font-size: 13px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 16px 20px;
    }

    .user-card {
      display: flex;
      align-items: center;
      gap: 20px;
    }

    .user-avatar-large {
      width: 64px;
      height: 64px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #ffffff;
      font-size: 26px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .user-details { flex: 1; min-width: 0; }

    .user-title-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;
    }

    .user-fullname {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .badge-icon { font-size: 14px; vertical-align: middle; }

    .user-info-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
      gap: 10px;
      font-size: 13px;
    }

    .info-label { color: var(--text-muted); margin-right: 6px; }
    .info-value { color: var(--text-main); font-weight: 500; }

    .sections-grid {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 16px;
    }

    .full-width {
      grid-column: 1 / -1;
    }

    @media (max-width: 1024px) {
      .sections-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .section-card { min-width: 0; }
    .info-value { overflow-wrap: anywhere; }

    @media (max-width: 640px) {
      .card { padding: 14px; }
      .user-card { align-items: flex-start; gap: 12px; }
      .user-avatar-large {
        width: 48px;
        height: 48px;
        flex-basis: 48px;
        font-size: 20px;
      }
      .user-title-row { flex-wrap: wrap; }
      .section-header {
        align-items: flex-start;
        flex-direction: column;
        gap: 10px;
      }
      .sessions-header-actions {
        width: 100%;
        flex-wrap: wrap;
      }
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

    .sessions-header-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .password-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req { color: var(--danger); }

    .field-hint {
      font-size: 11px;
      color: var(--text-muted);
    }
    .field-error { font-size: 11px; color: var(--danger); }

    .form-input {
      height: 36px;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      min-width: 0;
      max-width: 100%;
    }
    .form-input:focus { border-color: var(--primary); }

    .password-input-box {
      position: relative;
      display: flex;
      align-items: center;
    }
    .password-input-box .form-input {
      width: 100%;
      padding-right: 36px;
    }
    .pwd-toggle-btn {
      position: absolute;
      right: 6px;
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
      padding: 4px;
    }

    .form-actions {
      margin-top: 4px;
      display: flex;
      justify-content: flex-end;
    }

    .security-info-box {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .twofa-status-banner {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 14px;
      border-radius: var(--radius-md);
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
    }
    .twofa-status-banner.enabled {
      background-color: rgba(16, 185, 129, 0.08);
      border-color: rgba(16, 185, 129, 0.25);
    }

    .twofa-big-icon {
      font-size: 32px;
      color: var(--text-light);
    }
    .twofa-status-banner.enabled .twofa-big-icon {
      color: var(--success);
    }

    .twofa-status-text {
      display: flex;
      flex-direction: column;
    }
    .twofa-status-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
    }
    .twofa-status-desc {
      font-size: 12px;
      color: var(--text-muted);
    }

    .security-tips {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 10px 12px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
    }

    .tip-item {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: var(--text-muted);
    }
    .tip-icon { font-size: 16px; color: var(--primary); }

    .table-wrapper { overflow-x: auto; }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }

    .data-table th {
      text-align: left;
      padding: 8px 12px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 12px;
    }

    .data-table td {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }

    .token-prefix-cell {
      background-color: var(--bg-hover);
      padding: 2px 6px;
      border-radius: 4px;
      display: inline-block;
    }

    .text-right { text-align: right; }
    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }
    .empty-cell { text-align: center; color: var(--text-muted); padding: 24px !important; }

    .warning-box {
      background-color: var(--warning-bg);
      color: var(--warning);
      padding: 10px 12px;
      border-radius: var(--radius-sm);
      display: flex;
      gap: 8px;
      font-size: 12px;
      margin-bottom: 12px;
    }

    .token-secret-box {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background-color: var(--bg-hover);
      padding: 8px 12px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      font-family: monospace;
      font-size: 13px;
      word-break: break-all;
    }

    .mt-4 { margin-top: 16px; }
    .confirmation-body { display: flex; flex-direction: column; gap: 8px; }
    .confirmation-body p { margin: 0; }
    .confirmation-hint { color: var(--text-muted); font-size: 12px; }
  `]
})
export class ProfileComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  readonly sessions = signal<UserSession[]>([]);
  readonly tokens = signal<ApiToken[]>([]);

  readonly isCreateTokenModalOpen = signal<boolean>(false);
  readonly isTokenSecretModalOpen = signal<boolean>(false);
  readonly showNewPassword = signal<boolean>(false);
  readonly isChangingPassword = signal<boolean>(false);
  isPasswordSubmitted = false;
  isTokenSubmitted = false;

  newTokenName = '';
  createdTokenSecret = '';
  sessionToTerminate: UserSession | 'others' | null = null;
  tokenToRevoke: ApiToken | null = null;

  passwordForm = {
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  };

  constructor(
    public authService: AuthService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadSessions();
    this.loadTokens();
  }

  loadSessions() {
    this.api.get<UserSession[]>('/iam/profile/sessions').subscribe(res => {
      this.sessions.set(res || []);
    });
  }

  requestTerminateSession(session: UserSession) {
    this.sessionToTerminate = session;
  }

  requestTerminateOtherSessions() {
    this.sessionToTerminate = 'others';
  }

  confirmTerminateSession() {
    const target = this.sessionToTerminate;
    if (!target) return;

    if (target === 'others') {
      this.api.delete('/iam/profile/sessions/others').subscribe({
        next: () => {
          this.sessionToTerminate = null;
          this.toast.success(this.uiI18n.translate('iam.vse_ostalnye_sessii_uspeshno_zaversheny'));
          this.loadSessions();
        },
        error: (err: any) => {
          this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_pri_zavershenii_sessiy'));
        }
      });
      return;
    }

    this.api.delete(`/iam/profile/sessions/${target.id}`).subscribe({
      next: () => {
        this.sessionToTerminate = null;
        this.toast.success(this.uiI18n.translate('iam.sessiya_uspeshno_zavershena'));
        this.loadSessions();
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_pri_zavershenii_sessii'));
      }
    });
  }

  submitChangePassword(event: Event) {
    event.preventDefault();
    this.isPasswordSubmitted = true;

    if (!this.passwordForm.oldPassword || !this.passwordForm.newPassword || !this.passwordForm.confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.zapolnite_vse_polya_smeny_parolya'));
      return;
    }

    if (this.passwordForm.newPassword.length < 10) {
      this.toast.warning(this.uiI18n.translate('iam.novyy_parol_dolzhen_soderzhat_minimum_10_simvolo'));
      return;
    }

    if (this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
      this.toast.warning(this.uiI18n.translate('iam.novyy_parol_i_podtverzhdenie_ne_sovpadayut'));
      return;
    }

    this.isChangingPassword.set(true);
    this.api.post('/iam/users/me/password', {
      oldPassword: this.passwordForm.oldPassword,
      newPassword: this.passwordForm.newPassword
    }).subscribe({
      next: () => {
        this.isChangingPassword.set(false);
        this.toast.success(this.uiI18n.translate('iam.parol_uspeshno_izmenen'));
        this.passwordForm = { oldPassword: '', newPassword: '', confirmPassword: '' };
        this.isPasswordSubmitted = false;
      },
      error: (err: any) => {
        this.isChangingPassword.set(false);
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_pri_smene_parolya'));
      }
    });
  }

  loadTokens() {
    this.api.get<ApiToken[]>('/iam/profile/tokens').subscribe(res => {
      this.tokens.set(res || []);
    });
  }

  openCreateTokenModal() {
    this.newTokenName = '';
    this.isTokenSubmitted = false;
    this.isCreateTokenModalOpen.set(true);
  }

  createTokenSubmit() {
    this.isTokenSubmitted = true;
    if (!this.newTokenName.trim()) {
      this.toast.warning(this.uiI18n.translate('iam.vvedite_nazvanie_api_tokena.bfec35d'));
      return;
    }

    this.api.post<CreatedTokenResponse>('/iam/profile/tokens', { name: this.newTokenName.trim() }).subscribe({
      next: res => {
        this.isCreateTokenModalOpen.set(false);
        this.isTokenSubmitted = false;
        this.createdTokenSecret = res.rawSecretToken;
        this.isTokenSecretModalOpen.set(true);
        this.loadTokens();
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_pri_sozdanii_api_tokena'));
      }
    });
  }

  requestRevokeToken(token: ApiToken) {
    this.tokenToRevoke = token;
  }

  confirmRevokeToken() {
    if (!this.tokenToRevoke) return;
    const token = this.tokenToRevoke;
    this.api.delete(`/iam/profile/tokens/${token.id}`).subscribe({
      next: () => {
        this.tokenToRevoke = null;
        this.toast.success(this.uiI18n.translate('iam.token_uspeshno_otozvan'));
        this.loadTokens();
      },
      error: (err: any) => {
        this.toast.error(err?.error?.detail || this.uiI18n.translate('iam.oshibka_pri_otzyve_tokena'));
      }
    });
  }

  copySecret() {
    navigator.clipboard.writeText(this.createdTokenSecret);
    this.toast.success(this.uiI18n.translate('iam.token_skopirovan_v_bufer_obmena'));
  }
}
