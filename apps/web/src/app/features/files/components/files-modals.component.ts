import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TaskFile } from '@core/models/task.models';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFileUploadComponent } from '@shared/ui/ui-file-upload.component';
import { TranslatePipe } from '@core/services/i18n.service';

@Component({
  selector: 'app-files-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTDialogComponent,
    SMTDialogContentDirective,
    UiFormActionsComponent,
    UiFileUploadComponent,
    TranslatePipe,
  ],
  template: `
    <!-- Upload Modal -->
    <smt-dialog
      [open]="isUploadModalOpen()"
      [smtTitle]="'files.details.upload_title' | t"
      smtSize="md"
      (closed)="closeUpload.emit()"
    >
      <ng-template smtDialogContent>
        <div body class="upload-modal-body">
          <ui-file-upload
            [files]="uploadedBatch()"
            [canUpload]="true"
            [canDelete]="true"
            (fileAttached)="batchFileUploaded.emit($event)"
            (fileRemoved)="batchFileRemoved.emit($event)"
          ></ui-file-upload>
        </div>
        <ui-form-actions
          footer
          data-testid="files-upload-actions"
          [showCancel]="false"
          [submitLabel]="'common.close' | t"
          (submitted)="closeUpload.emit()"
        />
      </ng-template>
    </smt-dialog>
  `,
  styles: [
    `
      :host {
        display: contents;
      }

      .upload-modal-body {
        padding: 8px 0;
      }
    `,
  ],
})
export class FilesModalsComponent {
  readonly isUploadModalOpen = input(false);
  readonly uploadedBatch = input<TaskFile[]>([]);

  readonly closeUpload = output<void>();
  readonly batchFileUploaded = output<TaskFile>();
  readonly batchFileRemoved = output<TaskFile>();
}
