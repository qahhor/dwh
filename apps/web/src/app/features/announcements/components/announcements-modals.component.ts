import { ChangeDetectionStrategy, Component, inject, input, linkedSignal, output, signal } from '@angular/core';

import { A11yModule } from '@angular/cdk/a11y';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
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
    SMTControlComponent,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
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
      [dismissible]="!isSaving()"
      (closed)="closeEditor.emit()"
    >
      <ng-template smtDialogContent>
        <form
          id="announcement-editor"
          class="editor-form"
          uiFocusFirstInvalid
          (submit)="$event.preventDefault(); onSaveDraft()"
          novalidate
        >
          @if (draftError()) {
            <div class="editor-alert" role="alert" data-testid="announcement-save-error">{{ draftError() }}</div>
          }
          <!-- Language selector tabs for multilingual content -->
          <div class="lang-selector-row">
            <span class="lang-selector-label">{{ 'announcements.editor.content_language' | t }}:</span>
            <smt-tab-bar
              class="lang-chips"
              [tabs]="languageTabs()"
              [value]="selectedLang()"
              [smtAriaLabel]="'announcements.editor.content_language' | t"
              (valueChange)="$event && selectedLang.set($event)"
            />
          </div>

          <!-- Russian language inputs (Authoritative primary fields) -->
          @if (selectedLang() === 'ru') {
            <smt-control
              class="field-group"
              [smtLabel]="'announcements.editor.title_ru' | t"
              [smtHint]="'announcements.editor.title_placeholder' | t"
              [smtError]="titleRuError()"
              [required]="true"
            >
              <smt-input
                smtFieldId="announcement-title-ru"
                name="announcementTitleRu"
                type="text"
                [maxLength]="10000"
                smtFocusInitial
                [value]="titleRu"
                (valueChange)="onDraftTitleChange('ru', $event)"
              />
            </smt-control>
            <span class="char-count" aria-live="polite"
              >{{ titleRu.length }} / 10 000 {{ 'announcements.editor.characters' | t }}</span
            >
            <smt-control
              class="field-group"
              [smtLabel]="'announcements.editor.body_ru' | t"
              [smtHint]="'announcements.editor.body_hint' | t"
              [smtError]="bodyRuError()"
              [required]="true"
            >
              <smt-textarea
                smtFieldId="announcement-body-ru"
                name="announcementBodyRu"
                [rows]="7"
                [maxRows]="20"
                [maxLength]="10000"
                [value]="bodyRu"
                (valueChange)="onDraftBodyChange('ru', $event)"
              />
            </smt-control>
          }

          <!-- Non-Russian language inputs -->
          @if (selectedLang() !== 'ru') {
            <smt-control
              class="field-group"
              [smtLabel]="('task.title' | t) + ' (' + selectedLang().toUpperCase() + ')'"
            >
              <smt-input
                smtFieldId="announcement-title-other"
                name="announcementTitleOther"
                type="text"
                [maxLength]="10000"
                [value]="draftTitles()[selectedLang()]"
                (valueChange)="onDraftTitleChange(selectedLang(), $event)"
              />
            </smt-control>
            <span class="char-count">{{ (draftTitles()[selectedLang()] || '').length }} / 10 000</span>
            <smt-control
              class="field-group"
              [smtLabel]="('announcements.empty_body' | t) + ' (' + selectedLang().toUpperCase() + ')'"
            >
              <smt-textarea
                smtFieldId="announcement-body-other"
                name="announcementBodyOther"
                [rows]="7"
                [maxRows]="20"
                [maxLength]="10000"
                [value]="draftBodies()[selectedLang()]"
                (valueChange)="onDraftBodyChange(selectedLang(), $event)"
              />
            </smt-control>
          }

          <smt-control
            class="field-group"
            [smtLabel]="'announcements.editor.level' | t"
            [smtError]="draftErrors()['bannerType'] ?? ''"
          >
            <smt-select
              smtTriggerId="announcement-banner-type"
              [options]="bannerTypeOptions()"
              [allowClear]="false"
              [value]="bannerType()"
              (valueChange)="$event && bannerTypeChange.emit($event)"
            />
          </smt-control>
        </form>
        <ui-form-actions
          footer
          form="announcement-editor"
          data-testid="announcement-editor-actions"
          [submitLabel]="'announcements.save_draft' | t"
          [submitting]="isSaving()"
          (cancelled)="closeEditor.emit()"
        />
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
  /** The server's refusal of the draft by field (`titleRu`, `bodyRu`, `bannerType`). */
  readonly draftErrors = input<Readonly<Record<string, string>>>({});
  /** A refusal that names no field, shown at the top of the editor. */
  readonly draftError = input<string | null>(null);

  readonly closeEditor = output<void>();
  readonly saveDraft = output<void>();
  readonly bannerTypeChange = output<AnnouncementBannerType>();
  readonly draftTitlesChange = output<Record<string, string>>();
  readonly draftBodiesChange = output<Record<string, string>>();

  readonly selectedLang = signal('ru');

  /** A save was tried in this opening of the editor, so the empty required fields show their errors. */
  readonly saveTried = linkedSignal({ source: this.isEditorOpen, computation: () => false });

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

  /** "Required" after a save with an empty Russian title, else the server's word on it (forms standard, 4). */
  titleRuError(): string {
    if (this.saveTried() && !this.titleRu.trim()) return this.uiI18n.translate('ui.control.required');
    return this.draftErrors()['titleRu'] ?? '';
  }

  bodyRuError(): string {
    if (this.saveTried() && !this.bodyRu.trim()) return this.uiI18n.translate('ui.control.required');
    return this.draftErrors()['bodyRu'] ?? '';
  }

  /**
   * Enter and "Save draft" land here; one request while a save runs. The Russian fields are the required ones, so an
   * invalid draft switches to them and the form moves focus to the first empty one.
   */
  onSaveDraft(): void {
    if (this.isSaving()) return;
    this.saveTried.set(true);
    if (!this.isDraftValid()) {
      this.selectedLang.set('ru');
      return;
    }
    this.saveDraft.emit();
  }

  localizedValue(values: Record<string, string> | null | undefined): string {
    if (!values) return '';
    const current = this.uiI18n.currentLang();
    return values[current] ?? values['ru'] ?? Object.values(values).find((value) => value?.trim().length > 0) ?? '';
  }

  /** The languages to write in; Russian, the one required, is marked. */
  bannerTypeOptions(): SMTSelectOption<AnnouncementBannerType>[] {
    return this.bannerTypeMemo([this.tabText.currentLang()], () => [
      { id: 'INFO', label: this.tabText.translate('announcements.common.level_info') },
      { id: 'WARNING', label: this.tabText.translate('announcements.common.level_warning') },
      { id: 'CRITICAL', label: this.tabText.translate('announcements.common.level_critical') },
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
