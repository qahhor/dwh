import {
  ChangeDetectionStrategy,
  Component,
  TemplateRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
  input,
  output,
} from '@angular/core';
import { FormField, form, maxLength, required, validate } from '@angular/forms/signals';

import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { LanguageInfo } from '@core/models/i18n.models';
import { SMTTableComponent } from '@shared/ui-kit/components/table/table.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { LanguageEditorComponent } from '../language-editor.component';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { discardChangesQuestion } from '@shared/ui/discard-changes';
import { NewLanguage } from '../settings.models';

/** What the person types in the "add language" dialog. */
interface AddLanguageForm {
  code: string;
  name: string;
  json: string;
}

const EMPTY_LANGUAGE: AddLanguageForm = { code: '', name: '', json: '' };
/** An ISO 639 code with optional subtags (`kk`, `uz-latn`), as the server accepts it. */
const LANGUAGE_CODE = /^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$/;

/** The dictionary text as an object of texts, or null when it is not one. */
export function parseDictionary(json: string): Record<string, string> | null {
  if (!json.trim()) return {};
  try {
    const parsed: unknown = JSON.parse(json);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    return Object.values(parsed).every((value) => typeof value === 'string')
      ? (parsed as Record<string, string>)
      : null;
  } catch {
    return null;
  }
}

@Component({
  selector: 'app-settings-languages-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormField,
    SMTControlComponent,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
    SMTTextareaComponent,
    SMTInputComponent,
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

  readonly isAddingLang = input(false);
  /** The server's refusal of the new language by field (`code`, `name`, `json`), already in words. */
  readonly addServerErrors = input<Readonly<Record<string, string>>>({});

  readonly canUpdateSystemSettings = input(false);
  readonly editingLanguageCode = input<string | null>(null);
  readonly languages = input<LanguageInfo[]>([]);
  readonly currentLang = input('');
  readonly isAddLangModalOpen = input(false);

  readonly openLanguageEditor = output<string>();
  readonly closeLanguageEditor = output<void>();
  readonly languageSaved = output<void>();
  readonly openAddLangModal = output<void>();
  readonly closeAddLangModal = output<void>();
  readonly saveNewLanguage = output<NewLanguage>();
  readonly exportLangJson = output<string>();
  readonly switchLanguage = output<string>();

  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly typeCell = viewChild.required<TemplateRef<unknown>>('typeCell');
  private readonly coverageCell = viewChild.required<TemplateRef<unknown>>('coverageCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');

  readonly addModel = signal<AddLanguageForm>({ ...EMPTY_LANGUAGE });

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
      ariaLabel: this.i18n.translate('settings.languages.title'),
      columnsOrder: ['code', 'name', 'type', 'coverage', 'status', 'actions'],
      columns: {
        code: {
          header: header(this.i18n.translate('settings.common.code')),
          content: { type: 'templateRef', value: this.codeCell },
          width: '88px',
        },
        name: {
          header: header(this.i18n.translate('settings.languages.language_name')),
          content: { type: 'primitive', value: (lang) => lang.name },
          width: share,
        },
        type: {
          header: header(this.i18n.translate('settings.common.type')),
          content: { type: 'templateRef', value: this.typeCell },
          width: share,
        },
        coverage: {
          header: header(this.i18n.translate('settings.languages.readiness')),
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

  /** Each opening starts blank and untouched. */
  private readonly resetOnOpen = effect(() => {
    if (this.isAddLangModalOpen()) untracked(() => this.addForm().reset({ ...EMPTY_LANGUAGE }));
  });

  /** Rules are always on; smt-control shows an error once a field is left or a save is tried. */
  readonly addForm = form(this.addModel, (path) => {
    required(path.code);
    maxLength(path.code, 10);
    validate(path.code, ({ value }) => {
      const code = value().trim().toLowerCase();
      return code === '' || LANGUAGE_CODE.test(code)
        ? null
        : { kind: 'lang_code', message: this.i18n.translate('settings.validation.lang_code') };
    });
    required(path.name);
    validate(path.json, ({ value }) =>
      parseDictionary(value()) === null
        ? { kind: 'json', message: this.i18n.translate('settings.languages.invalid_json_format') }
        : null,
    );
  });

  private readonly askDiscard = discardChangesQuestion();

  /** Enter in a field and the save button both land here; one request while a save runs. */
  submitAdd(): void {
    if (this.isAddingLang()) return;
    markSMTFormFieldsTouched(this.addForm);
    if (!this.addForm().valid()) return;
    const { code, name, json } = this.addModel();
    this.saveNewLanguage.emit({
      code: code.trim().toLowerCase(),
      name: name.trim(),
      dictionary: parseDictionary(json) ?? {},
    });
  }

  /** Escape, the backdrop, the cross and "Cancel" ask before typed values are dropped. */
  requestCloseAdd(): void {
    if (this.isAddingLang()) return;
    const { code, name, json } = this.addModel();
    const dirty = [code, name, json].some((value) => value.trim() !== '');
    this.askDiscard(dirty).subscribe((discard) => {
      if (discard) this.closeAddLangModal.emit();
    });
  }
}
