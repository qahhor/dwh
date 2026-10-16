import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
} from '@angular/core';
import { ProblemFieldErrors } from '@shared/ui/problem-fields';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFormErrorSummaryComponent } from '@shared/ui/ui-form-error-summary.component';
import { UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { NO_FIELD_ERRORS, TASK_CREATE_FORM_ID } from '../services/task-forms.service';
import { clearTaskFieldError, taskErrorSummary } from '../task-form-errors';

import { SMTDataSelectComponent, SMTMultiDataSelectComponent } from '@shared/ui-kit/components/forms/data-select';
import { TaskLookupsService } from '../services/task-lookups.service';
import { SMTRadioGroupComponent, SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import { SMTDatePickerComponent } from '@shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiMarkdownEditorComponent } from '@shared/ui/ui-markdown-editor.component';
import { UiCustomFieldsComponent } from '@shared/ui/ui-custom-fields.component';
import { CustomField } from '@core/models/custom-field.models';
import { TaskType } from '@core/models/task.models';
import { TaskCreateFormValue, createDefaultTaskCreateForm } from '../tasks.models';

const ADDITIONAL_FIELDS = ['taskType', 'priority', 'parentTaskId', 'executorUserIds', 'observerUserIds'] as const;

@Component({
  selector: 'app-task-create-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTControlComponent,
    SMTDatePickerComponent,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTDataSelectComponent,
    UiFormActionsComponent,
    UiFormErrorSummaryComponent,
    UiFocusFirstInvalidDirective,
    SMTRadioGroupComponent,
    SMTMultiDataSelectComponent,
    SMTInputComponent,
    SMTButtonComponent,
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
  readonly createForm = input<TaskCreateFormValue>(createDefaultTaskCreateForm());
  readonly taskCustomFields = input<CustomField[]>([]);

  readonly closeModal = output<void>();
  readonly submitForm = output<void>();

  /** The server's refusal of the last save by field; `other` goes to the error summary. */
  readonly serverErrors = model<ProblemFieldErrors>(NO_FIELD_ERRORS);

  /** The title was visited this opening (a new form object is a new opening). */
  readonly titleTouched = linkedSignal({ source: this.createForm, computation: () => false });

  /** A new draft starts compact; a refused secondary field stays visible until corrected. */
  readonly additionalOpen = linkedSignal<{ form: TaskCreateFormValue; errors: ProblemFieldErrors }, boolean>({
    source: () => ({ form: this.createForm(), errors: this.serverErrors() }),
    computation: ({ form, errors }, previous) => {
      if (ADDITIONAL_FIELDS.some((key) => !!errors.fields[key])) return true;
      return previous?.source.form === form ? previous.value : false;
    },
  });

  readonly hasAdditionalErrors = computed(() => ADDITIONAL_FIELDS.some((key) => !!this.serverErrors().fields[key]));

  readonly summary = computed(() => {
    this.i18n.currentLang();
    return taskErrorSummary(this.serverErrors(), 'task-create', (key) => this.i18n.translate(key));
  });

  readonly formId = TASK_CREATE_FORM_ID;

  private typeCache: { types: TaskType[]; options: SMTRadioOption<string>[] } | null = null;
  private priorityCache: { lang: string; options: SMTRadioOption<string>[] } | null = null;

  /**
   * "Name the task" after a submit with an empty title, "required" once an empty title was left, else the server's
   * word on the title (forms standard, section 4). Read in the template, so the plain form object is seen fresh.
   */
  titleError(): string {
    const title = this.createForm().title;
    if (this.isCreateSubmitted() && !title.trim()) return this.i18n.translate('tasks.editor.title_required_hint');
    if (this.titleTouched() && !title) return this.i18n.translate('ui.control.required');
    return this.serverErrors().fields['title'] ?? '';
  }

  /** Editing a field clears only its previous server refusal. */
  setField<K extends keyof TaskCreateFormValue>(key: K, value: TaskCreateFormValue[K]): void {
    const form = this.createForm();
    if (Object.is(form[key], value)) return;
    form[key] = value;
    this.serverErrors.update((errors) => clearTaskFieldError(errors, key));
  }

  toggleAdditional(): void {
    if (!this.isSubmitting() && !this.hasAdditionalErrors()) this.additionalOpen.update((open) => !open);
  }

  /** The chosen type and priority remain visible even when secondary controls are collapsed. */
  additionalSummary(): string {
    const form = this.createForm();
    const type = this.typeOptions().find((option) => option.value === form.taskType)?.label ?? form.taskType;
    const priority = this.priorityOptions().find((option) => option.value === form.priority)?.label ?? form.priority;
    return [type, priority].filter(Boolean).join(' · ');
  }

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
          { value: 'medium', label: this.i18n.translate('tasks.common.medium') },
          { value: 'high', label: this.i18n.translate('task.priority.high'), tone: 'warning' },
          { value: 'critical', label: this.i18n.translate('tasks.common.critical'), tone: 'danger' },
        ],
      };
    }
    return this.priorityCache.options;
  }
}
