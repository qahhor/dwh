import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AuthService } from '@core/services/auth.service';
import { IdleLockService } from '@core/services/idle-lock.service';
import { TranslatePipe } from '@core/services/i18n.service';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';

/**
 * The warning before an idle session is closed (roadmap item 28): the seconds
 * left, "Go on" and "Sign out". It cannot be dismissed any other way — closing
 * it silently would leave the countdown running unseen.
 */
@Component({
  selector: 'app-idle-lock-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiFormActionsComponent, SMTDialogComponent, SMTDialogContentDirective],
  template: `
    <smt-dialog
      [open]="idle.warningSeconds() !== null"
      [dismissible]="false"
      smtSize="sm"
      [smtTitle]="'auth.idle.title' | t"
    >
      <ng-template smtDialogContent>
        <p body class="idle-text" role="alert" data-testid="idle-warning">
          {{ 'auth.idle.message' | t: { seconds: idle.warningSeconds() ?? 0 } }}
        </p>
        <ui-form-actions
          footer
          data-testid="idle-actions"
          [submitLabel]="'auth.idle.keep' | t"
          [cancelLabel]="'auth.idle.sign_out' | t"
          (submitted)="idle.keepWorking()"
          (cancelled)="auth.logout()"
        />
      </ng-template>
    </smt-dialog>
  `,
  styles: [
    `
      .idle-text {
        margin: 0;
        font-size: 14px;
        color: var(--text-main);
      }
      ui-form-actions {
        width: 100%;
      }
    `,
  ],
})
export class IdleLockDialogComponent {
  readonly idle = inject(IdleLockService);
  readonly auth = inject(AuthService);
}
