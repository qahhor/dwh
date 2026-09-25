/* Our code, after the idea of the kit's `smt-progress-stepper`
 * (smartup-ui-kit@6472beb, components/progress-stepper). See ADR-0015 rule 2.
 *
 * The kit's stepper derived a step's state from its position: everything
 * before the current step was "completed". Our editors let people move
 * between steps freely, so a step before the current one can still be
 * unfinished, and one after it can already hold errors. Here the caller
 * says what each step's status is, and position only decides which step is
 * current. It is a <nav> with an ordered list of real buttons: the current
 * one carries aria-current="step", the status is part of each button's
 * accessible name, and every step stays reachable with Tab (no roving
 * focus, since this is navigation rather than a composite widget).
 *
 *   <smt-progress-stepper smtLabel="Format steps" [smtSteps]="steps()"
 *     [(smtCurrent)]="step" /> */
import { ChangeDetectionStrategy, Component, inject, input, model, ViewEncapsulation } from '@angular/core';
import { SMTI18nService } from '../../i18n';

/** What the caller knows about a step: nothing yet, done, or holding errors. */
export type SMTProgressStepStatus = 'none' | 'complete' | 'error';

export interface SMTProgressStep {
  readonly id: string;
  readonly label: string;
  readonly status?: SMTProgressStepStatus;
  /** A short line under the label, such as "2 errors" or "3 sheets". */
  readonly hint?: string;
  /** Id of the panel this step shows, for aria-controls. */
  readonly controls?: string;
}

@Component({
  selector: 'smt-progress-stepper',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  templateUrl: './progress-stepper.component.html',
  styleUrl: './progress-stepper.scss',
  host: { class: 'smt-stepper-host' },
})
export class SMTProgressStepperComponent {
  readonly i18n = inject(SMTI18nService);

  /** Accessible name of the navigation landmark. */
  readonly label = input.required<string>({ alias: 'smtLabel' });

  readonly steps = input<readonly SMTProgressStep[]>([], { alias: 'smtSteps' });

  /** Id of the current step. */
  readonly current = model('', { alias: 'smtCurrent' });

  select(step: SMTProgressStep): void {
    this.current.set(step.id);
  }

  statusText(step: SMTProgressStep): string | null {
    const messages = this.i18n.messages().stepper;
    if (step.status === 'complete') return messages.complete;
    if (step.status === 'error') return messages.error;
    return null;
  }
}
