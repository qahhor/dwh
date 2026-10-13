import { signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { RecordNavigationDecision } from '@core/guards/record-navigation.guard';
import { DiscardChangesQuestion } from '@shared/ui/discard-changes';

/** A cancellable draft decision using the common question; the host owns pending/dirty state. */
export class OrgUnitDraft {
  readonly open = signal(false);
  private readonly navigation = new RecordNavigationDecision();
  private readonly closeQuestion = new Subject<void>();
  private action: (() => void) | null = null;

  constructor(
    private readonly dirty: () => boolean,
    private readonly pending: () => boolean,
    private readonly clear: () => void,
    private readonly ask: DiscardChangesQuestion,
  ) {}

  request(action: () => void): void {
    if (this.pending()) return;
    this.cancel();
    if (!this.dirty()) {
      action();
      return;
    }
    this.action = action;
    this.show();
  }
  canLeave(): boolean | Observable<boolean> {
    if (this.pending()) return false;
    if (!this.dirty()) {
      this.clear();
      return true;
    }
    this.cancel();
    return this.navigation.request(
      () => this.show(),
      () => this.hide(),
    );
  }
  confirm(): void {
    if (!this.open() || this.pending()) return;
    const action = this.action;
    this.action = null;
    this.hide();
    this.clear();
    this.navigation.settle(true);
    action?.();
  }
  cancel(): void {
    this.action = null;
    this.hide();
    this.navigation.settle(false);
  }

  private show(): void {
    this.open.set(true);
    this.ask(true, this.closeQuestion).subscribe((accepted) => {
      if (this.open()) {
        if (accepted) this.confirm();
        else this.cancel();
      }
    });
  }

  private hide(): void {
    this.open.set(false);
    this.closeQuestion.next();
  }
}
