import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { A11yModule } from '@angular/cdk/a11y';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { SMTTextareaComponent, SMTTextareaValueAccessor } from '../../../shared/ui-kit/components/forms/textarea';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { AnnouncementAdminRecord, AnnouncementBannerType, Confirmation } from '../announcements.models';
import { SMTTabBarComponent, SMTTabItem } from '../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group';
import { SMTSelectComponent, SMTSelectOption } from '../../../shared/ui-kit/components/forms/select';

@Component({
  selector: 'app-announcements-modals',
  standalone: true,
  imports: [SMTTabBarComponent, CommonModule, FormsModule, A11yModule, TranslatePipe, SMTButtonComponent, UiModalComponent, SMTInputComponent, SMTInputValueAccessor, SMTTextareaComponent, SMTTextareaValueAccessor, SMTSelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Create / Edit Draft Modal -->
    <ui-modal
      [isOpen]="isEditorOpen()"
      [title]="(editingId() === null ? 'announcements.new_announcement' : 'announcements.edit_announcement') | t"
      size="lg"
      [hasFooter]="false"
      (close)="closeEditor.emit()"
    >
      <form body id="announcement-editor" class="editor-form" (ngSubmit)="onSaveDraft()" novalidate>
        <!-- Language selector tabs for multilingual content -->
        <div class="lang-selector-row">
          <span class="lang-selector-label">{{ 'announcements.yazyk_redaktirovaniya' | t }}:</span>
          <smt-tab-bar
            class="lang-chips"
            [tabs]="languageTabs()"
            [value]="selectedLang()"
            [smtAriaLabel]="'announcements.yazyk_redaktirovaniya' | t"
            (valueChange)="$event && selectedLang.set($event)" />
        </div>

        <!-- Russian language inputs (Authoritative primary fields) -->
        <ng-container *ngIf="selectedLang() === 'ru'">
          <div class="field-group">
            <div class="field-header">
              <label for="announcement-title-ru">{{ 'announcements.zagolovok_ru' | t }} <span aria-hidden="true">*</span></label>
              <span class="char-count">{{ titleRu.length }} / 10 000 {{ 'announcements.simvolov' | t }}</span>
            </div>
            <smt-input
              smtFieldId="announcement-title-ru"
              name="announcementTitleRu"
              type="text"
              [maxLength]="10000"
              required
              smtFocusInitial
              [(ngModel)]="titleRu"
              smtDescribedBy="announcement-title-hint" />
            <span id="announcement-title-hint" class="field-hint">{{ 'announcements.korotko_opishite_glavnoe_soobschenie' | t }}</span>
          </div>
          <div class="field-group">
            <div class="field-header">
              <label for="announcement-body-ru">{{ 'announcements.tekst_obyavleniya_ru' | t }} <span aria-hidden="true">*</span></label>
            </div>
            <smt-textarea
              smtFieldId="announcement-body-ru"
              name="announcementBodyRu"
              smtDescribedBy="announcement-body-hint"
              [rows]="7"
              [maxRows]="20"
              [maxLength]="10000"
              required
              [(ngModel)]="bodyRu" />
            <span id="announcement-body-hint" class="field-hint">{{ 'announcements.do_10_000_simvolov_tekst_uvidyat_vse_polzovateli' | t }}</span>
          </div>
        </ng-container>

        <!-- Non-Russian language inputs -->
        <ng-container *ngIf="selectedLang() !== 'ru'">
          <div class="field-group">
            <div class="field-header">
              <label for="announcement-title-other">{{ 'task.title' | t }} ({{ selectedLang().toUpperCase() }})</label>
              <span class="char-count">{{ (draftTitles()[selectedLang()] || '').length }} / 10 000</span>
            </div>
            <smt-input
              smtFieldId="announcement-title-other"
              name="announcementTitleOther"
              type="text"
              [maxLength]="10000"
              [ngModel]="draftTitles()[selectedLang()]"
              (ngModelChange)="onDraftTitleChange(selectedLang(), $event)" />
          </div>
          <div class="field-group">
            <div class="field-header">
              <label for="announcement-body-other">{{ 'announcements.empty_body' | t }} ({{ selectedLang().toUpperCase() }})</label>
            </div>
            <smt-textarea
              smtFieldId="announcement-body-other"
              name="announcementBodyOther"
              [rows]="7"
              [maxRows]="20"
              [maxLength]="10000"
              [ngModel]="draftBodies()[selectedLang()]"
              (ngModelChange)="onDraftBodyChange(selectedLang(), $event)" />
          </div>
        </ng-container>

        <div class="field-group">
          <label for="announcement-banner-type">{{ 'announcements.uroven_soobscheniya' | t }}</label>
          <smt-select
            smtTriggerId="announcement-banner-type"
            [options]="bannerTypeOptions()"
            [allowClear]="false"
            [value]="bannerType()"
            (valueChange)="$event && bannerTypeChange.emit($event)"
          />
        </div>
        <div class="form-actions">
          <button smt-button type="button" smtVariant="secondary" (click)="closeEditor.emit()">{{ 'common.cancel' | t }}</button>
          <button
            type="submit"
            class="primary-button"
            data-testid="save-draft"
            [disabled]="!isDraftValid() || isSaving()"
            [attr.aria-busy]="isSaving()"
          >{{ (isSaving() ? 'common.saving' : 'announcements.save_draft') | t }}</button>
        </div>
      </form>
    </ui-modal>
  `,
  styles: [`
    :host { display: block; }
    .editor-form { display: flex; flex-direction: column; gap: 16px; }
    .lang-selector-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding-bottom: 8px; border-bottom: 1px solid var(--border-color); }
    .lang-selector-label { font-size: 12px; font-weight: 600; color: var(--text-muted); }
    .lang-required-tag { color: var(--danger); font-weight: 700; }

    .field-group { display: flex; flex-direction: column; gap: 6px; }
    .field-header { display: flex; align-items: center; justify-content: space-between; }
    .field-group label { color: var(--text-main); font-size: 12px; font-weight: 600; }
    .char-count { font-size: 11px; color: var(--text-muted); }
    .field-group textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--border-color); border-radius: var(--radius-sm); background: var(--bg-app); color: var(--text-main); font: inherit; font-size: 13px; padding: 9px 11px; }
    .field-group textarea { resize: vertical; min-height: 140px; line-height: 1.5; }
    .field-hint { color: var(--text-muted); font-size: 11px; }
    .form-actions, .modal-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
    .form-actions { margin: 4px -18px -18px; padding: 12px 18px; border-top: 1px solid var(--border-color); }
    .primary-button { height: 34px; padding: 6px 14px; border: 0; border-radius: var(--radius-sm); background: var(--primary); color: var(--on-primary); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
    .primary-button:focus-visible, textarea:focus-visible { outline: 2px solid var(--focus-ring, var(--primary)); outline-offset: 2px; }
    .primary-button:disabled { cursor: not-allowed; opacity: .5; }
  `]
})
export class AnnouncementsModalsComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  private readonly uiI18n = inject(I18nService);

  readonly isEditorOpen = input.required<boolean>();
  readonly isSaving = input.required<boolean>();
  readonly draftTitles = input.required<Record<string, string>>();
  readonly draftBodies = input.required<Record<string, string>>();
  readonly bannerType = input.required<AnnouncementBannerType>();

  readonly editingId = input<number | null>(null);

  readonly closeEditor = output<void>();
  readonly saveDraft = output<void>();
  readonly bannerTypeChange = output<AnnouncementBannerType>();
  readonly draftTitlesChange = output<Record<string, string>>();
  readonly draftBodiesChange = output<Record<string, string>>();

  readonly selectedLang = signal('ru');

  readonly availableLanguages = () => this.uiI18n.languages().filter(l => l.active);

  private readonly tabsMemo = optionsMemo<SMTTabItem<string>[]>();

  private readonly bannerTypeMemo = optionsMemo<SMTSelectOption<AnnouncementBannerType>[]>();

  get titleRu(): string {
    return this.draftTitles()['ru'] || '';
  }
  set titleRu(val: string) {
    this.draftTitlesChange.emit({ ...this.draftTitles(), ru: val });
  }

  get bodyRu(): string {
    return this.draftBodies()['ru'] || '';
  }
  set bodyRu(val: string) {
    this.draftBodiesChange.emit({ ...this.draftBodies(), ru: val });
  }

  onDraftTitleChange(lang: string, val: string): void {
    this.draftTitlesChange.emit({ ...this.draftTitles(), [lang]: val });
  }

  onDraftBodyChange(lang: string, val: string): void {
    this.draftBodiesChange.emit({ ...this.draftBodies(), [lang]: val });
  }

  isDraftValid(): boolean {
    return this.titleRu.trim().length > 0
      && this.titleRu.length <= 10_000
      && this.bodyRu.trim().length > 0
      && this.bodyRu.length <= 10_000;
  }

  onSaveDraft(): void {
    if (this.isDraftValid() && !this.isSaving()) {
      this.saveDraft.emit();
    }
  }

  localizedValue(values: Record<string, string> | null | undefined): string {
    if (!values) return '';
    const current = this.uiI18n.currentLang();
    return values[current] ?? values['ru'] ?? Object.values(values).find(value => value?.trim().length > 0) ?? '';
  }

  /** The languages to write in; Russian, the one required, is marked. */
  bannerTypeOptions(): SMTSelectOption<AnnouncementBannerType>[] {
    return this.bannerTypeMemo([this.tabText.currentLang()], () => [
      { id: 'INFO', label: this.tabText.translate('announcements.informaciya') },
      { id: 'WARNING', label: this.tabText.translate('announcements.preduprezhdenie') },
      { id: 'CRITICAL', label: this.tabText.translate('announcements.kriticheskoe') },
    ]);
  }

  languageTabs(): SMTTabItem<string>[] {
    const languages = this.availableLanguages();
    return this.tabsMemo([this.tabText.currentLang(), languages.map(language => language.code + language.name).join()], () => languages.map(language => ({
      value: language.code,
      label: language.code === 'ru' ? `${language.name} *` : language.name,
    })));
  }
}
