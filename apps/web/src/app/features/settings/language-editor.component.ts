import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  computed,
  signal,
  inject,
  input,
  linkedSignal,
  output,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { finalize, tap } from 'rxjs';
import { LanguageInfo, TranslationDictionary, TranslationEditor, TranslationEntry } from '@core/models/i18n.models';
import { SettingsApi } from './settings.api';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-language-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTSwitchComponent, SMTInputComponent, SMTControlComponent, TranslatePipe],
  templateUrl: './language-editor.component.html',
  styleUrl: './language-editor.component.css',
})
export class LanguageEditorComponent {
  private readonly settingsApi = inject(SettingsApi);
  private readonly i18n = inject(I18nService);
  private readonly permissionService = inject(PermissionService);
  private readonly toast = inject(ToastService);

  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly languageCode = input.required<string>();

  readonly closed = output<void>();
  readonly saved = output<string>();

  /** The loaded editor; a save puts the saved package here without asking the server again. */
  readonly editor = linkedSignal<TranslationEditor | null>(() =>
    this.editorResource.hasValue() ? (this.editorResource.value() ?? null) : null,
  );
  readonly isSaving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly missingOnly = signal(false);
  readonly draft = signal<TranslationDictionary>({});
  readonly initialDraft = signal<TranslationDictionary>({});
  readonly search = signal('');

  readonly isLoading = computed(() => this.editorResource.isLoading());
  /** Hidden while a retry runs. */
  readonly loadError = computed(() =>
    this.editorResource.error() !== undefined && !this.editorResource.isLoading()
      ? this.uiI18n.translate('settings.languages.editor_load_failed')
      : null,
  );

  readonly dirtyCount = computed(() => {
    const before = this.initialDraft();
    const after = this.draft();
    return Object.keys(after).filter((key) => after[key] !== before[key]).length;
  });

  readonly filteredEntries = computed(() => {
    const model = this.editor();
    if (!model) return [];
    const query = this.search().trim().toLocaleLowerCase();
    return model.entries.filter((entry) => {
      if (this.missingOnly() && this.isTranslated(entry)) return false;
      if (!query) return true;
      return (
        entry.key.toLocaleLowerCase().includes(query) ||
        entry.russianValue.toLocaleLowerCase().includes(query) ||
        this.valueFor(entry.key).toLocaleLowerCase().includes(query)
      );
    });
  });

  /** Every arrival starts a clean draft. */
  private readonly editorResource = rxResource({
    params: this.languageCode,
    stream: ({ params }) => this.settingsApi.translations(params).pipe(tap((model) => this.resetDraft(model))),
  });

  readonly canEdit: boolean;

  constructor() {
    const permissionService = this.permissionService;

    this.canEdit = permissionService.hasPermission('md.settings', 'update');
  }

  load(): void {
    this.editorResource.reload();
  }

  resetDraft(model = this.editor()): void {
    if (!model) return;
    const values: TranslationDictionary = {};
    for (const entry of model.entries) {
      values[entry.key] = entry.overrideValue ?? entry.bundledValue ?? '';
    }
    this.initialDraft.set({ ...values });
    this.draft.set(values);
    this.saveError.set(null);
  }

  valueFor(key: string): string {
    return this.draft()[key] ?? '';
  }

  setValue(key: string, value: string): void {
    this.draft.update((current) => ({ ...current, [key]: value }));
    this.saveError.set(null);
  }

  isTranslated(entry: TranslationEntry): boolean {
    return this.languageCode() === 'ru' || this.valueFor(entry.key).trim().length > 0;
  }

  canReset(entry: TranslationEntry): boolean {
    return this.valueFor(entry.key) !== (entry.bundledValue ?? '');
  }

  resetValue(entry: TranslationEntry): void {
    this.setValue(entry.key, entry.bundledValue ?? '');
  }

  cancelChanges(): void {
    this.resetDraft();
  }

