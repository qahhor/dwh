import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { form, FormField, max, maxLength, min, pattern, required } from '@angular/forms/signals';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { messageForError } from '@shared/ui-kit/components/forms/control/control-messages';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { discardChangesQuestion, formChanged } from '@shared/ui/discard-changes';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFormErrorItem, UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';
import type { StudioListEntry } from './studio-lists.store';

/** What the dialog edits: the names by language, flat, so each has its own control. */
export interface StudioListEntryForm {
  code: string;
  ru: string;
  uz: string;
  en: string;
  /** Null while the person has emptied the order field. */
  sortOrder: number | null;
  active: boolean;
}

/** The fields the server may name in a refusal, as the dialog calls them. */
export const STUDIO_LIST_ENTRY_FIELDS = ['code', 'ru', 'uz', 'en', 'sortOrder'] as const;

const LIST_CODE = /^[a-z][a-z0-9_]{0,39}$/;
const ITEM_CODE = /^[a-z0-9][a-z0-9_-]{0,63}$/;

/** The server's field (`name.ru`) by the dialog's (`ru`). */
export function studioListEntryField(field: string): string {
  return field.startsWith('name.') ? field.slice(5) : field;
}

/**
 * The dialog of a reference list or of one of its items (ADR-0041): a new one takes a permanent code; names in ru, uz
 * and en; an item also takes its order and, once it exists, whether new values may still take it. Errors show under
 * the fields after a field is left or a save is tried, three or more in a summary; a changed form asks before it is
 * dropped (forms standard).
 */
@Component({
  selector: 'app-studio-list-entry-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    FormField,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTInputComponent,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
    UiFormErrorSummaryComponent,
  ],
  templateUrl: './studio-list-entry-dialog.component.html',
})
export class StudioListEntryDialogComponent {
  private readonly i18n = inject(I18nService);
  private readonly kitI18n = inject(SMTI18nService);
  readonly open = input(false);
  /** A list or an item of one. */
  readonly kind = input<'list' | 'item'>('list');
  /** A new entry takes a code; an existing one keeps it. */
  readonly creating = input(true);
  readonly initial = input<StudioListEntryForm>(emptyStudioListEntry(1));
  readonly saving = input(false);
  readonly serverErrors = input<Readonly<Record<string, string>>>({});
  readonly otherErrors = input<readonly string[]>([]);
  readonly submitted = output<StudioListEntry>();
  readonly closed = output<void>();
  /** A server error is cleared as soon as its field is edited. */
  readonly edited = output<string>();

  readonly model = signal<StudioListEntryForm>(emptyStudioListEntry(1));
  readonly title = computed(() =>
    this.kind() === 'list'
      ? this.creating()
        ? 'studio.lists.new_list'
        : 'studio.lists.rename_list'
      : this.creating()
        ? 'studio.lists.new_item'
        : 'studio.lists.edit_item',
  );
  readonly entryForm = form(this.model, (path) => {
    required(path.code);
    pattern(path.code, () => (this.kind() === 'list' ? LIST_CODE : ITEM_CODE));
    for (const name of [path.ru, path.uz, path.en]) {
      required(name);
      maxLength(name, 80);
    }
    required(path.sortOrder, { when: () => this.kind() === 'item' });
    min(path.sortOrder, 1);
    max(path.sortOrder, 9999);
  });
  private readonly askDiscard = discardChangesQuestion();

  constructor() {
    effect(() => {
      if (this.open()) this.entryForm().reset(structuredClone(this.initial()));
    });
  }

  summary(): UiFormErrorItem[] {
    const fields: [keyof StudioListEntryForm, string, string][] = [
      ['code', 'studio-entry-code', 'studio.lists.code'],
      ['ru', 'studio-entry-ru', 'settings.form_studio.labels.ru'],
      ['uz', 'studio-entry-uz', 'settings.form_studio.labels.uz'],
      ['en', 'studio-entry-en', 'settings.form_studio.labels.en'],
      ['sortOrder', 'studio-entry-order', 'studio.lists.order'],
    ];
    const errors: UiFormErrorItem[] = fields.flatMap(([key, fieldId, label]) => {
      if (key === 'code' && !this.creating()) return [];
      if (key === 'sortOrder' && this.kind() === 'list') return [];
      const field = this.entryForm[key]();
      const message =
        this.serverErrors()[key] ??
        (field.touched() ? messageForError(field.errors()[0], this.kitI18n.messages().control) : '');
      return message ? [{ fieldId, label: this.i18n.translate(label), message }] : [];
    });
    return [...errors, ...this.otherErrors().map((message) => ({ message }))];
  }

  submit(): void {
    if (this.saving()) return;
    markSMTFormFieldsTouched(this.entryForm);
    const value = this.model();
    const codeValid = !this.creating() || this.entryForm.code().valid();
    const namesValid = this.entryForm.ru().valid() && this.entryForm.uz().valid() && this.entryForm.en().valid();
    const orderValid = this.kind() === 'list' || this.entryForm.sortOrder().valid();
    if (!codeValid || !namesValid || !orderValid) return;
    this.submitted.emit({
      code: value.code.trim(),
      name: { ru: value.ru.trim(), uz: value.uz.trim(), en: value.en.trim() },
      sortOrder: value.sortOrder ?? 1,
      active: value.active,
    });
  }

  close(): void {
    if (this.saving()) return;
    this.askDiscard(formChanged(this.initial(), this.model())).subscribe((discard) => {
      if (discard) this.closed.emit();
    });
  }

  setActive(active: boolean): void {
    this.model.update((model) => ({ ...model, active }));
  }
}

/** A blank entry: an item ordered `sortOrder`, used by new values. */
export function emptyStudioListEntry(sortOrder: number): StudioListEntryForm {
  return { code: '', ru: '', uz: '', en: '', sortOrder, active: true };
}
