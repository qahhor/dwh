import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  OnInit,
  computed,
  signal,
  inject,
  input,
  output,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { LanguageInfo, TranslationDictionary, TranslationEditor, TranslationEntry } from '@core/models/i18n.models';
import { SettingsApi } from './settings.api';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTSwitchComponent } from '@shared/ui-kit/components/forms/switch';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';

@Component({
  selector: 'app-language-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTSwitchComponent, SMTInputComponent, SMTInputValueAccessor, TranslatePipe, FormsModule],
  templateUrl: './language-editor.component.html',
  styleUrl: './language-editor.component.css',
})
export class LanguageEditorComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);

  readonly languageCode = input.required<string>();

  readonly closed = output<void>();
  readonly saved = output<string>();

  readonly editor = signal<TranslationEditor | null>(null);
  readonly isLoading = signal(false);
  readonly isSaving = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly saveError = signal<string | null>(null);
  readonly missingOnly = signal(false);
  readonly draft = signal<TranslationDictionary>({});
  readonly initialDraft = signal<TranslationDictionary>({});
  readonly search = signal('');

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

  readonly canEdit: boolean;

  constructor(
    private readonly settingsApi: SettingsApi,
    private readonly i18n: I18nService,
    private readonly permissionService: PermissionService,
    private readonly toast: ToastService,
  ) {
    this.canEdit =
      permissionService.hasPermission('platform.settings', 'update') ||
      permissionService.hasPermission('settings', 'update');
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading.set(true);
    this.loadError.set(null);
    this.settingsApi
      .translations(this.languageCode())
      .pipe(finalize(() => this.isLoading.set(false)))
      .subscribe({
        next: (model) => {
          this.editor.set(model);
          this.resetDraft();
        },
        error: () => this.loadError.set(this.uiI18n.translate('settings.ne_udalos_zagruzit_redaktor_perevodov')),
      });
  }

  resetDraft(): void {
    const model = this.editor();
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
              this.toast.success(this.uiI18n.translate('settings.perevody_uspeshno_sohraneny'));
              this.saved.emit(this.languageCode());
            },
            error: () =>
              this.toast.error(this.uiI18n.translate('settings.perevody_sohraneny_no_interfeys_ne_udalos_obnovi')),
          });
        },
        error: (error) => {
          this.saveError.set(
            error?.status === 409
              ? this.uiI18n.translate('settings.yazykovoy_paket_izmenen_drugim_administratorom_v')
              : this.uiI18n.translate('settings.ne_udalos_sohranit_perevody'),
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
      this.toast.error(this.uiI18n.translate('settings.nevernyy_json_ili_slovar_soderzhit_neizvestnye_k'));
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
        message: this.uiI18n.translate('settings.est_nesohranennye_perevody_zakryt_redaktor_bez_s'),
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
