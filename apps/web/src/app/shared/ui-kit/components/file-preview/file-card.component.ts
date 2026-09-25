/* Our code, after the idea of the kit's `smt-file-preview`
 * (smartup-ui-kit@6472beb, components/file-preview). See ADR-0015 rule 2
 * and NOTICE.
 *
 * Why not the kit's copy: it downloaded through an injected token with its
 * own progress bar and a CDK menu of options, and drew icons from the kit's
 * sprite; the application had two cards of its own with copied rules.
 *
 * A file as a card: the kind's icon, the name (a button that downloads it),
 * the size in the person's language, and buttons to preview an image and to
 * remove the file when the caller allows. Every button is named after the
 * file. The card only reports what was asked; the caller downloads, opens
 * the preview (SMTFilePreviewService) and confirms a removal. */
import { booleanAttribute, ChangeDetectionStrategy, Component, computed, inject, input, output, ViewEncapsulation } from '@angular/core';
import { SMTI18nService } from '../../i18n';
import { canPreview, fileKind, fileKindIcon, formatFileSize } from './file-kind';

@Component({
  selector: 'smt-file-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './file-preview.scss',
  host: { class: 'smt-file-card' },
  template: `
    <span class="smt-file-card__icon smt-file-card__icon--{{ kind() }}" aria-hidden="true">
      <span class="material-symbols-outlined" aria-hidden="true">{{ icon() }}</span>
    </span>
    <button
      type="button"
      class="smt-file-card__name"
      [attr.aria-label]="i18n.messages().file.download(name())"
      [title]="name()"
      (click)="download.emit()">
      <span class="smt-file-card__title">{{ name() }}</span>
      @if (size() !== null) {
        <span class="smt-file-card__size">{{ sizeText() }}</span>
      }
    </button>
    <span class="smt-file-card__actions">
      @if (previewable()) {
        <button type="button" class="smt-file-card__action" [attr.aria-label]="i18n.messages().file.preview(name())" (click)="preview.emit()">
          <span class="material-symbols-outlined" aria-hidden="true">visibility</span>
        </button>
      }
      <button type="button" class="smt-file-card__action" [attr.aria-label]="i18n.messages().file.download(name())" (click)="download.emit()">
        <span class="material-symbols-outlined" aria-hidden="true">download</span>
      </button>
      @if (removable()) {
        <button
          type="button"
          class="smt-file-card__action smt-file-card__action--danger"
          [disabled]="removing()"
          [attr.aria-label]="i18n.messages().file.remove(name())"
          (click)="remove.emit()">
          <span class="material-symbols-outlined" aria-hidden="true">delete</span>
        </button>
      }
    </span>
  `,
})
export class SMTFileCardComponent {
  readonly i18n = inject(SMTI18nService);

  readonly name = input.required<string>();

  readonly mimeType = input<string | null | undefined>(null);

  /** Bytes; null hides the size. */
  readonly size = input<number | null>(null);

  readonly removable = input(false, { alias: 'smtRemovable', transform: booleanAttribute });

  /** A removal in progress: its button is disabled. */
  readonly removing = input(false, { alias: 'smtRemoving', transform: booleanAttribute });

  /** false hides the preview button even for an image, e.g. where no preview is wired. */
  readonly previewEnabled = input(true, { alias: 'smtPreview', transform: booleanAttribute });

  readonly download = output<void>();

  readonly preview = output<void>();

  readonly remove = output<void>();

  readonly kind = computed(() => fileKind(this.mimeType(), this.name()));

  readonly icon = computed(() => fileKindIcon(this.kind()));

  readonly previewable = computed(() => this.previewEnabled() && canPreview(this.mimeType(), this.name()));

  readonly sizeText = computed(() => formatFileSize(this.size(), this.i18n.locale()));
}
