import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe } from '@core/services/i18n.service';
import { PasswordForm, PasswordStrength } from '../profile.models';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';

@Component({
  selector: 'app-profile-password-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, SMTInputValueAccessor, FormsModule, TranslatePipe, SMTButtonComponent, NgClass],
  templateUrl: './profile-password-card.component.html',
  styles: [
    `
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

      .req {
        color: var(--danger);
      }

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
        transition:
          border-color 0.15s ease,
          box-shadow 0.15s ease;
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

      .strength-label {
        color: var(--text-muted);
      }
      .strength-value {
        font-weight: 600;
      }

      .strength-weak {
        color: var(--danger);
      }
      .strength-medium {
        color: var(--warning);
      }
      .strength-good {
        color: var(--info-text);
      }
      .strength-strong {
        color: var(--success);
      }

      .strength-bar-track {
        height: 4px;
        background-color: var(--border-color);
        border-radius: 2px;
        overflow: hidden;
      }

      .strength-bar-fill {
        height: 100%;
        border-radius: 2px;
        transition:
          width 0.25s ease,
          background-color 0.25s ease;
      }
      .strength-bar-fill.strength-weak {
        background-color: var(--danger);
      }
      .strength-bar-fill.strength-medium {
        background-color: var(--warning);
      }
      .strength-bar-fill.strength-good {
        background-color: var(--info);
      }
      .strength-bar-fill.strength-strong {
        background-color: var(--success);
      }

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

      .check-icon {
        font-size: 14px;
      }

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

      .match-icon {
        font-size: 14px;
      }
    `,
  ],
})
export class ProfilePasswordCardComponent {
  readonly passwordForm = input.required<PasswordForm>();

  readonly isPasswordSubmitted = input(false);
  readonly isChangingPassword = input(false);
  readonly passwordStrength = input<PasswordStrength>({ score: 0, label: '', percent: 0, colorClass: '' });
  readonly hasMinLength = input(false);
  readonly hasLettersAndNumbers = input(false);
  readonly hasMixedCase = input(false);
  readonly passwordsMatch = input(false);

  readonly submitPassword = output<Event>();

  readonly passwordPolicy = PASSWORD_POLICY;
  readonly fitsPolicy = fitsPasswordPolicy;

  onSubmit(e: Event) {
    this.submitPassword.emit(e);
  }
}
