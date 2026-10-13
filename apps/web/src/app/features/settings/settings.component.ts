import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  Injector,
  OnInit,
  signal,
  inject,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, finalize } from 'rxjs';
import { RecordNavigationPage } from '@core/guards/record-navigation.guard';

import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SearchSettingsComponent } from './search/search-settings.component';
import { NavigationSettingsComponent } from './navigation/navigation-settings.component';
import { SettingsTab } from './settings.models';
import { SettingsStore } from './settings.store';
import { SettingsLanguagesStore } from './settings-languages.store';
import { SettingsGeneralPanelComponent } from './components/settings-general-panel.component';
import { SettingsSecurityPanelComponent } from './components/settings-security-panel.component';
import { SettingsStoragePanelComponent } from './components/settings-storage-panel.component';
import { SettingsPreferencesPanelComponent } from './components/settings-preferences-panel.component';
import { SettingsLanguagesPanelComponent } from './components/settings-languages-panel.component';
import { WebhooksSettingsComponent } from './webhooks/webhooks-settings.component';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { focusFirstInvalid } from '@shared/ui/focus-first-invalid';

/** The panel that holds each validated system setting. */
const SETTING_TAB: Readonly<Record<string, SettingsTab>> = {
  'security.session_lifetime_hours': 'security',
  'security.idle_lock_minutes': 'security',
  'storage.default_user_quota_mb': 'storage',
};

@Component({
  selector: 'app-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTTabBarComponent,
    TranslatePipe,
    SMTButtonComponent,
    SearchSettingsComponent,
    NavigationSettingsComponent,
    SettingsGeneralPanelComponent,
    SettingsSecurityPanelComponent,
    SettingsStoragePanelComponent,
    SettingsPreferencesPanelComponent,
    SettingsLanguagesPanelComponent,
    WebhooksSettingsComponent,
  ],
  providers: [SettingsStore, SettingsLanguagesStore],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class SettingsComponent implements OnInit, RecordNavigationPage {
  /** System and personal settings; the template reads it directly. */
  readonly store = inject(SettingsStore);
  /** The languages tab; the template reads it directly. */
  readonly languageStore = inject(SettingsLanguagesStore);
  /** Languages for the panels, and the texts of the tabs, translated again when the language changes. */
  readonly i18n = inject(I18nService);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly router = inject(Router, { optional: true });
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly languagesPanel = viewChild(SettingsLanguagesPanelComponent);
  private readonly tabBar = viewChild(SMTTabBarComponent);

  readonly activeTab = signal<SettingsTab>('general');

  private readonly askDiscard = discardChangesQuestion();

  private readonly tabsMemo = optionsMemo<SMTTabItem<SettingsTab>[]>();
  private changingTab = false;

  ngOnInit() {
    if (this.route) {
      this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
        const tabParam = params['tab'];
        if (tabParam && this.isTabAvailable(tabParam)) {
          this.setTab(tabParam, true);
        } else {
          this.setTab(this.store.canManageSystemSettings() ? 'general' : 'preferences', true);
        }
      });
    } else if (!this.store.canManageSystemSettings()) {
      this.activeTab.set('preferences');
    }
  }

  /**
   * Saves the system settings; a refused value is shown under its field, on its panel, with focus on it
   * (forms standard, section 4), even when the save came from another panel or from Ctrl+S.
   */
  saveSystemSettings(): void {
    this.store.saveSystemSettings();
    const first = Object.keys(this.store.systemErrors())[0];
    if (!first) return;
    const tab = SETTING_TAB[first];
    if (tab && tab !== this.activeTab()) this.setTab(tab);
    const panel = document.getElementById(`settings-${tab ?? this.activeTab()}-panel`);
    if (panel) focusFirstInvalid(panel, this.injector);
  }

  /** Unsaved settings make leaving the screen ask first (forms standard, section 8). */
  canLeaveRecordPage(): boolean | Observable<boolean> {
    if (this.store.isSaving() || this.languageStore.isAddingLang() || this.languagesPanel()?.isSaving()) return false;
    return this.store.dirty() || this.languagesPanel()?.hasUnsavedChanges() ? this.askDiscard(true) : true;
  }

  isTabAvailable(tab: string): tab is SettingsTab {
    switch (tab) {
      case 'general':
      case 'security':
      case 'storage':
      case 'languages':
        return this.store.canManageSystemSettings();
      case 'preferences':
        return true;
      case 'search':
        return this.store.canViewSearchSettings();
      case 'navigation':
        return this.store.canViewNavigationSettings();
      case 'webhooks':
        return this.store.canViewWebhookSettings();
      default:
        return false;
    }
  }

  setTab(tab: SettingsTab, fromRoute = false): void {
    if (!this.isTabAvailable(tab) || tab === this.activeTab() || this.changingTab) return;
    this.tabBar()?.value.set(this.activeTab());
    if (this.languagesPanel()?.isSaving()) {
      this.syncTabUrl();
      return;
    }
    this.changingTab = true;
    this.askDiscard(this.languagesPanel()?.hasUnsavedChanges() ?? false)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.changingTab = false;
        }),
      )
      .subscribe((discard) => {
        if (discard) {
          if (this.activeTab() === 'languages') {
            this.languageStore.editingLanguageCode.set(null);
            this.languageStore.isAddLangModalOpen.set(false);
          }
          this.activeTab.set(tab);
        }
        if (!discard || !fromRoute) this.syncTabUrl();
      });
  }

  @HostListener('window:keydown', ['$event'])
  handleGlobalKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && !event.altKey && !event.shiftKey) {
      event.preventDefault();
      if (['general', 'security', 'storage'].includes(this.activeTab())) {
        if (this.store.canUpdateSystemSettings() && !this.store.isSaving()) {
          this.saveSystemSettings();
        }
      } else if (this.activeTab() === 'preferences') {
        if (!this.store.isSaving()) {
          this.store.saveUserSettings();
        }
      }
    }
  }

  /** The sections the viewer may open; each tab names its panel. */
  settingsTabs(): SMTTabItem<SettingsTab>[] {
    const all: [SettingsTab, string, string][] = [
      ['general', 'tune', 'settings.tab.general'],
      ['security', 'security', 'settings.tab.security'],
      ['storage', 'cloud', 'settings.tab.storage'],
      ['preferences', 'person', 'settings.tab.preferences'],
      ['languages', 'language', 'settings.page.languages_tab'],
      ['search', 'manage_search', 'settings.search.tab'],
      ['navigation', 'menu_open', 'settings.navigation.tab'],
      ['webhooks', 'webhook', 'settings.webhooks.tab'],
    ];
    const available = all.filter(([tab]) => this.isTabAvailable(tab));
    return this.tabsMemo([this.i18n.currentLang(), available.map(([tab]) => tab).join()], () =>
      available.map(([tab, icon, key]) => ({
        value: tab,
        label: this.i18n.translate(key),
        icon,
        id: `settings-${tab}-tab`,
        panelId: `settings-${tab}-panel`,
      })),
    );
  }

  private syncTabUrl(): void {
    if (this.router && this.route) {
      this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { tab: this.activeTab() },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    }
  }
}
