import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, concatMap, finalize, from, switchMap, toArray } from 'rxjs';
import { TranslationDictionary, TranslationEditor } from '../../core/models/i18n.models';
import { ApiService } from '../../core/services/api.service';
import { ToastService } from '../../core/services/toast.service';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { LanguageEditorComponent } from './language-editor.component';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent,
    UiModalComponent,
    LanguageEditorComponent
  ],
  template: `
    <div class="settings-page">
      <!-- Page Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'settings.title' | t }}</h1>
          <span class="count-badge">System Settings</span>
        </div>
        <div class="header-right">
          <button type="button" class="btn btn-secondary" [attr.aria-label]="'common.refresh' | t" (click)="loadAllSettings()" title="{{ 'common.refresh' | t }}">
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
            <span>{{ 'common.refresh' | t }}</span>
          </button>
        </div>
      </div>

      <!-- Tabs Navigation -->
      <div class="toolbar">
        <div class="status-tabs" role="tablist" [attr.aria-label]="'settings.razdely_nastroek' | t">
          <button
            *ngIf="canManageSystemSettings()"
            id="settings-general-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'general'"
            [attr.aria-selected]="activeTab === 'general'"
            aria-controls="settings-general-panel"
            (click)="activeTab = 'general'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">tune</span>
            <span>{{ 'settings.tab.general' | t }}</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-security-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'security'"
            [attr.aria-selected]="activeTab === 'security'"
            aria-controls="settings-security-panel"
            (click)="activeTab = 'security'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">security</span>
            <span>{{ 'settings.tab.security' | t }}</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-storage-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'storage'"
            [attr.aria-selected]="activeTab === 'storage'"
            aria-controls="settings-storage-panel"
            (click)="activeTab = 'storage'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">cloud</span>
            <span>{{ 'settings.tab.storage' | t }}</span>
          </button>

          <button
            id="settings-preferences-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'preferences'"
            [attr.aria-selected]="activeTab === 'preferences'"
            aria-controls="settings-preferences-panel"
            (click)="activeTab = 'preferences'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">person</span>
            <span>{{ 'settings.tab.preferences' | t }}</span>
          </button>

          <button
            *ngIf="canManageSystemSettings()"
            id="settings-languages-tab"
            type="button"
            role="tab"
            class="status-tab"
            [class.active]="activeTab === 'languages'"
            [attr.aria-selected]="activeTab === 'languages'"
            aria-controls="settings-languages-panel"
            (click)="activeTab = 'languages'"
          >
            <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">language</span>
            <span>{{ 'settings.yazyki_i_lokalizaciya' | t }}</span>
          </button>

        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 1: GENERAL SYSTEM SETTINGS -->
      <!-- =================================================================== -->
      <div id="settings-general-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-general-tab" *ngIf="activeTab === 'general' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">corporate_fare</span>
              <div>
                <h3 class="card-title">{{ 'settings.konfiguraciya_kompanii' | t }}</h3>
                <p class="card-desc">{{ 'settings.globalnye_parametry_dlya_vseh_sotrudnikov_organi' | t }}</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group full-width">
              <label class="form-label" for="settings-company-name">{{ 'settings.company_name' | t }}</label>
              <input
                id="settings-company-name"
                name="settingsCompanyName"
                type="text"
                class="form-input"
                [(ngModel)]="systemSettings()['system.company_name']"
                placeholder="SmartupCMS"
              />
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-default-language">{{ 'settings.default_language' | t }}</label>
              <select id="settings-default-language" name="settingsDefaultLanguage" class="form-select" [(ngModel)]="systemSettings()['system.default_language']">
                <option *ngFor="let lang of i18n.languages()" [value]="lang.code">
                  {{ lang.name }} ({{ lang.code.toUpperCase() }})
                </option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-default-timezone">{{ 'settings.default_timezone' | t }}</label>
              <select id="settings-default-timezone" name="settingsDefaultTimezone" class="form-select" [(ngModel)]="systemSettings()['system.default_timezone']">
                <option value="Asia/Tashkent">Asia/Tashkent (UTC+5)</option>
                <option value="Asia/Almaty">Asia/Almaty (UTC+5)</option>
                <option value="Europe/Moscow">Europe/Moscow (UTC+3)</option>
                <option value="UTC">UTC (GMT+0)</option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-date-format">{{ 'settings.date_format' | t }}</label>
              <select id="settings-date-format" name="settingsDateFormat" class="form-select" [(ngModel)]="systemSettings()['system.date_format']">
                <option value="dd.MM.yyyy HH:mm">29.08.2026 14:30 (dd.MM.yyyy HH:mm)</option>
                <option value="yyyy-MM-dd HH:mm">2026-08-29 14:30 (yyyy-MM-dd HH:mm)</option>
                <option value="MM/dd/yyyy hh:mm a">08/29/2026 02:30 PM (MM/dd/yyyy)</option>
              </select>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 2: SECURITY POLICIES -->
      <!-- =================================================================== -->
      <div id="settings-security-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-security-tab" *ngIf="activeTab === 'security' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">lock</span>
              <div>
                <h3 class="card-title">{{ 'settings.politiki_bezopasnosti_i_avtorizacii' | t }}</h3>
                <p class="card-desc">{{ 'settings.trebovaniya_k_parolyam_2fa_i_veb_sessiyam' | t }}</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label" for="settings-password-length">{{ 'settings.min_password_len' | t }}</label>
              <input
                id="settings-password-length"
                name="settingsPasswordLength"
                type="number"
                min="8"
                max="32"
                class="form-input"
                aria-describedby="settings-password-length-hint"
                [(ngModel)]="systemSettings()['security.min_password_length']"
              />
              <span id="settings-password-length-hint" class="hint-text">{{ 'settings.rekomenduetsya_ne_menee_10_simvolov' | t }}</span>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-session-lifetime">{{ 'settings.session_lifetime' | t }}</label>
              <input
                id="settings-session-lifetime"
                name="settingsSessionLifetime"
                type="number"
                min="1"
                max="8760"
                class="form-input"
                aria-describedby="settings-session-lifetime-hint"
                [(ngModel)]="systemSettings()['security.session_lifetime_hours']"
              />
              <span id="settings-session-lifetime-hint" class="hint-text">{{ 'settings.po_umolchaniyu_720_chasov_30_dney' | t }}</span>
            </div>

            <div class="form-group full-width">
              <div class="toggle-row">
                <div class="toggle-info">
                  <span id="settings-require-2fa-label" class="toggle-title">{{ 'settings.require_2fa' | t }}</span>
                  <span class="toggle-desc">{{ 'settings.prinuditelno_trebovat_dvuhfaktornuyu_autentifika' | t }}</span>
                </div>
                <label class="switch-toggle">
                  <input
                    id="settings-require-2fa"
                    name="settingsRequire2fa"
                    type="checkbox"
                    aria-labelledby="settings-require-2fa-label"
                    [checked]="systemSettings()['security.require_2fa'] === 'true'"
                    (change)="toggleRequire2fa($event)"
                  />
                  <span class="toggle-slider" aria-hidden="true"></span>
                </label>
              </div>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 3: STORAGE QUOTAS -->
      <!-- =================================================================== -->
      <div id="settings-storage-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-storage-tab" *ngIf="activeTab === 'storage' && canManageSystemSettings()">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">folder_shared</span>
              <div>
                <h3 class="card-title">{{ 'settings.parametry_hranilischa_i_kvoty' | t }}</h3>
                <p class="card-desc">{{ 'settings.limity_diskovogo_prostranstva_dlya_novyh_sotrudn' | t }}</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label" for="settings-user-quota">{{ 'settings.default_user_quota' | t }}</label>
              <input
                id="settings-user-quota"
                name="settingsUserQuota"
                type="number"
                min="100"
                max="102400"
                class="form-input"
                aria-describedby="settings-user-quota-hint"
                [(ngModel)]="systemSettings()['storage.default_user_quota_mb']"
              />
              <span id="settings-user-quota-hint" class="hint-text">{{ 'settings.1024_mb_1_gb_na_kazhdogo_sotrudnika' | t }}</span>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveSystemSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 4: USER PREFERENCES -->
      <!-- =================================================================== -->
      <div id="settings-preferences-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-preferences-tab" *ngIf="activeTab === 'preferences'">
        <div class="settings-card">
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">palette</span>
              <div>
                <h3 class="card-title">{{ 'settings.personalnye_predpochteniya' | t }}</h3>
                <p class="card-desc">{{ 'settings.nastroyki_vneshnego_vida_i_yazyka_dlya_vashey_uc' | t }}</p>
              </div>
            </div>
          </div>

          <div class="form-grid">
            <div class="form-group">
              <label class="form-label" for="settings-interface-language">{{ 'settings.yazyk_interfeysa' | t }}</label>
              <select id="settings-interface-language" name="settingsInterfaceLanguage" class="form-select" [ngModel]="i18n.currentLang()" (ngModelChange)="changePersonalLang($event)">
                <option *ngFor="let lang of i18n.languages()" [value]="lang.code">
                  {{ lang.name }} ({{ lang.code.toUpperCase() }})
                </option>
              </select>
            </div>

            <div class="form-group">
              <label class="form-label" for="settings-theme">{{ 'settings.theme' | t }}</label>
              <select id="settings-theme" name="settingsTheme" class="form-select" [(ngModel)]="userSettings()['user.theme']">
                <option value="dark">{{ 'settings.temnaya_dark_premium' | t }}</option>
                <option value="light">{{ 'settings.svetlaya_light_clean' | t }}</option>
                <option value="system">{{ 'settings.sistemnaya_tema' | t }}</option>
              </select>
            </div>

            <div class="form-group full-width">
              <div class="toggle-row">
                <div class="toggle-info">
                  <span id="settings-notification-sound-label" class="toggle-title">{{ 'settings.notifications_sound' | t }}</span>
                  <span class="toggle-desc">{{ 'settings.vosproizvodit_zvukovoy_signal_pri_poluchenii_nov' | t }}</span>
                </div>
                <label class="switch-toggle">
                  <input
                    id="settings-notification-sound"
                    name="settingsNotificationSound"
                    type="checkbox"
                    aria-labelledby="settings-notification-sound-label"
                    [checked]="userSettings()['user.notifications_sound'] !== 'false'"
                    (change)="toggleSound($event)"
                  />
                  <span class="toggle-slider" aria-hidden="true"></span>
                </label>
              </div>
            </div>
          </div>

          <div class="card-footer-actions">
            <ui-button [loading]="isSaving()" (onClick)="saveUserSettings()">
              {{ 'common.save' | t }}
            </ui-button>
          </div>
        </div>
      </div>

      <!-- =================================================================== -->
      <!-- TAB 6: LANGUAGES & TRANSLATIONS -->
      <!-- =================================================================== -->
      <div id="settings-languages-panel" class="tab-content" role="tabpanel" aria-labelledby="settings-languages-tab" *ngIf="activeTab === 'languages' && canManageSystemSettings()">
        <app-language-editor
          *ngIf="editingLanguageCode() as code"
          [languageCode]="code"
          (closed)="editingLanguageCode.set(null)"
          (saved)="onLanguageSaved()"
        />

        <div class="settings-card" *ngIf="!editingLanguageCode()">
          <div class="legacy-import" *ngIf="legacyLanguageCount() > 0" role="status">
            <div>
              <strong>{{ 'settings.legacy_packages_found' | t:{count: legacyLanguageCount()} }}</strong>
              <span>{{ 'settings.perenesite_ih_v_obschee_servernoe_hranilische_ch' | t }}</span>
            </div>
            <button
              id="migrate-legacy-languages"
              type="button"
              class="btn btn-secondary"
              *ngIf="canUpdateSystemSettings()"
              [disabled]="isMigratingLegacyLanguages()"
              (click)="migrateLegacyLanguages()"
            >
              {{ (isMigratingLegacyLanguages() ? 'settings.migrating' : 'settings.migrate') | t }}
            </button>
          </div>
          <div class="card-header-bar">
            <div class="card-title-group">
              <span class="material-symbols-outlined card-icon" aria-hidden="true">translate</span>
              <div>
                <h3 class="card-title">{{ 'settings.upravlenie_yazykovymi_paketami_i_lokalizaciey' | t }}</h3>
                <p class="card-desc">{{ 'settings.dinamicheskoe_dobavlenie_novyh_yazykov_i_import_' | t }}</p>
              </div>
            </div>
            <button type="button" class="btn btn-primary" *ngIf="canUpdateSystemSettings()" (click)="openAddLangModal()">
              <span class="material-symbols-outlined" aria-hidden="true">add</span>
              <span>{{ 'settings.dobavit_yazyk' | t }}</span>
            </button>
          </div>

          <div class="table-card">
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>{{ 'settings.kod' | t }}</th>
                    <th>{{ 'settings.nazvanie_yazyka' | t }}</th>
                    <th>{{ 'settings.tip' | t }}</th>
                    <th>{{ 'settings.gotovnost' | t }}</th>
                    <th>{{ 'common.status' | t }}</th>
                    <th style="text-align: right;">{{ 'common.actions' | t }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr *ngFor="let lang of i18n.languages()">
                    <td><span class="badge badge-neutral mono">{{ lang.code.toUpperCase() }}</span></td>
                    <td class="font-medium">{{ lang.name }}</td>
                    <td>
                      <span class="badge" [class.badge-active]="lang.builtin" [class.badge-info]="!lang.builtin">
                        {{ (lang.builtin ? 'settings.builtin' : 'settings.custom') | t }}
                      </span>
                    </td>
                    <td>
                      <div class="language-coverage" [attr.aria-label]="'settings.coverage_percent' | t:{coverage: lang.coverage}">
                        <span class="coverage-track"><span [style.width.%]="lang.coverage"></span></span>
                        <span>{{ lang.translated }}/{{ lang.total }} · {{ lang.coverage }}%</span>
                      </div>
                    </td>
                    <td>
                      <span class="badge badge-active" *ngIf="i18n.currentLang() === lang.code">{{ 'settings.tekuschiy_aktivnyy' | t }}</span>
                      <span class="badge badge-neutral" *ngIf="i18n.currentLang() !== lang.code">{{ 'settings.dostupen' | t }}</span>
                    </td>
                    <td style="text-align: right;">
                      <div class="table-actions-right">
                        <button type="button" class="btn btn-secondary btn-sm" [attr.data-testid]="'edit-language-' + lang.code" (click)="openLanguageEditor(lang.code)">
                          <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                          <span>{{ 'common.edit' | t }}</span>
                        </button>
                        <button type="button" class="btn btn-secondary btn-sm" (click)="exportLangJson(lang.code)" [title]="'settings.eksportirovat_json' | t">
                          <span class="material-symbols-outlined" aria-hidden="true">download</span>
                          <span>JSON</span>
                        </button>
                        <button type="button" class="btn btn-primary btn-sm" [attr.data-testid]="'switch-language-' + lang.code" *ngIf="i18n.currentLang() !== lang.code" (click)="switchLanguage(lang.code)">
                          <span>{{ 'settings.pereklyuchitsya' | t }}</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

    </div>

    <!-- Modal: Add New Custom Language -->
    <ui-modal
      *ngIf="isAddLangModalOpen()"
      [title]="'settings.dobavlenie_novogo_yazyka' | t"
      [ariaLabel]="'settings.dobavlenie_novogo_yazyka' | t"
      (close)="isAddLangModalOpen.set(false)"
    >
      <div class="form-grid">
        <div class="form-group">
          <label class="form-label" for="new-lang-code">{{ 'settings.kod_yazyka_iso_639_1' | t }}</label>
          <input id="new-lang-code" type="text" class="form-input" [(ngModel)]="newLangCode" placeholder="kk, ky, tg, de, tr" maxlength="10">
        </div>
        <div class="form-group">
          <label class="form-label" for="new-lang-name">{{ 'settings.nazvanie_yazyka' | t }}</label>
          <input id="new-lang-name" type="text" class="form-input" [(ngModel)]="newLangName" [placeholder]="'settings.aza_sha_deutsch_etc' | t">
        </div>
        <div class="form-group full-width">
          <label class="form-label" for="new-lang-json">{{ 'settings.json_slovar_perevodov_opcionalno' | t }}</label>
          <textarea id="new-lang-json" class="form-input mono" rows="6" [(ngModel)]="newLangJson" [placeholder]="'settings.translation_json_example' | t"></textarea>
        </div>
      </div>
      <div modal-footer class="modal-footer-btns">
        <button type="button" class="btn btn-secondary" (click)="isAddLangModalOpen.set(false)">{{ 'common.cancel' | t }}</button>
        <button type="button" class="btn btn-primary" (click)="saveNewLanguage()" [disabled]="!newLangCode || !newLangName">{{ 'settings.sohranit_yazyk' | t }}</button>
      </div>
    </ui-modal>

  `,
  styles: [`
    .settings-page {
      display: flex;
      flex-direction: column;
      gap: 20px;
      padding: 0;
      max-width: 1000px;
      margin: 0 auto;
    }

    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .header-left {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .view-title {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-main);
      margin: 0;
    }

    .view-subtitle {
      font-size: 13px;
      color: var(--text-light);
    }

    .icon-refresh-btn {
      width: 36px;
      height: 36px;
      border-radius: 8px;
      border: 1px solid var(--border-color);
      background: var(--bg-surface);
      color: var(--text-light);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .icon-refresh-btn:hover {
      background: var(--bg-hover);
      color: var(--text-main);
    }

    /* Tabs */
    .tabs-nav-bar {
      display: flex;
      border-bottom: 1px solid var(--border-color);
    }

    .tab-buttons {
      display: flex;
      gap: 4px;
    }

    .nav-tab-btn {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 16px;
      border: none;
      background: transparent;
      color: var(--text-light);
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      transition: all 0.15s ease;
    }

    .nav-tab-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .nav-tab-btn.active {
      color: var(--primary-text);
      border-bottom-color: var(--primary);
    }

    /* Settings Card */
    .settings-card {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .card-header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--border-subtle);
      padding-bottom: 16px;
    }

    .card-title-group {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .card-icon {
      font-size: 28px;
      color: var(--primary-text);
    }

    .card-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .card-desc {
      font-size: 13px;
      color: var(--text-light);
      margin: 2px 0 0 0;
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 18px;
    }

    @media (max-width: 768px) {
      .form-grid {
        grid-template-columns: 1fr;
      }
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .form-group.full-width {
      grid-column: span 2;
    }

    @media (max-width: 768px) {
      .form-group.full-width {
        grid-column: span 1;
      }
    }

    .form-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
    }

    .form-input, .form-select {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 9px 12px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }

    .form-input:focus, .form-select:focus {
      border-color: var(--primary);
    }

    .form-select option {
      background: var(--bg-surface);
      color: var(--text-main);
    }

    .hint-text {
      font-size: 11px;
      color: var(--text-light);
    }

    /* Toggle Switch */
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 16px;
      background: var(--bg-hover);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
    }

    .toggle-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .toggle-title {
      font-size: 14px;
      font-weight: 500;
      color: var(--text-main);
    }

    .toggle-desc {
      font-size: 12px;
      color: var(--text-light);
    }

    .switch-toggle {
      position: relative;
      display: inline-block;
      width: 44px;
      height: 24px;
    }

    .switch-toggle input {
      opacity: 0;
      width: 0;
      height: 0;
    }

    .toggle-slider {
      position: absolute;
      cursor: pointer;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background-color: var(--bg-active);
      transition: .2s;
      border-radius: 24px;
    }

    .toggle-slider:before {
      position: absolute;
      content: "";
      height: 18px;
      width: 18px;
      left: 3px;
      bottom: 3px;
      background-color: var(--text-inverse);
      transition: .2s;
      border-radius: 50%;
    }

    input:checked + .toggle-slider {
      background-color: var(--primary);
    }

    input:checked + .toggle-slider:before {
      transform: translateX(20px);
    }

    .card-footer-actions {
      display: flex;
      justify-content: flex-end;
      padding-top: 12px;
      border-top: 1px solid var(--border-subtle);
    }

    .language-coverage {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 112px;
      color: var(--text-light);
      font-size: 11px;
    }

    .coverage-track {
      display: block;
      width: 100%;
      height: 5px;
      overflow: hidden;
      border-radius: 999px;
      background: var(--bg-active);
    }

    .coverage-track > span {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: var(--primary);
    }
    .legacy-import {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      margin: 16px 20px 0;
      padding: 12px 14px;
      border: 1px solid var(--warning, #d59b00);
      border-radius: 9px;
      color: var(--text-main);
      background: var(--warning-soft, #fff8e1);
    }
    .legacy-import > div { display: grid; gap: 3px; }
    .legacy-import span { color: var(--text-light); font-size: 12px; }
    @media (max-width: 680px) {
      .legacy-import { align-items: stretch; flex-direction: column; }
    }

  `]
})
export class SettingsComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  activeTab: 'general' | 'security' | 'storage' | 'preferences' | 'languages' = 'general';

  readonly systemSettings = signal<Record<string, string>>({});
  readonly userSettings = signal<Record<string, string>>({});
  readonly isSaving = signal<boolean>(false);

  // Languages Management
  readonly isAddLangModalOpen = signal<boolean>(false);
  readonly editingLanguageCode = signal<string | null>(null);
  readonly legacyLanguageCount = signal(0);
  readonly isMigratingLegacyLanguages = signal(false);
  newLangCode = '';
  newLangName = '';
  newLangJson = '';

  constructor(
    private api: ApiService,
    private toast: ToastService,
    public i18n: I18nService,
    private permService: PermissionService
  ) {}

  ngOnInit() {
    if (!this.canManageSystemSettings()) {
      this.activeTab = 'preferences';
    }
    this.legacyLanguageCount.set(Object.keys(this.readLegacyLanguages()).length);
    this.loadAllSettings();
  }

  canManageSystemSettings(): boolean {
    return this.permService.hasPermission('platform.settings', 'view') ||
           this.permService.hasPermission('platform.settings', 'update') ||
           this.permService.hasPermission('settings', 'view') ||
           this.permService.hasPermission('settings', 'update');
  }

  canUpdateSystemSettings(): boolean {
    return this.permService.hasPermission('platform.settings', 'update') ||
           this.permService.hasPermission('settings', 'update');
  }

  loadAllSettings() {
    if (this.canManageSystemSettings()) {
      this.api.get<Record<string, string>>('/settings/system').subscribe({
        next: res => this.systemSettings.set({ ...res })
      });

    }

    this.api.get<Record<string, string>>('/settings/user').subscribe({
      next: res => this.userSettings.set({ ...res })
    });
  }

  saveSystemSettings() {
    this.isSaving.set(true);
    this.api.patch('/settings/system', this.systemSettings()).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false)
    });
  }

  saveUserSettings() {
    this.isSaving.set(true);
    this.api.patch('/settings/user', this.userSettings()).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.toast.success(this.i18n.translate('common.saved'));
      },
      error: () => this.isSaving.set(false)
    });
  }

  changePersonalLang(lang: string) {
    this.i18n.setLanguage(lang).subscribe({
      next: () => this.userSettings.update(settings => ({ ...settings, 'user.language': lang }))
    });
  }

  toggleRequire2fa(event: any) {
    this.systemSettings.update(settings => ({
      ...settings,
      'security.require_2fa': event.target.checked ? 'true' : 'false'
    }));
  }

  toggleSound(event: any) {
    this.userSettings.update(settings => ({
      ...settings,
      'user.notifications_sound': event.target.checked ? 'true' : 'false'
    }));
  }

  // Language management methods
  openAddLangModal() {
    this.newLangCode = '';
    this.newLangName = '';
    this.newLangJson = '';
    this.isAddLangModalOpen.set(true);
  }

  openLanguageEditor(code: string) {
    this.editingLanguageCode.set(code);
  }

  onLanguageSaved() {
    this.i18n.refreshLanguages().subscribe();
  }

  migrateLegacyLanguages(): void {
    const legacyLanguages = this.readLegacyLanguages();
    const entries = Object.entries(legacyLanguages);
    if (entries.length === 0 || !this.canUpdateSystemSettings()) return;
    if (!window.confirm(
      this.uiI18n.translate('settings.confirm_legacy_migration', { count: entries.length })
    )) return;

    this.isMigratingLegacyLanguages.set(true);
    this.api.get<TranslationEditor>('/i18n/admin/languages/ru/translations').pipe(
      switchMap(russianEditor => {
        const knownKeys = new Set(russianEditor.entries.map(entry => entry.key));
        return from(entries).pipe(
          concatMap(([code, legacy]) => this.migrateLegacyLanguage(code, legacy, knownKeys)),
          toArray()
        );
      }),
      switchMap(() => this.i18n.refreshLanguages()),
      finalize(() => this.isMigratingLegacyLanguages.set(false))
    ).subscribe({
      next: () => {
        localStorage.removeItem('dwh_custom_languages');
        this.legacyLanguageCount.set(0);
        this.toast.success(this.uiI18n.translate('settings.lokalnye_yazykovye_pakety_pereneseny_v_servernoe'));
      },
      error: () => this.toast.error(
        this.uiI18n.translate('settings.ne_udalos_perenesti_yazykovye_pakety_lokalnaya_k')
      )
    });
  }

  saveNewLanguage() {
    if (!this.newLangCode || !this.newLangName) return;

    let dict: Record<string, string> = {};
    if (this.newLangJson) {
      try {
        dict = JSON.parse(this.newLangJson);
      } catch (e) {
        this.toast.error(this.uiI18n.translate('settings.nevernyy_format_json_slovarya'));
        return;
      }
    }

    const name = this.newLangName;
    this.i18n.registerLanguage(this.newLangCode, name, dict).subscribe({
      next: () => {
        this.isAddLangModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('settings.language_added', { name }));
      }
    });
  }

  switchLanguage(lang: string) {
    this.i18n.setLanguage(lang).subscribe();
  }

  exportLangJson(langCode: string) {
    this.api.get<Record<string, string>>(`/i18n/${langCode}`).subscribe(dictionary => {
      const blob = new Blob([JSON.stringify(dictionary, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `smartupcms-translations-${langCode}.json`;
      a.click();
      URL.revokeObjectURL(url);
      this.toast.info(this.uiI18n.translate('settings.dictionary_exported', {
        code: langCode.toUpperCase()
      }));
    });
  }

  private migrateLegacyLanguage(
    code: string,
    legacy: LegacyLanguage,
    knownKeys: Set<string>
  ): Observable<unknown> {
    const translations = this.filterKnownTranslations(legacy.dict, knownKeys);
    const existing = this.i18n.languages().some(language => language.code === code);
    if (!existing) {
      return this.i18n.registerLanguage(code, legacy.name, translations);
    }

    return this.api.get<TranslationEditor>(`/i18n/admin/languages/${code}/translations`).pipe(
      switchMap(editor => {
        const merged: TranslationDictionary = {};
        for (const entry of editor.entries) {
          if (entry.overrideValue) merged[entry.key] = entry.overrideValue;
        }
        Object.assign(merged, translations);
        return this.api.put(`/i18n/admin/languages/${code}/translations`, {
          expectedRevision: editor.language.revision,
          translations: merged
        });
      })
    );
  }

  private filterKnownTranslations(
    dictionary: TranslationDictionary,
    knownKeys: Set<string>
  ): TranslationDictionary {
    return Object.fromEntries(Object.entries(dictionary).filter(([key, value]) =>
      knownKeys.has(key) && typeof value === 'string' && value.trim().length > 0 && value.length <= 4000
    ));
  }

  private readLegacyLanguages(): Record<string, LegacyLanguage> {
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem('dwh_custom_languages') ?? '{}');
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') return {};
      const result: Record<string, LegacyLanguage> = {};
      for (const [rawCode, rawEntry] of Object.entries(parsed)) {
        const code = rawCode.trim().toLowerCase();
        if (!/^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$/.test(code)
            || !rawEntry || Array.isArray(rawEntry) || typeof rawEntry !== 'object') continue;
        const entry = rawEntry as { name?: unknown; dict?: unknown };
        if (typeof entry.name !== 'string' || !entry.name.trim()
            || !entry.dict || Array.isArray(entry.dict) || typeof entry.dict !== 'object') continue;
        const dict = Object.fromEntries(Object.entries(entry.dict).filter((pair): pair is [string, string] =>
          typeof pair[1] === 'string'
        ));
        result[code] = { name: entry.name.trim(), dict };
      }
      return result;
    } catch {
      return {};
    }
  }

}

interface LegacyLanguage {
  name: string;
  dict: TranslationDictionary;
}
