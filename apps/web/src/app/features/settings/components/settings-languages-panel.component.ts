import {
  ChangeDetectionStrategy,
  Component,
  TemplateRef,
  computed,
  inject,
  viewChild,
  input,
  output,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { LanguageInfo } from '@core/models/i18n.models';
import { SMTTableComponent } from '@shared/ui-kit/components/table/table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { LanguageEditorComponent } from '../language-editor.component';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTTextareaComponent, SMTTextareaValueAccessor } from '@shared/ui-kit/components/forms/textarea';

@Component({
  selector: 'app-settings-languages-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTTextareaComponent,
    SMTTextareaValueAccessor,
    SMTInputComponent,
    SMTInputValueAccessor,
    FormsModule,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    LanguageEditorComponent,
    SMTTableComponent,
  ],
  templateUrl: './settings-languages-panel.component.html',
  styles: [
    `
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
      .legacy-import > div {
        display: grid;
        gap: 3px;
      }
      .legacy-import span {
        color: var(--text-muted);
        font-size: 12px;
      }

      .table-card {
        background: var(--bg-surface);
        border: 1px solid var(--border-color);
        border-radius: 8px;
        overflow: hidden;
      }
      .table-scroll {
        overflow-x: auto;
      }
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
      tbody tr:last-child td {
        border-bottom: none;
      }
      tbody tr:hover td {
        background: var(--bg-hover);
      }

      .font-medium {
        font-weight: 500;
      }
      .mono {
        font-family: monospace;
      }
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
      @media (max-width: 680px) {
        .legacy-import {
          align-items: stretch;
          flex-direction: column;
        }
      }
    `,
  ],
})
export class SettingsLanguagesPanelComponent {
  private readonly i18n = inject(I18nService);

  readonly isMigratingLegacyLanguages = input(false);
  readonly isAddingLang = input(false);
  readonly newLangCode = input('');
  readonly newLangName = input('');
  readonly newLangJson = input('');

  readonly canUpdateSystemSettings = input(false);
  readonly editingLanguageCode = input<string | null>(null);
  readonly legacyLanguageCount = input(0);
  readonly languages = input<LanguageInfo[]>([]);
  readonly currentLang = input('');
  readonly isAddLangModalOpen = input(false);

  readonly openLanguageEditor = output<string>();
  readonly closeLanguageEditor = output<void>();
  readonly languageSaved = output<void>();
  readonly migrateLegacyLanguages = output<void>();
  readonly openAddLangModal = output<void>();
  readonly closeAddLangModal = output<void>();
  readonly saveNewLanguage = output<void>();
  readonly exportLangJson = output<string>();
  readonly switchLanguage = output<string>();
  readonly newLangCodeChange = output<string>();
  readonly newLangNameChange = output<string>();
  readonly newLangJsonChange = output<string>();

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
        code: {
          header: header(this.i18n.translate('settings.kod')),
          content: { type: 'templateRef', value: this.codeCell },
          width: '88px',
        },
        name: {
          header: header(this.i18n.translate('settings.nazvanie_yazyka')),
          content: { type: 'primitive', value: (lang) => lang.name },
          width: share,
        },
        type: {
          header: header(this.i18n.translate('settings.tip')),
          content: { type: 'templateRef', value: this.typeCell },
          width: share,
        },
        coverage: {
          header: header(this.i18n.translate('settings.gotovnost')),
          content: { type: 'templateRef', value: this.coverageCell },
          width: share,
        },
        status: {
          header: header(this.i18n.translate('common.status')),
          content: { type: 'templateRef', value: this.statusCell },
          width: '160px',
        },
        actions: {
          header: header(this.i18n.translate('common.actions')),
          content: { type: 'templateRef', value: this.actionsCell },
          align: 'right',
          width: '280px',
        },
      },
    };
  });
}
