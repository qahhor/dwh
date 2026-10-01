import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { finalize } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { FormMeta, FormProblems } from '@core/models/form-meta.models';
import {
  formProblems,
  hasCapability,
  recordPayload,
  recordValues,
  serverProblems,
} from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { SMTEntityFormComponent } from '@shared/entity/smt-entity-form.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiRecordHistoryComponent } from '@shared/ui/ui-record-history.component';
import { Note, NotesApi } from './notes.api';

/** What a new note starts with. */
const NEW_NOTE = { color: 'default', isPinned: false };

/**
 * Creates a note, or edits `note`, by the note form from the server. The screen creates it for one
 * editing and removes it on `closed` or `saved`, so every opening starts from the record.
 */
@Component({
  selector: 'app-note-form-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTEntityFormComponent,
    TranslatePipe,
    UiRecordHistoryComponent,
  ],
  templateUrl: './note-form-dialog.component.html',
  styleUrl: './note-form-dialog.component.css',
})
export class NoteFormDialogComponent {
  private readonly notes = inject(NotesApi);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  readonly meta = input.required<FormMeta>();
  /** The note to edit; null creates one. */
  readonly note = input<Note | null>(null);

  /** The person closed the dialog without saving. */
  readonly closed = output<void>();
  readonly saved = output<Note>();

  /** The note as last read: the one given, or the one read again after a save was refused over a newer revision. */
  readonly record = linkedSignal(() => this.note());
  /** The fields by key, starting from the record. */
  readonly values = linkedSignal(() => recordValues(this.meta(), { ...(this.record() ?? NEW_NOTE) }));
  readonly problems = signal<FormProblems>({});
  readonly saving = signal(false);
  readonly showHistory = computed(() => hasCapability(this.meta(), 'history'));

  close(): void {
    if (!this.saving()) this.closed.emit();
  }

  /** Checked by the note's declared rules first; the server checks them again and names the fields it rejects. */
  save(): void {
    if (this.saving()) return;
    const meta = this.meta();
    const note = this.record();
    const translate = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    this.problems.set(formProblems(meta, this.values(), translate));
    if (Object.keys(this.problems()).length > 0) return;

    this.saving.set(true);
    this.notes
      .save(note?.id ?? null, recordPayload(meta, this.values(), note ? { ...note } : null), note?.revision)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.toast.success(translate(note ? 'notes.updated' : 'notes.created'));
          this.saved.emit(saved);
        },
        error: (problem: ProblemDetail) => {
          this.problems.set(serverProblems(meta, problem?.errors, translate));
          if (Object.keys(this.problems()).length > 0) return;
          this.saveErrors.show(problem, {
            fallbackKey: note ? 'notes.save_error' : 'notes.create_error',
            reload: note ? () => this.reload(note.id) : undefined,
          });
        },
      });
  }

  /** Reads the note again after a save was refused over a newer revision: the form shows what is saved now. */
  reload(id: number): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.notes
      .get(id)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (fresh) => {
          this.problems.set({});
          this.record.set(fresh);
        },
        error: (problem: unknown) => this.saveErrors.show(problem, { fallbackKey: 'notes.load_error' }),
      });
  }
}
