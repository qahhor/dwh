/* Our code, after the idea of the kit's `smt-preview` (smartup-ui-kit@6472beb,
 * components/preview). See ADR-0015 rule 2 and NOTICE.
 *
 * Why not the kit's copy: it embedded documents through OnlyOffice URLs
 * and videos, with zoom, rotate and pan; our API serves files only as
 * attachments and forbids framing, which keeps an uploaded HTML or PDF from
 * running inside the application.
 *
 * So this previews images — drawn in an <img>, where nothing in the file can
 * run — one at a time in a dialog: its name, "n of m", previous and next
 * (buttons and the arrow keys), download, and a note with a download button
 * when an image cannot be read. Open it through SMTFilePreviewService. */
import { ChangeDetectionStrategy, Component, computed, inject, Injectable, signal, ViewEncapsulation } from '@angular/core';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { SMTI18nService } from '../../i18n';
import { SMTModalService } from '../modal/modal.service';
import { canPreview } from './file-kind';

export interface SMTPreviewFile {
  readonly name: string;
  /** Where the browser loads the image from, e.g. `/api/v1/files/{id}/download`. */
  readonly url: string;
  readonly mimeType?: string | null;
}

interface PreviewData {
  readonly files: readonly SMTPreviewFile[];
  readonly index: number;
  readonly download: (file: SMTPreviewFile) => void;
  readonly titleId: string;
}

let nextPreviewId = 0;

@Component({
  selector: 'smt-file-preview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './file-preview.scss',
  host: {
    class: 'smt-file-preview',
    '(keydown)': 'onKeydown($event)',
  },
  template: `
    <header class="smt-file-preview__header">
      <h2 class="smt-file-preview__title" [id]="titleId">{{ current().name }}</h2>
      @if (files.length > 1) {
        <span class="smt-file-preview__count">{{ i18n.messages().file.position(index() + 1, files.length) }}</span>
      }
      <button type="button" class="smt-file-preview__button" [attr.aria-label]="i18n.messages().file.download(current().name)" (click)="download()">
        <span class="material-symbols-outlined" aria-hidden="true">download</span>
      </button>
      <button type="button" class="smt-file-preview__button" cdkFocusInitial [attr.aria-label]="i18n.messages().common.close" (click)="close()">
        <span class="material-symbols-outlined" aria-hidden="true">close</span>
      </button>
    </header>
    <div class="smt-file-preview__stage">
      @if (files.length > 1) {
        <button type="button" class="smt-file-preview__nav" [disabled]="index() === 0" [attr.aria-label]="i18n.messages().file.previous" (click)="go(-1)">
          <span class="material-symbols-outlined" aria-hidden="true">chevron_left</span>
        </button>
      }
      @if (failed()) {
        <div class="smt-file-preview__failed" role="alert">
          <p>{{ i18n.messages().file.cannotShow }}</p>
          <button type="button" class="smt-modal-button smt-modal-button--secondary" (click)="download()">{{ i18n.messages().file.downloadInstead }}</button>
        </div>
      } @else {
        <img class="smt-file-preview__image" [src]="current().url" [alt]="current().name" (error)="failed.set(true)" />
      }
      @if (files.length > 1) {
        <button type="button" class="smt-file-preview__nav" [disabled]="index() === files.length - 1" [attr.aria-label]="i18n.messages().file.next" (click)="go(1)">
          <span class="material-symbols-outlined" aria-hidden="true">chevron_right</span>
        </button>
      }
    </div>
  `,
})
export class SMTFilePreviewComponent {
  readonly i18n = inject(SMTI18nService);

  private readonly data = inject<PreviewData>(DIALOG_DATA);

  private readonly dialogRef = inject(DialogRef);

  readonly index = signal(Math.min(Math.max(this.data.index, 0), this.data.files.length - 1));

  readonly failed = signal(false);

  readonly current = computed(() => this.files[this.index()]);

  readonly files = this.data.files;

  readonly titleId = this.data.titleId;

  go(delta: -1 | 1): void {
    const next = this.index() + delta;
    if (next < 0 || next >= this.files.length) return;
    this.index.set(next);
    this.failed.set(false);
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft') this.go(-1);
    if (event.key === 'ArrowRight') this.go(1);
  }

  download(): void {
    this.data.download(this.current());
  }

  close(): void {
    this.dialogRef.close();
  }
}

/** Opens the image preview; only files it can show are taken, the one asked for first in view. */
@Injectable({ providedIn: 'root' })
export class SMTFilePreviewService {
  private readonly modal = inject(SMTModalService);

  open(files: readonly SMTPreviewFile[], chosen: SMTPreviewFile, download: (file: SMTPreviewFile) => void): void {
    const images = files.filter(file => canPreview(file.mimeType, file.name));
    const index = images.indexOf(chosen);
    if (index < 0) return;
    const titleId = `smt-file-preview-title-${nextPreviewId++}`;
    this.modal.open<SMTFilePreviewComponent>(SMTFilePreviewComponent, {
      data: { files: images, index, download, titleId } satisfies PreviewData,
      ariaLabelledBy: titleId,
      width: 'min(960px, calc(100vw - 32px))',
      maxHeight: 'calc(100vh - 32px)',
    });
  }
}
