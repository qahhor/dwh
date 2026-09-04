import { Component, EventEmitter, Input, Output, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { TaskFile } from '../../core/models/task.models';
import { ToastService } from '../../core/services/toast.service';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

@Component({
  selector: 'ui-file-upload',
  standalone: true,
  imports: [
    TranslatePipe,CommonModule],
  template: `
    <div class="file-upload-wrapper">
      <!-- Drag & Drop Upload Zone -->
      <label
        *ngIf="canUpload"
        class="drop-zone"
        [for]="fileInputId"
        [class.dragging]="isDragging()"
        (dragover)="onDragOver($event)"
        (dragleave)="onDragLeave($event)"
        (drop)="onDrop($event)"
      >
        <input
          [id]="fileInputId"
          type="file"
          [multiple]="multiple"
          class="sr-only"
          (change)="onFilesSelected($event)"
        />
        <div class="drop-content">
          <span class="material-symbols-outlined drop-icon" aria-hidden="true">cloud_upload</span>
          <div class="drop-text">
            <span class="primary-text">{{ 'ui.file_upload.peretaschite_fayly_syuda_ili' | t }} <strong>{{ 'ui.file_upload.nazhmite_dlya_vybora' | t }}</strong></span>
            <span class="sub-text">{{ 'ui.file_upload.do_50_mb_na_fayl_pdf_png_jpg_docx_zip_i_dr' | t }}</span>
          </div>
        </div>
      </label>

      <!-- Upload Progress Indicator -->
      <div
        *ngIf="isUploading()"
        class="upload-progress-bar"
        role="progressbar"
        [attr.aria-label]="'ui.file_upload.zagruzka_faylov' | t"
        aria-valuemin="0"
        aria-valuemax="100"
        [attr.aria-valuenow]="uploadProgress()"
        [attr.aria-valuetext]="uploadProgress() + '%'"
      >
        <div class="progress-track">
          <div class="progress-fill" [style.width.%]="uploadProgress()"></div>
        </div>
        <span class="progress-label">{{ 'ui.file_upload.upload_progress' | t:{progress: uploadProgress()} }}</span>
      </div>

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

    .drop-zone {
      border: 2px dashed var(--border-color);
      border-radius: var(--radius-md);
      padding: 20px;
      text-align: center;
      cursor: pointer;
      background: var(--bg-hover);
      transition: all 0.15s ease;
      user-select: none;
    }

    .drop-zone:hover, .drop-zone.dragging {
      border-color: var(--primary);
      background: var(--primary-subtle);
    }

    .drop-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
    }

    .drop-icon {
      font-size: 32px;
      color: var(--primary);
    }

    .drop-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .primary-text {
      font-size: 13px;
      color: var(--text-main);
    }

    .primary-text strong {
      color: var(--primary);
    }

    .sub-text {
      font-size: 11px;
      color: var(--text-muted);
    }

    /* Progress bar */
    .upload-progress-bar {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .progress-track {
      flex: 1;
      height: 6px;
      background: var(--bg-hover);
      border-radius: var(--radius-xs);
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      background: var(--primary);
      transition: width 0.15s ease;
    }

    .progress-label {
      font-size: 12px;
      color: var(--text-muted);
    }

    /* Attachments list */
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
    .file-type-icon.pdf { background: var(--danger-bg); color: var(--danger); }
    .file-type-icon.doc { background: var(--info-bg); color: var(--info); }
    .file-type-icon.sheet { background: var(--success-bg); color: var(--success); }
    .file-type-icon.archive { background: var(--warning-bg); color: var(--warning); }
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
      color: var(--danger);
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
  private static nextId = 0;

  @Input() files: TaskFile[] = [];
  @Input() canUpload = true;
  @Input() canDelete = true;
  @Input() multiple = true;

  @Output() fileAttached = new EventEmitter<TaskFile>();
  @Output() fileRemoved = new EventEmitter<TaskFile>();

  readonly isDragging = signal<boolean>(false);
  readonly isUploading = signal<boolean>(false);
  readonly uploadProgress = signal<number>(0);
  readonly fileInputId = `ui-file-upload-${UiFileUploadComponent.nextId++}`;

  constructor(
    private http: HttpClient,
    private toast: ToastService
  ) {}

  onDragOver(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragging.set(false);

    if (event.dataTransfer && event.dataTransfer.files.length > 0) {
      this.uploadFiles(Array.from(event.dataTransfer.files));
    }
  }

  onFilesSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadFiles(Array.from(input.files));
      input.value = '';
    }
  }

  uploadFiles(filesToUpload: File[]) {
    for (const file of filesToUpload) {
      this.uploadSingleFile(file);
    }
  }

  private uploadSingleFile(file: File) {
    const formData = new FormData();
    formData.append('file', file);

    this.isUploading.set(true);
    this.uploadProgress.set(0);

    this.http.post<any>('/api/v1/files/upload', formData, {
      reportProgress: true,
      observe: 'events',
      withCredentials: true
    }).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.uploadProgress.set(Math.round((100 * event.loaded) / event.total));
        } else if (event.type === HttpEventType.Response) {
          this.isUploading.set(false);
          const body = event.body;
          const taskFile: TaskFile = {
            fileId: body.id,
            fileName: body.originalName || file.name,
            sizeBytes: body.sizeBytes || file.size,
            mimeType: body.mimeType || file.type,
            createdAt: body.createdAt || new Date().toISOString()
          };
          this.fileAttached.emit(taskFile);
          this.toast.success(this.uiI18n.translate('ui.file_upload.uploaded_named', { name: taskFile.fileName }));
        }
      },
      error: (err) => {
        this.isUploading.set(false);
        const msg = err.error?.detail || err.error?.message || this.uiI18n.translate('ui.file_upload.oshibka_zagruzki_fayla');
        this.toast.error(msg, this.uiI18n.translate('ui.file_upload.zagruzka_ne_udalas'));
      }
    });
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
