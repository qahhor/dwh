import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';

import { A11yModule } from '@angular/cdk/a11y';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTTextareaComponent } from '@shared/ui-kit/components/forms/textarea';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { AnnouncementBannerType } from '../announcements.models';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';

@Component({
  selector: 'app-announcements-modals',
  imports: [
    SMTTabBarComponent,
    A11yModule,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTInputComponent,
    SMTTextareaComponent,
    SMTSelectComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Create / Edit Draft Modal -->
    <smt-dialog
      [open]="isEditorOpen()"
      [smtTitle]="(editingId() === null ? 'announcements.new_announcement' : 'announcements.edit_announcement') | t"
      smtSize="lg"
      (closed)="closeEditor.emit()"
    >
      <ng-template smtDialogContent>
        <form
          body
          id="announcement-editor"
          class="editor-form"
          (submit)="$event.preventDefault(); onSaveDraft()"
          novalidate
        >
          <!-- Language selector tabs for multilingual content -->
          <div class="lang-selector-row">
            <span class="lang-selector-label">{{ 'announcements.yazyk_redaktirovaniya' | t }}:</span>
            <smt-tab-bar
              class="lang-chips"
              [tabs]="languageTabs()"
              [value]="selectedLang()"
              [smtAriaLabel]="'announcements.yazyk_redaktirovaniya' | t"
              (valueChange)="$event && selectedLang.set($event)"
            />
          </div>

          <!-- Russian language inputs (Authoritative primary fields) -->
          @if (selectedLang() === 'ru') {
            <div class="field-group">
              <div class="field-header">
                <label for="announcement-title-ru"
                  >{{ 'announcements.zagolovok_ru' | t }} <span aria-hidden="true">*</span></label
                >
                <span class="char-count">{{ titleRu.length }} / 10 000 {{ 'announcements.simvolov' | t }}</span>
              </div>
              <smt-input
                smtFieldId="announcement-title-ru"
                name="announcementTitleRu"
                type="text"
                [maxLength]="10000"
                required
                smtFocusInitial
                [value]="titleRu"
                (valueChange)="onDraftTitleChange('ru', $event)"
                smtDescribedBy="announcement-title-hint"
              />
              <span id="announcement-title-hint" class="field-hint">{{
                'announcements.korotko_opishite_glavnoe_soobschenie' | t
              }}</span>
            </div>
            <div class="field-group">
              <div class="field-header">
                <label for="announcement-body-ru"
                  >{{ 'announcements.tekst_obyavleniya_ru' | t }} <span aria-hidden="true">*</span></label
                >
              </div>
              <smt-textarea
                smtFieldId="announcement-body-ru"
                name="announcementBodyRu"
                smtDescribedBy="announcement-body-hint"
                [rows]="7"
                [maxRows]="20"
                [maxLength]="10000"
                required
                [value]="bodyRu"
                (valueChange)="onDraftBodyChange('ru', $event)"
              />
              <span id="announcement-body-hint" class="field-hint">{{
                'announcements.do_10_000_simvolov_tekst_uvidyat_vse_polzovateli' | t
              }}</span>
            </div>
          }

          <!-- Non-Russian language inputs -->
          @if (selectedLang() !== 'ru') {
            <div class="field-group">
              <div class="field-header">
                <label for="announcement-title-other"
                  >{{ 'task.title' | t }} ({{ selectedLang().toUpperCase() }})</label
                >
                <span class="char-count">{{ (draftTitles()[selectedLang()] || '').length }} / 10 000</span>
              </div>
              <smt-input
                smtFieldId="announcement-title-other"
                name="announcementTitleOther"
                type="text"
                [maxLength]="10000"
                [value]="draftTitles()[selectedLang()]"
                (valueChange)="onDraftTitleChange(selectedLang(), $event)"
              />
            </div>
            <div class="field-group">
              <div class="field-header">
                <label for="announcement-body-other"
                  >{{ 'announcements.empty_body' | t }} ({{ selectedLang().toUpperCase() }})</label
                >
              </div>
              <smt-textarea
                smtFieldId="announcement-body-other"
                name="announcementBodyOther"
                [rows]="7"
                [maxRows]="20"
                [maxLength]="10000"
                [value]="draftBodies()[selectedLang()]"
                (valueChange)="onDraftBodyChange(selectedLang(), $event)"
              />
            </div>
          }

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
            <button smt-button type="button" smtVariant="secondary" (click)="closeEditor.emit()">
              {{ 'common.cancel' | t }}
            </button>
            <button
              type="submit"
              class="primary-button"
              data-testid="save-draft"
              [disabled]="!isDraftValid() || isSaving()"
              [attr.aria-busy]="isSaving()"
            >
              {{ (isSaving() ? 'common.saving' : 'announcements.save_draft') | t }}
            </button>
          </div>
        </form>
      </ng-template>
    </smt-dialog>
  `,
  styleUrl: './announcements-modals.component.css',
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

  readonly availableLanguages = () => this.uiI18n.languages().filter((l) => l.active);

  private readonly tabsMemo = optionsMemo<SMTTabItem<string>[]>();

  private readonly bannerTypeMemo = optionsMemo<SMTSelectOption<AnnouncementBannerType>[]>();

  get titleRu(): string {
    return this.draftTitles()['ru'] || '';
  }

  get bodyRu(): string {
    return this.draftBodies()['ru'] || '';
  }

  /** The kit field types its value as text, a number or null; the draft keeps text. */
  onDraftTitleChange(lang: string, val: SMTInputValue): void {
    this.draftTitlesChange.emit({ ...this.draftTitles(), [lang]: val === null ? '' : String(val) });
  }

  onDraftBodyChange(lang: string, val: string): void {
    this.draftBodiesChange.emit({ ...this.draftBodies(), [lang]: val });
  }

  isDraftValid(): boolean {
    return (
      this.titleRu.trim().length > 0 &&
      this.titleRu.length <= 10_000 &&
      this.bodyRu.trim().length > 0 &&
      this.bodyRu.length <= 10_000
    );
  }

  onSaveDraft(): void {
    if (this.isDraftValid() && !this.isSaving()) {
      this.saveDraft.emit();
    }
  }

  localizedValue(values: Record<string, string> | null | undefined): string {
    if (!values) return '';
    const current = this.uiI18n.currentLang();
    return values[current] ?? values['ru'] ?? Object.values(values).find((value) => value?.trim().length > 0) ?? '';
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
    return this.tabsMemo(
      [this.tabText.currentLang(), languages.map((language) => language.code + language.name).join()],
      () =>
        languages.map((language) => ({
          value: language.code,
          label: language.code === 'ru' ? `${language.name} *` : language.name,
        })),
    );
  }
}
