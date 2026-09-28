import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { UiCustomFieldsComponent } from '@shared/ui/ui-custom-fields.component';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent, SMTInputValueAccessor } from '@shared/ui-kit/components/forms/input';
import { SMTTextareaComponent, SMTTextareaValueAccessor } from '@shared/ui-kit/components/forms/textarea';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { Project } from '@core/models/task.models';
import { CustomField } from '@core/models/custom-field.models';
import { ProjectCreateForm, ProjectEditForm, ProjectAttributeItem } from '../projects.models';
import { RecordAttributes } from '@features/tasks/tasks.models';

@Component({
  selector: 'app-project-modals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTInputComponent,
    SMTInputValueAccessor,
    SMTTextareaComponent,
    SMTTextareaValueAccessor,
    SMTSelectComponent,
    SMTSelectValueAccessor,
    FormsModule,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    UiCustomFieldsComponent,
  ],
  templateUrl: './project-modals.component.html',
  styleUrl: './project-modals.component.css',
})
export class ProjectModalsComponent {
  private readonly uiI18n = inject(I18nService);

  readonly viewingProject = input<Project | null>(null);
  readonly recordLoading = input(false);
  readonly recordError = input(false);
  readonly recordNotFound = input(false);

  // Create Modal
  readonly isCreateModalOpen = input(false);
  readonly isCreateSubmitted = input(false);
  readonly isCreateDiscardConfirmationOpen = input(false);

  // Edit Modal
  readonly isEditModalOpen = input(false);
  readonly isEditSubmitted = input(false);
  readonly isEditDiscardConfirmationOpen = input(false);
  readonly editLoading = input(false);
  readonly editLoadError = input(false);
  readonly editingProject = input<Project | null>(null);

  // Shared
  readonly isSubmitting = input(false);

  // Record View
  readonly routeRecordId = input<string | null>(null);
  readonly createSaveError = input<string | null>(null);
  readonly createForm = input<ProjectCreateForm>({ name: '', description: '', attributes: {} });
  readonly editSaveError = input<string | null>(null);
  readonly editForm = input<ProjectEditForm>({ name: '', description: '', state: 'A', attributes: {} });
  readonly projectCustomFields = input<CustomField[]>([]);

  readonly closeRecordView = output<void>();
  readonly loadRecordView = output<string | null>();
  readonly requestCloseCreate = output<void>();
  readonly confirmDiscardCreate = output<void>();
  readonly submitCreateProject = output<void>();
  readonly requestCloseEdit = output<void>();
  readonly confirmDiscardEdit = output<void>();
  readonly submitEditProject = output<void>();
  readonly retryEditLoad = output<void>();

  // Discard helper
  readonly cancelNavigationDiscard = output<'create' | 'edit'>();

  private readonly stateMemo = optionsMemo<SMTSelectOption<'A' | 'P'>[]>();

  /** Project states for the edit form; translated again when the language changes. */
  stateOptions(): SMTSelectOption<'A' | 'P'>[] {
    return this.stateMemo([this.uiI18n.currentLang()], () => [
      { id: 'A', label: this.uiI18n.translate('projects.state_active') },
      { id: 'P', label: this.uiI18n.translate('projects.state_archived') },
    ]);
  }

  hasAttributes(attrs: RecordAttributes): attrs is Record<string, unknown> {
    if (!attrs || typeof attrs !== 'object') return false;
    return Object.keys(attrs).length > 0;
  }

  formatAttributes(attrs: RecordAttributes): ProjectAttributeItem[] {
    if (!this.hasAttributes(attrs)) return [];
    const fields = this.projectCustomFields();
    return Object.entries(attrs).map(([k, v]) => {
      const field = fields.find((f) => f.code === k);
      const keyLabel = field ? field.name : k;
      let valueStr = String(v ?? '');
      if (field?.fieldType === 'boolean') {
        valueStr =
          v === true || v === 'true' ? this.uiI18n.translate('common.yes') : this.uiI18n.translate('common.no');
      } else if (field?.fieldType === 'select' && field.optionsJson) {
        try {
          const opts = JSON.parse(field.optionsJson);
          if (Array.isArray(opts)) {
            const matched = opts.find((o) => (typeof o === 'object' && o !== null ? o.value === v : o === v));
            if (matched && typeof matched === 'object' && matched.label) {
              valueStr = matched.label;
            }
          }
        } catch {
          // ignore
        }
      }
      return { key: keyLabel, value: valueStr };
    });
  }
}
