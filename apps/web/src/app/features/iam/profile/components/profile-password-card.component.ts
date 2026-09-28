import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { NgClass } from '@angular/common';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TranslatePipe } from '@core/services/i18n.service';
import { PasswordForm, PasswordStrength } from '../profile.models';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { fitsPasswordPolicy, PASSWORD_POLICY } from '@core/security/password-policy';

@Component({
  selector: 'app-profile-password-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, TranslatePipe, SMTButtonComponent, NgClass],
  templateUrl: './profile-password-card.component.html',
  styleUrl: './profile-password-card.component.css',
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
