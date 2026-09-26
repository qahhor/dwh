import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TaskFile } from '../../../core/models/task.models';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../shared/ui-kit/components/modal';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { UiFileUploadComponent } from '../../../shared/ui/ui-file-upload.component';
import { TranslatePipe } from '../../../core/services/i18n.service';

@Component({
  selector: 'app-files-modals',
  standalone: true,
  imports: [
    CommonModule,
    SMTDialogComponent, SMTDialogContentDirective,
    SMTButtonComponent,
    UiFileUploadComponent,
    TranslatePipe
  ],
  template: `
    <!-- Upload Modal -->
    <smt-dialog
      [open]="isUploadModalOpen"
      [smtTitle]="'files.zagruzka_faylov_v_hranilische' | t"
      smtSize="md"
      (closed)="closeUpload.emit()">
      <ng-template smtDialogContent>
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
        <button smt-button type="button" smtVariant="secondary" (click)="closeUpload.emit()">{{ 'audit.zakryt' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>
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
