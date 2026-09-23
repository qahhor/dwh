/* Adapter, not vendored code.
 *
 * The kit carries its own theme service. This application already has one,
 * which owns `data-theme` and the cross-tab synchronisation, so this exposes
 * the single member the vendored skeleton directive reads, backed by ours. */
import { Injectable, computed, inject } from '@angular/core';
import { ThemeService } from '../../../core/services/theme.service';

@Injectable({ providedIn: 'root' })
export class SMTThemeService {
  private readonly theme = inject(ThemeService);
  readonly resolvedTheme = computed(() => this.theme.currentTheme());
}
