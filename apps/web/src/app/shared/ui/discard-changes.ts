import { inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';

/** Asks whether to drop unsaved changes; emits true when they may be dropped. */
export type DiscardChangesQuestion = (dirty: boolean) => Observable<boolean>;

/**
 * The one "discard changes?" question of the forms (docs/guidelines/forms-ux-standard.md, section 8; texts
 * common.discard.*): closing a changed dialog (Escape, backdrop, the cross, "Cancel") or leaving a changed page asks
 * it; an untouched form closes without a question. Call in an injection context:
 *
 * private readonly askDiscard = discardChangesQuestion();
 * close(): void { this.askDiscard(this.dirty()).subscribe((ok) => ok && this.open.set(false)); }
 */
export function discardChangesQuestion(): DiscardChangesQuestion {
  const modal = inject(SMTModalService);
  const i18n = inject(I18nService);
  return (dirty) =>
    dirty
      ? modal.confirm({
          title: i18n.translate('common.discard.title'),
          message: i18n.translate('common.discard.message'),
          yesLabel: i18n.translate('common.discard.confirm'),
          noLabel: i18n.translate('common.discard.keep'),
          destructive: true,
        })
      : of(true);
}

/** Whether a form's values differ from the ones it opened with; text is compared without outer spaces. */
export function formChanged<T extends object>(initial: T, current: T): boolean {
  const keys = new Set([...Object.keys(initial), ...Object.keys(current)]) as Set<keyof T>;
  for (const key of keys) {
    const before = initial[key];
    const after = current[key];
    const same =
      typeof before === 'string' && typeof after === 'string' ? before.trim() === after.trim() : before === after;
    if (!same) return true;
  }
  return false;
}