  save(): void {
    const model = this.editor();
    if (!model || !this.canEdit || this.dirtyCount() === 0) return;

    const languageCode = this.languageCode();
    if (languageCode === 'ru') {
      const emptyRussian = model.entries.find((entry) => !this.valueFor(entry.key).trim());
      if (emptyRussian) {
        this.toast.error(
          this.uiI18n.translate('settings.russian_translation_required', {
            key: emptyRussian.key,
          }),
        );
        return;
      }
    }

    const translations = this.buildOverrides(model);
    this.isSaving.set(true);
    this.saveError.set(null);
    this.settingsApi
      .saveTranslations(languageCode, model.language.revision, translations)
      .pipe(finalize(() => this.isSaving.set(false)))
      .subscribe({
        next: (language) => {
          this.applySavedModel(model, language, translations);
          this.i18n.refreshLanguage(this.languageCode()).subscribe({
            next: () => {
              this.toast.success(this.uiI18n.translate('settings.languages.translations_saved'));
              this.saved.emit(this.languageCode());
            },
            error: () => this.toast.error(this.uiI18n.translate('settings.languages.saved_refresh_failed')),
          });
        },
        error: (error) => {
          this.saveError.set(
            error?.status === 409
              ? this.uiI18n.translate('settings.languages.stale_conflict')
              : this.uiI18n.translate('settings.languages.save_failed'),
          );
        },
      });
  }

  importJson(content: string): boolean {
    const model = this.editor();
    if (!model) return false;
    try {
      const parsed: unknown = JSON.parse(content);
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        throw new Error('not an object');
      }
      const known = new Set(model.entries.map((entry) => entry.key));
      const imported: TranslationDictionary = {};
      for (const [key, value] of Object.entries(parsed)) {
        if (!known.has(key)) throw new Error(`unknown key: ${key}`);
        if (typeof value !== 'string' || value.length > 4000) throw new Error(`invalid value: ${key}`);
        imported[key] = value;
      }
      this.draft.update((current) => ({ ...current, ...imported }));
      this.toast.success(
        this.uiI18n.translate('settings.imported_rows', {
          count: Object.keys(imported).length,
        }),
      );
      return true;
    } catch {
      this.toast.error(this.uiI18n.translate('settings.languages.invalid_dictionary'));
      return false;
    }
  }

  async importFile(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    try {
      this.importJson(await file.text());
    } finally {
      input.value = '';
    }
  }

  exportDraft(): void {
    const model = this.editor();
    if (!model) return;
    const effective: TranslationDictionary = {};
    for (const entry of model.entries) {
      effective[entry.key] = this.valueFor(entry.key).trim() || entry.russianValue;
    }
    const blob = new Blob([JSON.stringify(effective, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `smartupcms-translations-${this.languageCode()}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  requestClose(): void {
    if (this.dirtyCount() === 0) {
      this.closed.emit();
      return;
    }
    this.modal
      .confirm({
        message: this.uiI18n.translate('settings.languages.unsaved_close_confirm'),
        destructive: true,
      })
      .subscribe((confirmed) => {
        if (confirmed) this.closed.emit();
      });
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.dirtyCount() === 0) return;
    event.preventDefault();
    event.returnValue = '';
  }

  inputId(key: string): string {
    return `translation-${key.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  }

  trackByKey(_: number, entry: TranslationEntry): string {
    return entry.key;
  }

  private buildOverrides(model: TranslationEditor): TranslationDictionary {
    const overrides: TranslationDictionary = {};
    for (const entry of model.entries) {
      const value = this.valueFor(entry.key);
      if (!value.trim()) continue;
      if (entry.bundledValue !== null && value === entry.bundledValue) continue;
      overrides[entry.key] = value;
    }
    return overrides;
  }

  private applySavedModel(model: TranslationEditor, language: LanguageInfo, overrides: TranslationDictionary): void {
    const entries = model.entries.map((entry) => {
      const overrideValue = overrides[entry.key] ?? null;
      const effectiveValue = overrideValue ?? entry.bundledValue ?? entry.russianValue;
      return {
        ...entry,
        overrideValue,
        effectiveValue,
        translated: this.languageCode() === 'ru' || overrideValue !== null || entry.bundledValue !== null,
      };
    });
    this.editor.set({ language, entries });
    this.resetDraft();
  }
}
