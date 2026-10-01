import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  model,
  signal,
} from '@angular/core';
import type { Subscription } from 'rxjs';
import { fileId, type FileValue } from '@core/services/field-values';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '../ui-kit/components/button';
import { SMTControlComponent } from '../ui-kit/components/forms/control/control.component';
import { SMTDropzoneComponent } from '../ui-kit/components/dropzone';
import { EntitiesApi } from './entities.api';
import { fileName } from './entity-values';

/**
 * A file or image field (ADR-0032 4.7, plan 10/10, item 5.2): the person picks a file, the files module checks and
 * stores it, and the field holds its id; the save attaches it to the record. A chosen file shows its name with a
 * link through its record (when the record exists) and a button to take it away. No thumbnails are made (ADR-0032
 * §19, B5): an image opens as it is.
 */
@Component({
  selector: 'smt-file-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, SMTControlComponent, SMTDropzoneComponent, TranslatePipe],
  host: { class: 'smt-file-field' },
  template: `
    <smt-control [smtLabel]="label()" [required]="required()" [smtError]="error() || failure()">
      @if (chosen(); as file) {
        <div class="file-chosen" role="group">
          <span class="material-symbols-outlined" aria-hidden="true">{{ image() ? 'image' : 'description' }}</span>
          @if (href(); as url) {
            <a class="file-name" [href]="url" target="_blank" rel="noopener">{{ file }}</a>
          } @else {
            <span class="file-name">{{ file }}</span>
          }
          <button
            smt-button
            type="button"
            smtVariant="ghost"
            smtSize="sm"
            smtIcon="close"
            [disabled]="disabled()"
            [attr.aria-label]="'ui.entity_form.file_remove' | t: { name: file }"
            (click)="value.set(null)"
          ></button>
        </div>
      } @else {
        <smt-dropzone
          [smtAccept]="accept()"
          [smtMaxBytes]="maxBytes() ?? 0"
          [smtHint]="hint()"
          [disabled]="disabled() || uploading()"
          (filesSelected)="upload($event)"
        />
      }
      @if (uploading()) {
        <p class="file-status" role="status">{{ 'ui.entity_form.file_uploading' | t }}</p>
      }
    </smt-control>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .file-chosen {
        display: flex;
        align-items: center;
        gap: 8px;
        min-width: 0;
      }
      .file-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .file-status {
        margin: 4px 0 0;
        color: var(--text-muted);
        font-size: 0.8125rem;
      }
    `,
  ],
})
export class SMTFileFieldComponent {
  private readonly api = inject(EntitiesApi);

  private readonly i18n = inject(I18nService);

  readonly label = input.required<string>();

  /** An image takes PNG, JPEG and WebP; a file any type unless `contentTypes` says otherwise. */
  readonly image = input(false, { transform: booleanAttribute });

  readonly contentTypes = input<readonly string[]>([]);

  readonly maxBytes = input<number | null>(null);

  readonly required = input(false, { transform: booleanAttribute });

  readonly disabled = input(false, { transform: booleanAttribute });

  readonly error = input('');

  /** The record's entity and id, so a chosen file opens through its record; none while the record is new. */
  readonly entity = input<string | null>(null);

  readonly recordId = input<number | null>(null);

  /** The file the field holds: its id, or the file a record reads back. */
  readonly value = model<FileValue | string | null>(null);

  readonly uploading = signal(false);

  readonly failure = signal('');

  readonly accept = computed(() => this.contentTypes().join(','));

  readonly hint = computed(() =>
    this.contentTypes().length ? this.i18n.translate('ui.entity_form.file_types', { types: this.accept() }) : '',
  );

  readonly chosen = computed(() => {
    const value = this.value();
    return fileId(value) === null ? null : fileName(value);
  });

  readonly href = computed(() => {
    const id = fileId(this.value());
    const entity = this.entity();
    const record = this.recordId();
    return id && entity && record ? this.api.fileUrl(entity, record, id) : null;
  });

  private request: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
  }

  upload(files: File[]): void {
    const file = files[0];
    if (!file) return;
    this.uploading.set(true);
    this.failure.set('');
    this.request?.unsubscribe();
    this.request = this.api.uploadFile(file).subscribe({
      next: (stored) => {
        this.uploading.set(false);
        this.value.set(stored);
      },
      error: (problem: { detail?: string }) => {
        this.uploading.set(false);
        this.failure.set(problem?.detail || this.i18n.translate('ui.entity_form.file_failed'));
      },
    });
  }
}
