import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormMeta } from '@core/models/form-meta.models';
import { canDo, recordValues } from '@core/services/form-meta.service';
import { TranslatePipe } from '@core/services/i18n.service';
import { SMTEntityCardComponent } from '@shared/entity/smt-entity-card.component';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { UiMarkdownViewComponent } from '@shared/ui/ui-markdown-view.component';
import { Note } from './notes.api';

/** One note on the board: its text, its custom fields and the actions the note form allows the viewer. */
@Component({
  selector: 'app-note-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, SMTCheckboxComponent, SMTEntityCardComponent, TranslatePipe, UiMarkdownViewComponent],
  templateUrl: './note-card.component.html',
  styleUrl: './note-card.component.css',
  host: {
    '[class]': "'note-card color-' + (note().color || 'default')",
    '[class.is-pinned]': 'note().isPinned',
    '[class.is-archived]': '!!note().archived',
  },
})
export class NoteCardComponent {
  readonly note = input.required<Note>();
  readonly meta = input.required<FormMeta>();

  /** Shows the checkbox that chooses the note for a bulk action. */
  readonly selectable = input(false);
  readonly selected = input(false);

  readonly selectedChange = output<boolean>();
  readonly togglePin = output<void>();
  readonly edit = output<void>();
  readonly remove = output<void>();
  /** Asks to move the note to the archive, or back when it is archived (ADR-0032 5.4). */
  readonly toggleArchive = output<void>();

  readonly canPin = computed(() => canDo(this.meta(), 'pin'));
  readonly canArchive = computed(() => canDo(this.meta(), 'archive'));
  readonly canEdit = computed(() => canDo(this.meta(), 'update'));
  readonly canDelete = computed(() => canDo(this.meta(), 'delete'));
  readonly values = computed(() => recordValues(this.meta(), { ...this.note() }));

  /** The card shows the custom fields; the title and the text are drawn above them. */
  readonly customSections = ['custom'];
}
