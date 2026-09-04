import { CommonModule } from '@angular/common';
import {
  Component,
  EventEmitter,
  HostListener,
  Input,
  OnInit,
  Output,
  computed,
  signal, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import {
  LanguageInfo,
  TranslationDictionary,
  TranslationEditor,
  TranslationEntry
} from '../../core/models/i18n.models';
import { ApiService } from '../../core/services/api.service';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-language-editor',
  standalone: true,
  imports: [
    TranslatePipe,CommonModule, FormsModule],
  template: `
    <section class="translation-editor" aria-labelledby="translation-editor-title">
      <header class="editor-header">
        <div class="editor-heading">
          <button type="button" class="icon-action" data-testid="language-editor-close" [attr.aria-label]="'settings.vernutsya_k_spisku_yazykov' | t" (click)="requestClose()">
            <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span>
          </button>
          <div>
            <h3 id="translation-editor-title">{{ 'settings.translations_for' | t:{name: editor()?.language?.name || languageCode.toUpperCase()} }}</h3>
            <p *ngIf="editor() as model">
              {{ 'settings.translation_progress' | t:{translated: model.language.translated, total: model.language.total, coverage: model.language.coverage} }}
            </p>
          </div>
        </div>
        <span class="coverage" *ngIf="editor() as model" [attr.aria-label]="'settings.translation_coverage' | t:{coverage: model.language.coverage}">
          {{ model.language.coverage }}%
        </span>
      </header>

      <div class="editor-loading" *ngIf="isLoading()" role="status">{{ 'settings.zagruzka_perevodov' | t }}</div>
      <div class="editor-error" *ngIf="loadError()" role="alert">
        <span>{{ loadError() }}</span>
        <button type="button" class="text-action" (click)="load()">{{ 'announcements.povtorit' | t }}</button>
      </div>

      <ng-container *ngIf="editor()">
        <div class="editor-toolbar">
          <div class="search-field">
            <label for="translation-search">{{ 'settings.poisk_perevoda' | t }}</label>
            <div class="search-control">
              <span class="material-symbols-outlined" aria-hidden="true">search</span>
              <input
                id="translation-search"
                type="search"
                [ngModel]="search()"
                (ngModelChange)="search.set($event)"
                [placeholder]="'settings.klyuch_russkiy_tekst_ili_perevod' | t"
              />
            </div>
          </div>
          <label class="missing-filter">
            <input type="checkbox" [ngModel]="missingOnly()" (ngModelChange)="missingOnly.set($event)" />
            <span>{{ 'settings.tolko_neperevedennye' | t }}</span>
          </label>
          <label class="import-action" *ngIf="canEdit">
            <span class="material-symbols-outlined" aria-hidden="true">upload_file</span>
            <span>{{ 'settings.import_json' | t }}</span>
            <input type="file" accept="application/json,.json" (change)="importFile($event)" />
          </label>
          <button type="button" class="secondary-action" (click)="exportDraft()">
            <span class="material-symbols-outlined" aria-hidden="true">download</span>
            {{ 'settings.eksport_json' | t }}
          </button>
        </div>

        <div class="translation-table" role="region" [attr.aria-label]="'settings.tablica_perevodov' | t" tabindex="0">
          <div class="translation-row header-row" aria-hidden="true">
            <span>{{ 'settings.klyuch' | t }}</span>
            <span>{{ (languageCode === 'ru' ? 'settings.packaged_russian' : 'settings.russian_source') | t }}</span>
            <span>{{ (languageCode === 'ru' ? 'settings.interface_text' : 'settings.translation') | t }}</span>
            <span>{{ 'common.status' | t }}</span>
          </div>

          <div
            class="translation-row"
            *ngFor="let entry of filteredEntries(); trackBy: trackByKey"
            [attr.data-translation-key]="entry.key"
          >
            <code class="translation-key">{{ entry.key }}</code>
            <div class="source-value" [attr.data-label]="(languageCode === 'ru' ? 'settings.packaged_russian' : 'settings.russian_source') | t">
              {{ languageCode === 'ru' ? entry.bundledValue : entry.russianValue }}
            </div>
            <div class="target-value" [attr.data-label]="'settings.translation' | t">
              <label class="sr-only" [for]="inputId(entry.key)">{{ 'settings.translation_for_key' | t:{key: entry.key} }}</label>
              <input
                [id]="inputId(entry.key)"
                type="text"
                [disabled]="!canEdit"
                [ngModel]="valueFor(entry.key)"
                (ngModelChange)="setValue(entry.key, $event)"
                [placeholder]="languageCode === 'ru' ? entry.bundledValue || '' : entry.russianValue"
                maxlength="4000"
              />
              <button
                type="button"
                class="reset-action"
                *ngIf="canEdit && canReset(entry)"
                [attr.aria-label]="'settings.restore_packaged_value' | t:{key: entry.key}"
                (click)="resetValue(entry)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">restart_alt</span>
              </button>
            </div>
            <span
              class="translation-status"
              [class.ready]="isTranslated(entry)"
              [attr.data-status]="isTranslated(entry) ? 'translated' : 'missing'"
              [attr.data-label]="'common.status' | t"
            >
              {{ (isTranslated(entry) ? 'settings.translated' : 'settings.fallback_ru') | t }}
            </span>
          </div>

          <div class="empty-filter" *ngIf="filteredEntries().length === 0">
            {{ 'settings.po_zadannym_usloviyam_stroki_ne_naydeny' | t }}
          </div>
        </div>

        <div class="save-error" *ngIf="saveError()" role="alert">{{ saveError() }}</div>

        <footer class="editor-actions" *ngIf="canEdit">
          <span class="dirty-count" aria-live="polite">{{ 'settings.changed_count' | t:{count: dirtyCount()} }}</span>
          <div>
            <button type="button" class="secondary-action" [disabled]="dirtyCount() === 0 || isSaving()" (click)="cancelChanges()">
              {{ 'settings.otmenit_izmeneniya' | t }}
            </button>
            <button type="button" class="primary-action" data-testid="language-editor-save" [disabled]="dirtyCount() === 0 || isSaving()" (click)="save()">
              <span class="material-symbols-outlined" aria-hidden="true">save</span>
              {{ (isSaving() ? 'common.saving' : 'settings.save_translations') | t }}
            </button>
          </div>
        </footer>
      </ng-container>
    </section>
  `,
  styles: [`
    .translation-editor {
      display: flex;
      flex-direction: column;
      gap: 18px;
      min-width: 0;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 20px;
    }
    .editor-header, .editor-heading, .editor-toolbar, .editor-actions,
    .editor-actions > div, .search-control, .target-value, .import-action,
    .primary-action, .secondary-action {
      display: flex;
      align-items: center;
    }
    .editor-header, .editor-actions { justify-content: space-between; gap: 16px; }
    .editor-heading { gap: 10px; }
    .editor-heading h3 { margin: 0; color: var(--text-main); font-size: 18px; }
    .editor-heading p { margin: 3px 0 0; color: var(--text-light); font-size: 12px; }
    .coverage {
      min-width: 52px;
      padding: 6px 10px;
      border-radius: 999px;
      background: var(--primary-soft);
      color: var(--primary-text);
      font-weight: 700;
      text-align: center;
    }
    .icon-action, .reset-action, .text-action, .secondary-action, .primary-action, .import-action {
      border: 0;
      cursor: pointer;
      font: inherit;
    }
    .icon-action, .reset-action {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      color: var(--text-light);
      background: transparent;
      border-radius: 7px;
    }
    .icon-action { width: 36px; height: 36px; }
    .reset-action { width: 30px; height: 30px; flex: 0 0 30px; }
    .icon-action:hover, .reset-action:hover { background: var(--bg-hover); color: var(--text-main); }
    .editor-toolbar { gap: 12px; flex-wrap: wrap; }
    .search-field { flex: 1 1 280px; }
    .search-field > label { display: block; margin-bottom: 5px; color: var(--text-main); font-size: 12px; font-weight: 600; }
    .search-control {
      gap: 7px;
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 0 10px;
      background: var(--bg-surface);
    }
    .search-control span { color: var(--text-light); font-size: 18px; }
    .search-control input { width: 100%; min-height: 38px; border: 0; outline: 0; color: var(--text-main); background: transparent; }
    .missing-filter { display: flex; align-items: center; gap: 7px; color: var(--text-main); font-size: 13px; cursor: pointer; }
    .import-action, .secondary-action, .primary-action {
      gap: 6px;
      min-height: 36px;
      padding: 7px 11px;
      border-radius: 8px;
      font-size: 12px;
      font-weight: 600;
    }
    .import-action, .secondary-action { color: var(--text-main); background: var(--bg-surface); border: 1px solid var(--border-color); }
    .primary-action { color: var(--text-inverse); background: var(--primary); }
    .import-action input { position: absolute; width: 1px; height: 1px; opacity: 0; }
    .primary-action:disabled, .secondary-action:disabled { cursor: not-allowed; opacity: .5; }
    .translation-table { overflow: auto; border: 1px solid var(--border-color); border-radius: 10px; }
    .translation-row {
      display: grid;
      grid-template-columns: minmax(180px, .8fr) minmax(220px, 1fr) minmax(260px, 1.2fr) 105px;
      gap: 12px;
      align-items: center;
      min-width: 850px;
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-subtle);
    }
    .translation-row:last-child { border-bottom: 0; }
    .header-row { color: var(--text-light); background: var(--bg-hover); font-size: 11px; font-weight: 700; text-transform: uppercase; }
    .translation-key { color: var(--primary-text); font-size: 11px; overflow-wrap: anywhere; }
    .source-value { color: var(--text-main); font-size: 13px; }
    .target-value { gap: 4px; }
    .target-value input {
      min-width: 0;
      width: 100%;
      min-height: 36px;
      padding: 7px 9px;
      border: 1px solid var(--border-color);
      border-radius: 7px;
      color: var(--text-main);
      background: var(--bg-surface);
    }
    .target-value input:focus { border-color: var(--primary); outline: 2px solid color-mix(in srgb, var(--primary) 18%, transparent); }
    .translation-status { color: var(--warning-text, #8a5b00); font-size: 11px; font-weight: 700; }
    .translation-status.ready { color: var(--success-text, #087443); }
    .editor-actions { position: sticky; bottom: 0; padding: 12px; margin: 0 -8px -8px; border: 1px solid var(--border-color); border-radius: 9px; background: var(--bg-surface); box-shadow: var(--shadow-md); }
    .editor-actions > div { gap: 8px; }
    .dirty-count { color: var(--text-light); font-size: 12px; }
    .editor-loading, .empty-filter { padding: 32px; color: var(--text-light); text-align: center; }
    .editor-error, .save-error { padding: 10px 12px; border: 1px solid var(--danger); border-radius: 8px; color: var(--danger-text, #a61b1b); background: var(--danger-soft, #fff0f0); }
    .editor-error { display: flex; justify-content: space-between; gap: 12px; }
    .text-action { color: inherit; background: transparent; text-decoration: underline; }
    .sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
    @media (max-width: 760px) {
      .translation-editor { padding: 14px; }
      .editor-header, .editor-actions { align-items: flex-start; }
      .editor-actions { flex-direction: column; }
      .editor-actions > div { width: 100%; }
      .editor-actions button { flex: 1; }
      .translation-row { min-width: 0; grid-template-columns: 1fr; gap: 6px; }
      .header-row { display: none; }
      .source-value::before, .target-value::before, .translation-status::before {
        content: attr(data-label);
        display: block;
        margin-bottom: 3px;
        color: var(--text-light);
        font-size: 10px;
        font-weight: 700;
        text-transform: uppercase;
      }
      .target-value { display: grid; grid-template-columns: 1fr auto; }
      .target-value::before { grid-column: 1 / -1; }
    }
  `]
})
export class LanguageEditorComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  @Input({ required: true }) languageCode = 'ru';
  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly saved = new EventEmitter<string>();

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
    return Object.keys(after).filter(key => after[key] !== before[key]).length;
  });

  readonly filteredEntries = computed(() => {
    const model = this.editor();
    if (!model) return [];
    const query = this.search().trim().toLocaleLowerCase();
    return model.entries.filter(entry => {
      if (this.missingOnly() && this.isTranslated(entry)) return false;
      if (!query) return true;
      return entry.key.toLocaleLowerCase().includes(query)
        || entry.russianValue.toLocaleLowerCase().includes(query)
        || this.valueFor(entry.key).toLocaleLowerCase().includes(query);
    });
  });

  readonly canEdit: boolean;

  constructor(
    private readonly api: ApiService,
    private readonly i18n: I18nService,
    private readonly permissionService: PermissionService,
    private readonly toast: ToastService
  ) {
    this.canEdit = permissionService.hasPermission('platform.settings', 'update')
      || permissionService.hasPermission('settings', 'update');
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.isLoading.set(true);
    this.loadError.set(null);
    this.api.get<TranslationEditor>(`/i18n/admin/languages/${this.languageCode}/translations`)
      .pipe(finalize(() => this.isLoading.set(false)))
      .subscribe({
        next: model => {
          this.editor.set(model);
          this.resetDraft();
        },
        error: () => this.loadError.set(this.uiI18n.translate('settings.ne_udalos_zagruzit_redaktor_perevodov'))
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
    this.draft.update(current => ({ ...current, [key]: value }));
    this.saveError.set(null);
  }

  isTranslated(entry: TranslationEntry): boolean {
    return this.languageCode === 'ru' || this.valueFor(entry.key).trim().length > 0;
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

    if (this.languageCode === 'ru') {
      const emptyRussian = model.entries.find(entry => !this.valueFor(entry.key).trim());
      if (emptyRussian) {
        this.toast.error(this.uiI18n.translate('settings.russian_translation_required', {
          key: emptyRussian.key
        }));
        return;
      }
    }

    const translations = this.buildOverrides(model);
    this.isSaving.set(true);
    this.saveError.set(null);
    this.api.put<LanguageInfo>(`/i18n/admin/languages/${this.languageCode}/translations`, {
      expectedRevision: model.language.revision,
      translations
    }).pipe(finalize(() => this.isSaving.set(false)))
      .subscribe({
        next: language => {
          this.applySavedModel(model, language, translations);
          this.i18n.refreshLanguage(this.languageCode).subscribe({
            next: () => {
              this.toast.success(this.uiI18n.translate('settings.perevody_uspeshno_sohraneny'));
              this.saved.emit(this.languageCode);
            },
            error: () => this.toast.error(this.uiI18n.translate('settings.perevody_sohraneny_no_interfeys_ne_udalos_obnovi'))
          });
        },
        error: error => {
          this.saveError.set(error?.status === 409
            ? this.uiI18n.translate('settings.yazykovoy_paket_izmenen_drugim_administratorom_v')
            : this.uiI18n.translate('settings.ne_udalos_sohranit_perevody'));
        }
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
      const known = new Set(model.entries.map(entry => entry.key));
      const imported: TranslationDictionary = {};
      for (const [key, value] of Object.entries(parsed)) {
        if (!known.has(key)) throw new Error(`unknown key: ${key}`);
        if (typeof value !== 'string' || value.length > 4000) throw new Error(`invalid value: ${key}`);
        imported[key] = value;
      }
      this.draft.update(current => ({ ...current, ...imported }));
      this.toast.success(this.uiI18n.translate('settings.imported_rows', {
        count: Object.keys(imported).length
      }));
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
      effective[entry.key] = this.valueFor(entry.key).trim()
        || entry.russianValue;
    }
    const blob = new Blob([JSON.stringify(effective, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `smartupcms-translations-${this.languageCode}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  requestClose(): void {
    if (this.dirtyCount() > 0
        && !window.confirm(this.uiI18n.translate('settings.est_nesohranennye_perevody_zakryt_redaktor_bez_s'))) {
      return;
    }
    this.closed.emit();
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

  private applySavedModel(model: TranslationEditor, language: LanguageInfo,
                          overrides: TranslationDictionary): void {
    const entries = model.entries.map(entry => {
      const overrideValue = overrides[entry.key] ?? null;
      const effectiveValue = overrideValue ?? entry.bundledValue ?? entry.russianValue;
      return {
        ...entry,
        overrideValue,
        effectiveValue,
        translated: this.languageCode === 'ru'
          || overrideValue !== null
          || entry.bundledValue !== null
      };
    });
    this.editor.set({ language, entries });
    this.resetDraft();
  }
}
