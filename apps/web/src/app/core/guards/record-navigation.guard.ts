import { inject } from '@angular/core';
import { CanDeactivateFn, PRIMARY_OUTLET } from '@angular/router';
import { Observable, Subscriber } from 'rxjs';
import { AuthService } from '../services/auth.service';

export interface RecordNavigationPage {
  canLeaveRecordPage(): boolean | Observable<boolean>;
}

export const recordNavigationGuard: CanDeactivateFn<RecordNavigationPage> = (page, _route, _current, next) => {
  const destination = next.root.children.find(route => route.outlet === PRIMARY_OUTLET);
  // Successful logout/password change clears the session before navigating.
  // Never keep authenticated-page data mounted behind a draft prompt then.
  if (inject(AuthService).currentUser() === null && destination?.routeConfig?.path === 'login'
    && destination.url.length === 1 && destination.url[0].path === 'login') return true;
  return page.canLeaveRecordPage();
};

/** One pending navigation decision, owned by the existing page discard dialog. */
export class RecordNavigationDecision {
  private observer: Subscriber<boolean> | null = null;

  get pending(): boolean { return this.observer !== null; }

  request(show: () => void, hide: () => void): Observable<boolean> {
    return new Observable(observer => {
      this.settle(false);
      this.observer = observer;
      show();
      // Router unsubscribes when a newer navigation replaces this one. Do not
      // let an old teardown dismiss the next navigation's confirmation.
      return () => {
        if (this.observer === observer) {
          this.observer = null;
          hide();
        }
      };
    });
  }

  settle(allow: boolean): void {
    const observer = this.observer;
    if (!observer) return;
    observer.next(allow);
    observer.complete();
  }
}
