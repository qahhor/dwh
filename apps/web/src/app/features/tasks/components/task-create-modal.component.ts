import { ChangeDetectionStrategy, Component, inject, input, linkedSignal, output } from '@angular/core';

import { SMTDataSelectComponent, SMTMultiDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { TaskLookupsService } from '../services/task-lookups.service';
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { ProjectOptionsPipe } from './project-options.pipe';
import { SMTDatePickerComponent } from '@shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { UiMarkdownEditorComponent } from '@shared/ui/ui-markdown-editor.component';
import { UiCustomFieldsComponent } from '@shared/ui/ui-custom-fields.component';
import { CustomField } from '@core/models/custom-field.models';
import { Project, TaskType } from '@core/models/task.models';
import { TaskCreateFormValue, createDefaultTaskCreateForm } from '../tasks.models';

@Component({
  selector: 'app-task-create-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTDatePickerComponent,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    SMTSelectComponent,
    SMTDataSelectComponent,
    SMTRadioGroupComponent,
    SMTMultiDataSelectComponent,
    ProjectOptionsPipe,
    SMTInputComponent,
    UiMarkdownEditorComponent,
    UiCustomFieldsComponent,
  ],
  templateUrl: './task-create-modal.component.html',
  styleUrl: './task-create-modal.component.css',
})
export class TaskCreateModalComponent {
  /** The pickers' sources and the people and parent the task's card already named. */
  readonly lookups = inject(TaskLookupsService);
  private readonly i18n = inject(I18nService);

  readonly isOpen = input(false);
  readonly isSubmitting = input(false);
  readonly isCreateSubmitted = input(false);
  readonly taskTypes = input<TaskType[]>([]);
  readonly projects = input<Project[]>([]);
  readonly createForm = input<TaskCreateFormValue>(createDefaultTaskCreateForm());
  readonly taskCustomFields = input<CustomField[]>([]);

  readonly closeModal = output<void>();
  readonly submitForm = output<void>();

  /**
   * The title was visited this opening (a new form object is a new opening). ngModel's required
   * validator used to make smt-control say "required" for an empty visited field; the template now does.
   */
  readonly titleTouched = linkedSignal({ source: this.createForm, computation: () => false });

  private typeCache: { types: TaskType[]; options: SMTRadioOption<string>[] } | null = null;
  private priorityCache: { lang: string; options: SMTRadioOption<string>[] } | null = null;

  /** Task types as chips, each icon in the type's colour; the same array while the types stay the same. */
  typeOptions(): SMTRadioOption<string>[] {
    const taskTypes = this.taskTypes();
    if (this.typeCache?.types !== taskTypes) {
      this.typeCache = {
        types: taskTypes,
        options: taskTypes.map((type) => ({
          value: type.code,
          label: type.name,
          icon: type.icon,
          color: type.color,
        })),
      };
    }
    return this.typeCache.options;
  }

  /** Priorities as one segmented bar; a chosen high or critical reads in its warning colour. */
  priorityOptions(): SMTRadioOption<string>[] {
    const lang = this.i18n.currentLang();
    if (this.priorityCache?.lang !== lang) {
      this.priorityCache = {
        lang,
        options: [
          { value: 'low', label: this.i18n.translate('task.priority.low'), tone: 'success' },
          { value: 'medium', label: this.i18n.translate('tasks.sredniy') },
          { value: 'high', label: this.i18n.translate('task.priority.high'), tone: 'warning' },
          { value: 'critical', label: this.i18n.translate('tasks.kriticheskiy'), tone: 'danger' },
        ],
      };
    }
    return this.priorityCache.options;
  }
}
