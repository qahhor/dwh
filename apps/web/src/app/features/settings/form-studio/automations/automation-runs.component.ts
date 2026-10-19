import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTTagComponent } from '@shared/ui-kit/components/tag';
import { runTone } from './automation-words';
import {
  AutomationsApi,
  type Automation,
  type AutomationActionResult,
  type AutomationProblem,
  type AutomationRun,
} from './automations.api';

/**
 * The run log of an automation (ADR-0046), newest first: what started each run, the record, its outcome and why, and
 * what each action did — the values written, the record created, who was told — or its problem. Statuses are words and
 * tags, never colour alone.
 */
@Component({
  selector: 'app-automation-runs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    SMTAlertComponent,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTTagComponent,
    TranslatePipe,
  ],
  templateUrl: './automation-runs.component.html',
  styleUrl: './automation-runs.component.css',
})
export class AutomationRunsComponent implements OnInit {
  private readonly api = inject(AutomationsApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly automation = input.required<Automation>();
  readonly closed = output<void>();

  readonly runs = signal<AutomationRun[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly open = signal<number | null>(null);

  readonly tone = runTone;

  ngOnInit(): void {
    this.load(null);
  }

  load(cursor: string | null): void {
    this.loading.set(true);
    this.error.set('');
    this.api
      .runs(this.automation().id, cursor)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.loading.set(false);
          this.runs.update((runs) => (cursor ? [...runs, ...page.items] : page.items));
          this.cursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        },
        error: (failure: unknown) => {
          this.loading.set(false);
          this.error.set(problemText(failure) || this.i18n.translate('studio.automations.runs_failed'));
        },
      });
  }

  toggle(run: AutomationRun): void {
    this.open.set(this.open() === run.id ? null : run.id);
  }

  /** What an action did, in words: the values written, the record created, who was told. */
  detail(result: AutomationActionResult): string {
    this.i18n.currentLang();
    const parts: string[] = [];
    if (result.values) {
      parts.push(
        Object.entries(result.values)
          .map(([field, value]) => `${field} = ${String(value)}`)
          .join(', '),
      );
    }
    if (result.recordId) parts.push(this.i18n.translate('studio.automations.created_record', { id: result.recordId }));
    if (result.recipients?.length) {
      parts.push(
        this.i18n.translate('studio.automations.to_people', {
          people: result.recipients.map((person) => person.name).join(', '),
        }),
      );
    }
    if (result.reason) parts.push(this.i18n.translate(`studio.automations.reason.${result.reason}`));
    return parts.join(' · ');
  }

  problem(problem: AutomationProblem): string {
    const params = Object.fromEntries(
      Object.entries(problem.params ?? {}).map(([key, value]) => [key, String(value)]),
    ) as Record<string, string>;
    const fields = (problem.fields ?? []).map((item) => `${item.field}: ${this.i18n.translate(item.key)}`);
    return [this.i18n.translate(problem.key, params), ...fields].join('; ');
  }
}
