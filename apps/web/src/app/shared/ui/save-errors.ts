import { Injectable, inject } from '@angular/core';
import { I18nService } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { problemText } from './problem-text';

/**
 * A change refused because the record moved on since it was read (plan 10/10, item 3.6, ADR-0024): 409 with the code
 * `revision_conflict`, or 428 when the change named no revision at all.
 */
export function isRevisionConflict(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const { status, code } = error as { status?: unknown; code?: unknown };
  return status === 428 || (status === 409 && code === 'revision_conflict');
}

export interface SaveErrorOptions {
  /** Catalog key of the text shown when the server gave none. */
  fallbackKey: string;
  /**
   * Reads the record or the list again. A refused change caused by a newer revision offers it as a button, so the
   * person sees what changed before saving again.
   */
  reload?: () => void;
}

/**
 * The one way a screen reports a failed change (plan 10/10, item 3.6): a stale revision shows the server's text with
 * a "Refresh" button; any other failure shows the server's text, or the screen's own when there is none. The request
 * is sent with `notifyError: false`, so this is the only toast.
 */
@Injectable({ providedIn: 'root' })
export class SaveErrorNotifier {
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);

  /** How long a conflict stays on screen: long enough to read it and reach the button. */
  static readonly CONFLICT_TOAST_MS = 12000;

  show(error: unknown, options: SaveErrorOptions): void {
    if (isRevisionConflict(error)) {
      const reload = options.reload;
      this.toast.show(
        'error',
        problemText(error) || this.i18n.translate('error.common.revision_conflict'),
        this.i18n.translate('common.revision_conflict_title'),
        SaveErrorNotifier.CONFLICT_TOAST_MS,
        reload ? { label: this.i18n.translate('common.refresh'), run: reload } : undefined,
      );
      return;
    }
    this.toast.error(problemText(error) || this.i18n.translate(options.fallbackKey));
  }
}
