import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TaskFile } from '../../../core/models/task.models';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiFileUploadComponent } from '../../../shared/ui/ui-file-upload.component';
import { TranslatePipe } from '../../../core/services/i18n.service';

@Component({
  selector: 'app-files-modals',
  standalone: true,
  imports: [
    CommonModule,
    UiModalComponent,
    UiButtonComponent,
    UiFileUploadComponent,
    TranslatePipe
  ],
  template: `
    <!-- Upload Modal -->
    <ui-modal
      [isOpen]="isUploadModalOpen"
      [title]="'files.zagruzka_faylov_v_hranilische' | t"
      size="md"
      (close)="closeUpload.emit()"
    >
      <div body class="upload-modal-body">
        <ui-file-upload
          [files]="uploadedBatch"
          [canUpload]="true"
          [canDelete]="true"
          (fileAttached)="batchFileUploaded.emit($event)"
          (fileRemoved)="batchFileRemoved.emit($event)"
        ></ui-file-upload>
      </div>
      <div footer class="modal-footer-actions">
        <ui-button variant="secondary" (onClick)="closeUpload.emit()">{{ 'audit.zakryt' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    :host {
      display: contents;
    }

    .upload-modal-body {
      padding: 8px 0;
    }

    .modal-footer-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
    }
  `]
})
export class FilesModalsComponent {
  @Input() isUploadModalOpen = false;
  @Input() uploadedBatch: TaskFile[] = [];

  @Output() closeUpload = new EventEmitter<void>();
  @Output() batchFileUploaded = new EventEmitter<TaskFile>();
  @Output() batchFileRemoved = new EventEmitter<TaskFile>();
}
