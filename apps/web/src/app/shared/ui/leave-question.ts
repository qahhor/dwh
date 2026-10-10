import { inject } from '@angular/core';
import { Dialog } from '@angular/cdk/dialog';
import { Observable, finalize, share, timer } from 'rxjs';
import { discardChangesQuestion } from './discard-changes';

/** Asks whether a page with unsaved changes may be left; emits true when it may. */
export type LeaveQuestion = () => Observable<boolean>;

/**
 * The "discard changes?" question for `canLeaveRecordPage()` (docs/guidelines/forms-ux-standard.md, section 8).
 *
 * The router drops a pending guard when a newer navigation replaces it, and asks the page again when that navigation
 * leaves it too: both get the one open question, and the answer settles the latest navigation. When nobody asks again
 * (the newer navigation needs no guard, such as the exit of an expired session), the question closes by itself, so no
 * stale dialog is left over another screen. Call in an injection context.
 */
export function leaveQuestion(): LeaveQuestion {
  const ask = discardChangesQuestion();
  const dialogs = inject(Dialog);
  let open: Observable<boolean> | null = null;
  return () => {
    if (open) return open;
    const answer = ask(true);
    // The confirmation opens as soon as it is asked for: it is the newest dialog now.
    const question = dialogs.openDialogs.at(-1);
    const shared = answer.pipe(
      finalize(() => {
        if (open === shared) open = null;
        if (question && dialogs.openDialogs.includes(question)) question.close();
      }),
      share({ resetOnRefCountZero: () => timer(0) }),
    );
    open = shared;
    return shared;
  };
}
