import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { PasswordForm, PasswordStrength } from '../profile.models';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-profile-password-card',
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, 
    CommonModule,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent
  ],
  template: `
    <div class="card section-card">
      <div class="section-header">
        <div class="section-title-box">
          <span class="material-symbols-outlined section-icon" aria-hidden="true">lock_reset</span>
          <h4 class="section-title">{{ 'iam.smena_parolya' | t }}</h4>
        </div>
      </div>

      <form class="password-form" (submit)="onSubmit($event)">
        <!-- Current Password -->
        <div class="form-group">
          <label class="form-label" for="profile-current-password">{{ 'iam.tekuschiy_parol' | t }} <span class="req">*</span></label>
          <smt-input
            smtFieldId="profile-current-password"
            type="password"
            class="font-mono"
            autocomplete="current-password"
            [(ngModel)]="passwordForm.oldPassword"
            name="oldPassword"
            [smtInvalid]="isPasswordSubmitted && !passwordForm.oldPassword"
            [smtDescribedBy]="isPasswordSubmitted && !passwordForm.oldPassword ? 'profile-current-password-error' : null"
            [placeholder]="'iam.vvedite_tekuschiy_parol' | t"
            required />
          <span id="profile-current-password-error" class="field-error" *ngIf="isPasswordSubmitted && !passwordForm.oldPassword">
            {{ 'iam.vvedite_tekuschiy_parol' | t }}
          </span>
        </div>

        <!-- New Password -->
        <div class="form-group">
          <label class="form-label" for="profile-new-password">{{ 'auth.novyy_parol' | t }} <span class="req">*</span></label>
          <smt-input
            smtFieldId="profile-new-password"
            type="password"
            class="font-mono"
            autocomplete="new-password"
            [minLength]="10"
            [(ngModel)]="passwordForm.newPassword"
            name="newPassword"
            [smtInvalid]="isPasswordSubmitted && passwordForm.newPassword.length < 10"
            [smtDescribedBy]="isPasswordSubmitted && passwordForm.newPassword.length < 10 ? 'profile-new-password-hint profile-new-password-error' : 'profile-new-password-hint'"
            [placeholder]="'iam.minimum_10_simvolov' | t"
            required />
          <span id="profile-new-password-hint" class="field-hint">{{ 'iam.minimum_10_simvolov_ne_iz_chernogo_spiska_i_ne_s' | t }}</span>
          <span id="profile-new-password-error" class="field-error" *ngIf="isPasswordSubmitted && passwordForm.newPassword.length < 10">
            {{ 'iam.parol_dolzhen_soderzhat_ne_menee_10_simvolov' | t }}
          </span>

          <!-- Live Password Strength Meter -->
          <div class="strength-meter-container" *ngIf="passwordForm.newPassword">
            <div class="strength-header">
              <span class="strength-label">{{ 'iam.nadezhnost_parolya' | t }}:</span>
              <span class="strength-value" [ngClass]="passwordStrength.colorClass">
                {{ passwordStrength.label | t }}
              </span>
            </div>
            <div class="strength-bar-track">
              <div
                class="strength-bar-fill"
                [ngClass]="passwordStrength.colorClass"
                [style.width.%]="passwordStrength.percent"
              ></div>
            </div>
            <div class="strength-checklist">
              <div class="check-item" [class.valid]="hasMinLength">
                <span class="material-symbols-outlined check-icon" aria-hidden="true">{{ hasMinLength ? 'check_circle' : 'radio_button_unchecked' }}</span>
                <span>{{ 'iam.trebovanie_dlina' | t }}</span>
                  <span class="sr-only">{{ (hasMinLength ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
              </div>
              <div class="check-item" [class.valid]="hasLettersAndNumbers">
                <span class="material-symbols-outlined check-icon" aria-hidden="true">{{ hasLettersAndNumbers ? 'check_circle' : 'radio_button_unchecked' }}</span>
                <span>{{ 'iam.trebovanie_bukvy_i_cifry' | t }}</span>
                  <span class="sr-only">{{ (hasLettersAndNumbers ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
              </div>
              <div class="check-item" [class.valid]="hasMixedCase">
                <span class="material-symbols-outlined check-icon" aria-hidden="true">{{ hasMixedCase ? 'check_circle' : 'radio_button_unchecked' }}</span>
                <span>{{ 'iam.trebovanie_raznyy_registr' | t }}</span>
                  <span class="sr-only">{{ (hasMixedCase ? 'common.requirement_met' : 'common.requirement_not_met') | t }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Confirm Password -->
        <div class="form-group">
          <label class="form-label" for="profile-confirm-password">{{ 'iam.podtverzhdenie_novogo_parolya' | t }} <span class="req">*</span></label>
          <smt-input
            smtFieldId="profile-confirm-password"
            type="password"
            class="font-mono"
            autocomplete="new-password"
            [(ngModel)]="passwordForm.confirmPassword"
            name="confirmPassword"
            [smtInvalid]="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword)"
            [smtDescribedBy]="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword) ? 'profile-confirm-password-error' : null"
            [placeholder]="'auth.povtorite_novyy_parol' | t"
            required />
          <div class="password-match-hint" *ngIf="passwordForm.confirmPassword && passwordForm.newPassword">
            <span class="match-badge match-ok" *ngIf="passwordsMatch">
              <span class="material-symbols-outlined match-icon" aria-hidden="true">check</span>
              {{ 'iam.paroli_sovpadayut' | t }}
            </span>
            <span class="match-badge match-error" *ngIf="!passwordsMatch">
              <span class="material-symbols-outlined match-icon" aria-hidden="true">close</span>
              {{ 'iam.paroli_ne_sovpadayut' | t }}
            </span>
          </div>
          <span id="profile-confirm-password-error" class="field-error" *ngIf="isPasswordSubmitted && (!passwordForm.confirmPassword || passwordForm.newPassword !== passwordForm.confirmPassword)">
            {{ (!passwordForm.confirmPassword ? 'iam.confirm_new_password' : 'iam.passwords_do_not_match') | t }}
          </span>
        </div>

        <div class="form-actions">
          <button smt-button smtVariant="primary" smtSize="md" [smtLoading]="isChangingPassword" type="submit">
            {{ 'iam.obnovit_parol' | t }}
          </button>
        </div>
      </form>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
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

    .password-form {
      display: flex;
      flex-direction: column;
      gap: 14px;
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
      min-width: 0;
      width: 100%;
      box-sizing: border-box;
      transition: border-color 0.15s ease, box-shadow 0.15s ease;
    }

    .form-input:focus {
      border-color: var(--primary);
      box-shadow: 0 0 0 2px rgba(99, 102, 241, 0.15);
    }

    .font-mono {
      font-family: monospace;
    }





    .form-actions {
      margin-top: 4px;
      display: flex;
      justify-content: flex-end;
    }

    /* Strength Meter */
    .strength-meter-container {
      margin-top: 6px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      background-color: var(--bg-hover);
      padding: 8px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
    }

    .strength-header {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
    }

    .strength-label { color: var(--text-muted); }
    .strength-value { font-weight: 600; }

    .strength-weak { color: var(--danger); }
    .strength-medium { color: var(--warning); }
    .strength-good { color: var(--info-text); }
    .strength-strong { color: var(--success); }

    .strength-bar-track {
      height: 4px;
      background-color: var(--border-color);
      border-radius: 2px;
      overflow: hidden;
    }

    .strength-bar-fill {
      height: 100%;
      border-radius: 2px;
      transition: width 0.25s ease, background-color 0.25s ease;
    }
    .strength-bar-fill.strength-weak { background-color: var(--danger); }
    .strength-bar-fill.strength-medium { background-color: var(--warning); }
    .strength-bar-fill.strength-good { background-color: var(--info); }
    .strength-bar-fill.strength-strong { background-color: var(--success); }

    .strength-checklist {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 14px;
      font-size: 11px;
      color: var(--text-muted);
      margin-top: 2px;
    }

    .check-item {
      display: flex;
      align-items: center;
      gap: 4px;
      transition: color 0.15s ease;
    }

    .check-item.valid {
      color: var(--success);
      font-weight: 500;
    }

    .check-icon { font-size: 14px; }

    .password-match-hint {
      margin-top: 4px;
      display: flex;
      align-items: center;
    }

    .match-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      font-weight: 500;
      padding: 2px 8px;
      border-radius: var(--radius-sm);
    }

    .match-ok {
      background-color: rgba(16, 185, 129, 0.1);
      color: var(--success);
    }

    .match-error {
      background-color: rgba(239, 68, 68, 0.1);
      color: var(--danger);
    }

    .match-icon { font-size: 14px; }
  `]
})
export class ProfilePasswordCardComponent {
  @Input() passwordForm!: PasswordForm;
  @Input() isPasswordSubmitted = false;
  @Input() isChangingPassword = false;
  @Input() passwordStrength: PasswordStrength = { score: 0, label: '', percent: 0, colorClass: '' };
  @Input() hasMinLength = false;
  @Input() hasLettersAndNumbers = false;
  @Input() hasMixedCase = false;
  @Input() passwordsMatch = false;
  @Output() submitPassword = new EventEmitter<Event>();

  onSubmit(e: Event) {
    this.submitPassword.emit(e);
  }
}
