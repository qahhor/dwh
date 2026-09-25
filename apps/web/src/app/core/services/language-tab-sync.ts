import { DestroyRef, Injectable, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { I18nService } from './i18n.service';
import { TabSyncService } from './tab-sync.service';

/**
 * Carries a language the person chose in one tab to the others (roadmap item
 * 27). The choice is already saved to their settings, so the other tabs only
 * switch the page, without saving again.
 */
@Injectable({ providedIn: 'root' })
export class LanguageTabSync {
  private readonly i18n = inject(I18nService);
  private readonly tabs = inject(TabSyncService);
  private readonly destroyRef = inject(DestroyRef);
  private started = false;

  start(): void {
    if (this.started) return;
    this.started = true;
    this.i18n.languageChosen.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(code => this.tabs.publish({ kind: 'language', code }));
    this.tabs.messages.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(message => {
      if (message.kind !== 'language' || message.code === this.i18n.currentLang()) return;
      this.i18n.setLanguage(message.code, false).subscribe({ error: () => undefined });
    });
  }
}
