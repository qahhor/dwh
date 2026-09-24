import { Component, DestroyRef, EventEmitter, Input, Output, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { SMTDropzoneComponent } from '../ui-kit/components/dropzone';
import { TaskFile } from '../../core/models/task.models';
import { ToastService } from '../../core/services/toast.service';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

/** One file in the upload queue. */
export interface QueuedUpload {
  readonly id: number;
  readonly file: File;
  readonly status: 'queued' | 'uploading' | 'failed';
  readonly progress: number;
  readonly error?: string;
}

@Component({
  selector: 'ui-file-upload',
  standalone: true,
  imports: [
    TranslatePipe, CommonModule, SMTDropzoneComponent],
  template: `
    <div class="file-upload-wrapper">
      <!-- Picking files: the shared dropzone (a label for a real file input). -->
      <smt-dropzone
        *ngIf="canUpload"
        [smtMultiple]="multiple"
        [smtHint]="'ui.file_upload.do_50_mb_na_fayl_pdf_png_jpg_docx_zip_i_dr' | t"
        (filesSelected)="uploadFiles($event)"
      ></smt-dropzone>

      <!-- Upload queue: one file at a time, each with its own progress and actions. -->
      <ul class="upload-queue" *ngIf="queue().length > 0" [attr.aria-label]="'ui.file_upload.queue' | t">
        <li *ngFor="let item of queue(); trackBy: trackQueued" class="queue-item" [class.failed]="item.status === 'failed'">
          <span class="queue-name">{{ item.file.name }}</span>
          <div
            *ngIf="item.status === 'uploading'"
            class="queue-progress"
            role="progressbar"
            [attr.aria-label]="'ui.file_upload.upload_progress' | t:{progress: item.progress}"
            aria-valuemin="0"
            aria-valuemax="100"
            [attr.aria-valuenow]="item.progress"
            [attr.aria-valuetext]="item.progress + '%'"
          >
            <div class="progress-track"><div class="progress-fill" [style.width.%]="item.progress"></div></div>
          </div>
          <span *ngIf="item.status === 'queued'" class="queue-state">{{ 'ui.file_upload.queued' | t }}</span>
          <span *ngIf="item.status === 'failed'" class="queue-state queue-error">{{ item.error }}</span>
          <div class="file-actions">
            <button
              *ngIf="item.status === 'failed'"
              type="button"
              class="action-btn"
              (click)="retry(item)"
              [attr.aria-label]="'ui.file_upload.retry_named' | t:{name: item.file.name}"
            >
              <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
            </button>
            <button
              type="button"
              class="action-btn delete"
              (click)="cancel(item)"
              [attr.aria-label]="(item.status === 'failed' ? 'ui.file_upload.dismiss_named' : 'ui.file_upload.cancel_named') | t:{name: item.file.name}"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </div>
        </li>
      </ul>

      <!-- File Attachment List -->
      <div class="attachments-list" *ngIf="files && files.length > 0" role="list" [attr.aria-label]="'ui.file_upload.prikreplennye_fayly' | t">
        <div *ngFor="let file of files" class="file-card" role="listitem">
          <div class="file-type-icon" [ngClass]="getFileCategory(file.mimeType, file.fileName)">
            <span class="material-symbols-outlined" aria-hidden="true">{{ getFileIcon(file.mimeType, file.fileName) }}</span>
          </div>
          <button type="button" class="file-info" (click)="downloadFile(file)" [attr.aria-label]="'ui.file_upload.download_named' | t:{name: file.fileName}" [title]="'files.skachat_fayl' | t">
            <span class="file-name">{{ file.fileName }}</span>
            <span class="file-size">{{ formatBytes(file.sizeBytes) }}</span>
          </button>
          <div class="file-actions">
            <button type="button" class="action-btn download" (click)="downloadFile(file)" [attr.aria-label]="'ui.file_upload.download_named' | t:{name: file.fileName}" [title]="'files.skachat' | t">
              <span class="material-symbols-outlined" aria-hidden="true">download</span>
            </button>
            <button
              *ngIf="canDelete"
              type="button"
              class="action-btn delete"
              (click)="removeFile(file, $event)"
              [attr.aria-label]="'ui.file_upload.delete_named' | t:{name: file.fileName}"
              [title]="'ui.file_upload.udalit_vlozhenie' | t"
            >
              <span class="material-symbols-outlined" aria-hidden="true">delete</span>
            </button>
          </div>
        </div>
      </div>

      <div *ngIf="(!files || files.length === 0) && !canUpload" class="empty-files">
        <span class="material-symbols-outlined empty-icon" aria-hidden="true">attach_file</span>
        <span>{{ 'ui.file_upload.net_prikreplennyh_faylov' | t }}</span>
      </div>
    </div>
  `,
  styles: [`
    .file-upload-wrapper {
      display: flex;
      flex-direction: column;
      gap: 12px;
      width: 100%;
    }

    .upload-queue {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .queue-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 12px;
    }

    .queue-item.failed {
      border-color: var(--danger-border);
    }

    .queue-name {
      flex: 0 1 40%;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .queue-progress {
      flex: 1;
    }

    .queue-state {
      flex: 1;
      color: var(--text-muted);
    }

    .queue-error {
      color: var(--danger-text);
    }

    .progress-track {
      height: 6px;
      overflow: hidden;
      border-radius: 999px;
      background-color: var(--bg-hover);
    }

    .progress-fill {
      height: 100%;
      background-color: var(--primary);
      transition: width 0.15s ease;
    }

    .attachments-list {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 8px;
    }

    .file-card {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      transition: background 0.15s ease;
    }

    .file-card:hover {
      background: var(--bg-hover);
    }

    .file-type-icon {
      width: 32px;
      height: 32px;
      border-radius: var(--radius-xs);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
      flex-shrink: 0;
    }

    .file-type-icon.image { background: var(--primary-subtle); color: var(--primary); }
    .file-type-icon.pdf { background: var(--danger-bg); color: var(--danger-text); }
    .file-type-icon.doc { background: var(--info-bg); color: var(--info-text); }
    .file-type-icon.sheet { background: var(--success-bg); color: var(--success-text); }
    .file-type-icon.archive { background: var(--warning-bg); color: var(--warning-text); }
    .file-type-icon.other { background: var(--bg-hover); color: var(--text-muted); }

    .file-info {
      flex: 1;
      min-width: 0;
      cursor: pointer;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      border: 0;
      padding: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;
    }

    .file-name {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .file-size {
      font-size: 11px;
      color: var(--text-muted);
    }

    .file-actions {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .action-btn {
      width: 28px;
      height: 28px;
      border-radius: var(--radius-xs);
      border: none;
      background: transparent;
      color: var(--text-muted);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .action-btn .material-symbols-outlined {
      font-size: 18px;
    }

    .action-btn.download:hover {
      background: var(--primary-subtle);
      color: var(--primary);
    }

    .action-btn.delete:hover {
      background: var(--danger-bg);
      color: var(--danger-text);
    }

    .empty-files {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: var(--text-muted);
      padding: 8px 0;
    }

    .empty-icon {
      font-size: 18px;
    }
  `]
})
export class UiFileUploadComponent {
  private readonly uiI18n = inject(I18nService);

  @Input() files: TaskFile[] = [];
  @Input() canUpload = true;
  @Input() canDelete = true;
  @Input() multiple = true;

  @Output() fileAttached = new EventEmitter<TaskFile>();
  @Output() fileRemoved = new EventEmitter<TaskFile>();

  /** Files waiting, uploading or failed; a file leaves the queue once it is attached. */
  readonly queue = signal<QueuedUpload[]>([]);
  private nextQueueId = 0;
  private current: Subscription | null = null;
  private readonly destroyRef = inject(DestroyRef);

  constructor(
    private http: HttpClient,
    private toast: ToastService
  ) {
    this.destroyRef.onDestroy(() => this.current?.unsubscribe());
  }

  uploadFiles(filesToUpload: File[]) {
    const added = filesToUpload.map(file => ({ id: this.nextQueueId++, file, status: 'queued' as const, progress: 0 }));
    this.queue.update(queue => [...queue, ...added]);
    this.pump();
  }

  retry(item: QueuedUpload) {
    this.patch(item.id, { status: 'queued', progress: 0, error: undefined });
    this.pump();
  }

  /** Cancels a queued or running upload, or dismisses a failed one. */
  cancel(item: QueuedUpload) {
    if (item.status === 'uploading') {
      this.current?.unsubscribe();
      this.current = null;
    }
    this.queue.update(queue => queue.filter(entry => entry.id !== item.id));
    this.pump();
  }

  trackQueued(_: number, item: QueuedUpload) {
    return item.id;
  }

  /** Starts the next queued file when nothing is uploading. */
  private pump() {
    if (this.current) return;
    const next = this.queue().find(item => item.status === 'queued');
    if (!next) return;
    this.patch(next.id, { status: 'uploading', progress: 0 });

    const formData = new FormData();
    formData.append('file', next.file);
    this.current = this.http.post<any>('/api/v1/files/upload', formData, {
      reportProgress: true,
      observe: 'events',
      withCredentials: true
    }).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.patch(next.id, { progress: Math.round((100 * event.loaded) / event.total) });
        } else if (event.type === HttpEventType.Response) {
          const body = event.body;
          const taskFile: TaskFile = {
            fileId: body.id,
            fileName: body.originalName || next.file.name,
            sizeBytes: body.sizeBytes || next.file.size,
            mimeType: body.mimeType || next.file.type,
            createdAt: body.createdAt || new Date().toISOString()
          };
          this.finish(next.id);
          this.fileAttached.emit(taskFile);
          this.toast.success(this.uiI18n.translate('ui.file_upload.uploaded_named', { name: taskFile.fileName }));
        }
      },
      error: (err) => {
        const msg = err.error?.detail || err.error?.message || this.uiI18n.translate('ui.file_upload.oshibka_zagruzki_fayla');
        this.current = null;
        this.patch(next.id, { status: 'failed', error: msg });
        this.toast.error(msg, this.uiI18n.translate('ui.file_upload.zagruzka_ne_udalas'));
        this.pump();
      }
    });
  }

  private finish(id: number) {
    this.current = null;
    this.queue.update(queue => queue.filter(entry => entry.id !== id));
    this.pump();
  }

  private patch(id: number, changes: Partial<QueuedUpload>) {
    this.queue.update(queue => queue.map(entry => (entry.id === id ? { ...entry, ...changes } : entry)));
  }

  downloadFile(file: TaskFile) {
    window.open(`/api/v1/files/${file.fileId}/download`, '_blank', 'noopener,noreferrer');
  }

  removeFile(file: TaskFile, event: Event) {
    event.stopPropagation();
    this.fileRemoved.emit(file);
  }

  getFileCategory(mimeType?: string, fileName?: string): string {
    const mime = (mimeType || '').toLowerCase();
    const name = (fileName || '').toLowerCase();

    if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg)$/.test(name)) return 'image';
    if (mime === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
    if (/\.(docx?|odt|rtf|txt|md)$/.test(name)) return 'doc';
    if (/\.(xlsx?|csv|ods)$/.test(name)) return 'sheet';
    if (/\.(zip|tar|gz|rar|7z)$/.test(name)) return 'archive';
    return 'other';
  }

  getFileIcon(mimeType?: string, fileName?: string): string {
    const cat = this.getFileCategory(mimeType, fileName);
    switch (cat) {
      case 'image': return 'image';
      case 'pdf': return 'picture_as_pdf';
      case 'doc': return 'description';
      case 'sheet': return 'table_chart';
      case 'archive': return 'folder_zip';
      default: return 'attach_file';
    }
  }

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
