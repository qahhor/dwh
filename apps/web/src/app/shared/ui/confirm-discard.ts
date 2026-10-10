import { Observable, of } from 'rxjs';
import { I18nService } from '@core/services/i18n.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';

/**
 * Asks before a changed form is closed (docs/guidelines/forms-ux-standard.md, section 8): Escape, the backdrop, the
 * close button and "Cancel" of a dialog whose values differ from the ones it opened with. Emits true when the person
 * agrees to lose the changes; an untouched form (`dirty` false) closes without a question.
 *
 * requestClose(): void {
 *   confirmDiscard(this.modal, this.i18n, this.isDirty()).subscribe((discard) => discard && this.close());
 * }
 */
export function confirmDiscard(modal: SMTModalService, i18n: I18nService, dirty: boolean): Observable<boolean> {
  if (!dirty) return of(true);
  return modal.confirm({
    title: i18n.translate('common.discard.title'),
    message: i18n.translate('common.discard.message'),
    yesLabel: i18n.translate('common.discard.confirm'),
    noLabel: i18n.translate('common.discard.keep'),
    destructive: true,
  });
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
