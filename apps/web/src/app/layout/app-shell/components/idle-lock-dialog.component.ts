import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AuthService } from '../../../core/services/auth.service';
import { IdleLockService } from '../../../core/services/idle-lock.service';
import { TranslatePipe } from '../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';

/**
 * The warning before an idle session is closed (roadmap item 28): the seconds
 * left, "Go on" and "Sign out". It cannot be dismissed any other way — closing
 * it silently would leave the countdown running unseen.
 */
@Component({
  selector: 'app-idle-lock-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, SMTButtonComponent, UiModalComponent],
  template: `
    <ui-modal [isOpen]="idle.warningSeconds() !== null" [dismissible]="false" size="sm" [title]="'auth.idle.title' | t">
      <p body class="idle-text" role="alert" data-testid="idle-warning">
        {{ 'auth.idle.message' | t: { seconds: idle.warningSeconds() ?? 0 } }}
      </p>
      <div footer class="idle-actions">
        <button smt-button type="button" smtVariant="secondary" smtSize="md" data-testid="idle-sign-out" (click)="auth.logout()">{{ 'auth.idle.sign_out' | t }}</button>
        <button smt-button type="button" smtVariant="primary" smtSize="md" data-testid="idle-keep" (click)="idle.keepWorking()">{{ 'auth.idle.keep' | t }}</button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .idle-text { margin: 0; font-size: 14px; color: var(--text-main); }
    .idle-actions { display: flex; justify-content: flex-end; gap: 8px; width: 100%; }
  `]
})
export class IdleLockDialogComponent {
  readonly idle = inject(IdleLockService);
  readonly auth = inject(AuthService);
}
