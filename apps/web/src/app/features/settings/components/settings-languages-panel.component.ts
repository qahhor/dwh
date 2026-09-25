import { Component, EventEmitter, Input, Output, TemplateRef, computed, inject, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { LanguageInfo } from '../../../core/models/i18n.models';
import { SMTTableComponent } from '../../../shared/ui-kit/components/table/table.component';
import { TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { LanguageEditorComponent } from '../language-editor.component';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-settings-languages-panel',
  standalone: true,
  imports: [SMTInputComponent, SMTInputValueAccessor, 
    CommonModule,
    FormsModule,
    TranslatePipe,
    UiButtonComponent,
    UiModalComponent,
    LanguageEditorComponent,
    SMTTableComponent
  ],
  template: `
    <app-language-editor
      *ngIf="editingLanguageCode"
      [languageCode]="editingLanguageCode"
      (closed)="closeLanguageEditor.emit()"
      (saved)="languageSaved.emit()"
    />

    <div class="settings-card" *ngIf="!editingLanguageCode">
      <div class="legacy-import" *ngIf="legacyLanguageCount > 0" role="status">
        <div>
          <strong>{{ 'settings.legacy_packages_found' | t:{count: legacyLanguageCount} }}</strong>
          <span>{{ 'settings.perenesite_ih_v_obschee_servernoe_hranilische_ch' | t }}</span>
        </div>
        <button
          id="migrate-legacy-languages"
          type="button"
          class="btn btn-secondary"
          *ngIf="canUpdateSystemSettings"
          [disabled]="isMigratingLegacyLanguages"
          (click)="migrateLegacyLanguages.emit()"
        >
          {{ (isMigratingLegacyLanguages ? 'settings.migrating' : 'settings.migrate') | t }}
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
        <button type="button" class="btn btn-primary" *ngIf="canUpdateSystemSettings" (click)="openAddLangModal.emit()">
          <span class="material-symbols-outlined" aria-hidden="true">add</span>
          <span>{{ 'settings.dobavit_yazyk' | t }}</span>
        </button>
      </div>

      <smt-table class="languages-table" [smtData]="languages" [smtConfig]="tableConfig()" />

      <ng-template #codeCell let-lang>
        <span class="badge badge-neutral mono">{{ lang.code.toUpperCase() }}</span>
      </ng-template>

      <ng-template #typeCell let-lang>
        <span class="badge" [class.badge-active]="lang.builtin" [class.badge-info]="!lang.builtin">
          {{ (lang.builtin ? 'settings.builtin' : 'settings.custom') | t }}
        </span>
      </ng-template>

      <ng-template #coverageCell let-lang>
        <!-- The column header names the cell and the text states the figures; the bar repeats them. -->
        <div class="language-coverage">
          <span class="coverage-track" aria-hidden="true"><span [style.width.%]="lang.coverage"></span></span>
          <span>{{ lang.translated }}/{{ lang.total }} · {{ lang.coverage }}%</span>
        </div>
      </ng-template>

      <ng-template #statusCell let-lang>
        @if (currentLang === lang.code) {
          <span class="badge badge-active">{{ 'settings.tekuschiy_aktivnyy' | t }}</span>
        } @else {
          <span class="badge badge-neutral">{{ 'settings.dostupen' | t }}</span>
        }
      </ng-template>

      <ng-template #actionsCell let-lang>
        <div class="table-actions-right">
          <button type="button" class="btn btn-secondary btn-sm" [attr.data-testid]="'edit-language-' + lang.code" (click)="openLanguageEditor.emit(lang.code)">
            <span class="material-symbols-outlined" aria-hidden="true">edit</span>
            <span>{{ 'common.edit' | t }}</span>
          </button>
          <button type="button" class="btn btn-secondary btn-sm" (click)="exportLangJson.emit(lang.code)" [title]="'settings.eksportirovat_json' | t">
            <span class="material-symbols-outlined" aria-hidden="true">download</span>
            <span>JSON</span>
          </button>
          @if (currentLang !== lang.code) {
            <button type="button" class="btn btn-primary btn-sm" [attr.data-testid]="'switch-language-' + lang.code" (click)="switchLanguage.emit(lang.code)">
              <span>{{ 'settings.pereklyuchitsya' | t }}</span>
            </button>
          }
        </div>
      </ng-template>
    </div>

    <!-- Modal: Add New Custom Language -->
    <ui-modal
      *ngIf="isAddLangModalOpen"
      [isOpen]="isAddLangModalOpen"
      [title]="'settings.dobavlenie_novogo_yazyka' | t"
      [ariaLabel]="'settings.dobavlenie_novogo_yazyka' | t"
      (close)="closeAddLangModal.emit()"
    >
      <div class="form-grid">
        <div class="form-group">
          <label class="form-label" for="new-lang-code">{{ 'settings.kod_yazyka_iso_639_1' | t }}</label>
          <smt-input
            smtFieldId="new-lang-code"
            [ngModel]="newLangCode"
            (ngModelChange)="newLangCodeChange.emit($event)"
            placeholder="kk, ky, tg, de, tr"
            [maxLength]="10" />
        </div>
        <div class="form-group">
          <label class="form-label" for="new-lang-name">{{ 'settings.nazvanie_yazyka' | t }}</label>
          <smt-input
            smtFieldId="new-lang-name"
            [ngModel]="newLangName"
            (ngModelChange)="newLangNameChange.emit($event)"
            [placeholder]="'settings.aza_sha_deutsch_etc' | t" />
        </div>
        <div class="form-group full-width">
          <label class="form-label" for="new-lang-json">{{ 'settings.json_slovar_perevodov_opcionalno' | t }}</label>
          <textarea
            id="new-lang-json"
            class="form-input mono"
            rows="6"
            [ngModel]="newLangJson"
            (ngModelChange)="newLangJsonChange.emit($event)"
            [placeholder]="'settings.translation_json_example' | t"
          ></textarea>
        </div>
      </div>
      <div modal-footer class="modal-footer-btns">
        <ui-button variant="secondary" (onClick)="closeAddLangModal.emit()" [disabled]="isAddingLang">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" [loading]="isAddingLang" (onClick)="saveNewLanguage.emit()" [disabled]="!newLangCode.trim() || !newLangName.trim()">{{ 'settings.sohranit_yazyk' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
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
    .legacy-import {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 14px;
      border: 1px solid var(--warning);
      border-radius: 9px;
      color: var(--text-main);
      background: var(--warning-bg);
    }
    .legacy-import > div { display: grid; gap: 3px; }
    .legacy-import span { color: var(--text-muted); font-size: 12px; }

    .table-card {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      overflow: hidden;
    }
    .table-scroll { overflow-x: auto; }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }
    th {
      text-align: left;
      padding: 10px 14px;
      background: var(--bg-hover);
      color: var(--text-light);
      font-weight: 600;
      font-size: 11px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      border-bottom: 1px solid var(--border-color);
    }
    td {
      padding: 12px 14px;
      border-bottom: 1px solid var(--border-subtle);
      color: var(--text-main);
    }
    tbody tr:last-child td { border-bottom: none; }
    tbody tr:hover td { background: var(--bg-hover); }

    .font-medium { font-weight: 500; }
    .mono { font-family: monospace; }
    .table-actions-right {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: flex-end;
      gap: 6px;
      white-space: nowrap;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      padding: 3px 8px;
      font-size: 11px;
      font-weight: 600;
      border-radius: var(--radius-xs);
    }
    .badge-active {
      background-color: var(--success-bg);
      color: var(--success-text);
    }
    .badge-neutral {
      background-color: var(--bg-active);
      color: var(--text-muted);
    }
    .badge-info {
      background-color: var(--primary-subtle);
      color: var(--primary-text);
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
    .form-input {
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 9px 12px;
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }
    .form-input:focus {
      border-color: var(--primary);
    }
    .modal-footer-btns {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
    .btn {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 8px 14px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      border: 1px solid transparent;
      transition: all 0.15s ease;
    }
    .btn-sm {
      padding: 4px 8px;
      font-size: 12px;
      border-radius: 6px;
    }
    .btn-primary {
      background: var(--primary);
      color: var(--on-primary);
    }
    .btn-primary:hover {
      background: var(--primary-hover);
    }
    .btn-secondary {
      background: var(--bg-surface);
      border-color: var(--border-color);
      color: var(--text-main);
    }
    .btn-secondary:hover {
      background: var(--bg-hover);
    }
    @media (max-width: 680px) {
      .legacy-import { align-items: stretch; flex-direction: column; }
    }
  `]
})
export class SettingsLanguagesPanelComponent {
  private readonly i18n = inject(I18nService);

  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly coverageCell = viewChild.required<TemplateRef<unknown>>('coverageCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  /**
   * Headers read the dictionary signal, so they follow a language switch.
   * Each key is passed to translate() as a literal so the localization audit
   * can see it; a helper taking the key as a variable would hide a typo.
   */
  protected readonly tableConfig = computed<TableConfig<LanguageInfo>>(() => {
    const header = (text: string) => ({ type: 'primitive' as const, value: text });
    // Every row is its own grid, so a content-sized track (max-content, fr)
    // would size differently per row and misalign the columns. The code and
    // status and actions tracks are fixed; the three text columns share the
    // rest. The action buttons wrap rather than clip when their labels are long.
    const share = 'max(120px, calc((100% - 528px) / 3))';
    return {
      trackBy: (_: number, lang: LanguageInfo) => lang.code,
      layout: 'fit',
      ariaLabel: this.i18n.translate('settings.upravlenie_yazykovymi_paketami_i_lokalizaciey'),
      columnsOrder: ['code', 'name', 'type', 'coverage', 'status', 'actions'],
      columns: {
        code: { header: header(this.i18n.translate('settings.kod')), content: { type: 'templateRef', value: this.codeCell }, width: '88px' },
        name: { header: header(this.i18n.translate('settings.nazvanie_yazyka')), content: { type: 'primitive', value: lang => lang.name }, width: share },
        type: { header: header(this.i18n.translate('settings.tip')), content: { type: 'templateRef', value: this.typeCell }, width: share },
        coverage: { header: header(this.i18n.translate('settings.gotovnost')), content: { type: 'templateRef', value: this.coverageCell }, width: share },
        status: { header: header(this.i18n.translate('common.status')), content: { type: 'templateRef', value: this.statusCell }, width: '160px' },
        actions: { header: header(this.i18n.translate('common.actions')), content: { type: 'templateRef', value: this.actionsCell }, align: 'right', width: '280px' },
      },
    };
  });

  @Input() canUpdateSystemSettings = false;
  @Input() editingLanguageCode: string | null = null;
  @Input() legacyLanguageCount = 0;
  @Input() isMigratingLegacyLanguages = false;
  @Input() languages: LanguageInfo[] = [];
  @Input() currentLang = '';
  @Input() isAddLangModalOpen = false;
  @Input() isAddingLang = false;
  @Input() newLangCode = '';
  @Input() newLangName = '';
  @Input() newLangJson = '';

  @Output() openLanguageEditor = new EventEmitter<string>();
  @Output() closeLanguageEditor = new EventEmitter<void>();
  @Output() languageSaved = new EventEmitter<void>();
  @Output() migrateLegacyLanguages = new EventEmitter<void>();
  @Output() openAddLangModal = new EventEmitter<void>();
  @Output() closeAddLangModal = new EventEmitter<void>();
  @Output() saveNewLanguage = new EventEmitter<void>();
  @Output() exportLangJson = new EventEmitter<string>();
  @Output() switchLanguage = new EventEmitter<string>();
  @Output() newLangCodeChange = new EventEmitter<string>();
  @Output() newLangNameChange = new EventEmitter<string>();
  @Output() newLangJsonChange = new EventEmitter<string>();
}
