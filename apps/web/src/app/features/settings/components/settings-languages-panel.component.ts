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
  styleUrl: './settings-languages-panel.component.css',
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
