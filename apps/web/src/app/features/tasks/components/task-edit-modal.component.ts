import { Component, inject, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTDataSelectComponent, SMTMultiDataSelectComponent } from '../../../shared/ui-kit/components/forms/data-select';
import { TaskLookupsService } from '../services/task-lookups.service';
import { SMTRadioGroupComponent, SMTRadioOption } from '../../../shared/ui-kit/components/forms/radio-group';
import { TaskRef } from '../../../shared/lookups/lookup-sources';
import { ProjectOptionsPipe } from './project-options.pipe';
import { SMTDatePickerComponent, SMTDatePickerValueAccessor } from '../../../shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../shared/ui-kit/components/forms/control';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { SMTSelectComponent, SMTSelectValueAccessor } from '../../../shared/ui-kit/components/forms/select';
import { UiMarkdownEditorComponent } from '../../../shared/ui/ui-markdown-editor.component';
import { UiCustomFieldsComponent } from '../../../shared/ui/ui-custom-fields.component';
import { CustomField } from '../../../core/models/custom-field.models';
import { Project, Task, TaskType } from '../../../core/models/task.models';

@Component({
  selector: 'app-task-edit-modal',
  standalone: true,
  imports: [
    SMTControlComponent,
    CommonModule,
    FormsModule,
    SMTDatePickerComponent,
    SMTDatePickerValueAccessor,
    TranslatePipe,
    UiModalComponent,
    UiButtonComponent,
    SMTSelectComponent,
    SMTDataSelectComponent,
    SMTRadioGroupComponent,
    SMTMultiDataSelectComponent,
    ProjectOptionsPipe,
    SMTSelectValueAccessor,
    UiMarkdownEditorComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <ui-modal
      [isOpen]="isOpen"
      [title]="'tasks.redaktirovanie_zadachi' | t"
      size="lg"
      [dismissible]="!isSubmitting"
      (close)="close.emit()"
    >
      <div body class="request-state request-loading" *ngIf="editLoading" role="status">
        {{ 'tasks.edit_loading' | t }}
      </div>
      <div body class="request-state request-error" *ngIf="editLoadError" role="alert">
        <span>{{ 'tasks.edit_load_error' | t }}</span>
        <ui-button variant="secondary" size="sm" (onClick)="retryEditLoad.emit()">{{ 'audit.retry' | t }}</ui-button>
      </div>
      <fieldset body class="modal-form modal-form-fieldset task-edit-form" [disabled]="isSubmitting" *ngIf="editingTask as task">
        <!-- Title Input (Required) -->
        <smt-control class="form-group" [smtLabel]="'task.title' | t" [smtError]="isEditSubmitted && !editForm.title.trim() ? ('tasks.nazvanie_zadachi_ne_mozhet_byt_pustym' | t) : ''">
          <input
            id="task-edit-title"
            name="taskEditTitle"
            type="text"
            class="clean-input title-input"
            required
            [(ngModel)]="editForm.title"
          />
        </smt-control>

        <!-- Visual Type Selector Chips -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.tip_zadachi' | t }}</span>
          </div>
          <smt-radio-group
            smtAppearance="chips"
            [options]="typeOptions()"
            [value]="editForm.taskType"
            [smtAriaLabel]="'tasks.tip_zadachi' | t"
            (valueChange)="editForm.taskType = $event ?? editForm.taskType" />
        </div>

        <!-- Visual Priority Selector Pills -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'common.priority' | t }}</span>
          </div>
          <smt-radio-group
            smtAppearance="segmented"
            [options]="priorityOptions()"
            [value]="editForm.priority"
            [smtAriaLabel]="'tasks.prioritet_zadachi' | t"
            (valueChange)="editForm.priority = $event ?? editForm.priority" />
        </div>

        <div class="form-grid-2">
          <!-- Project Selector -->
          <smt-control class="form-group" [smtLabel]="'projects.proekt' | t">
            <smt-select
              smtTriggerId="task-edit-project"
              name="taskEditProject"
              [(ngModel)]="editForm.projectId"
              [options]="projects | projectOptions"
              [placeholder]="'tasks.bez_proekta' | t"
              [searchPlaceholder]="'tasks.search_project' | t"
              [emptyLabel]="'tasks.bez_proekta' | t"
            ></smt-select>
          </smt-control>

          <!-- Parent Task (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'task.parent' | t }}</span>
            </div>
            <smt-data-select
              [source]="lookups.tasks"
              [knownRows]="lookups.knownParentRows()"
              [exclude]="notThisTask"
              [value]="editForm.parentTaskId"
              [ariaLabel]="'task.parent' | t"
              (valueChange)="editForm.parentTaskId = $event"
              [placeholder]="'tasks.bez_roditelya_kornevaya_zadacha' | t"
              [searchPlaceholder]="'tasks.poisk_zadachi_po_id_ili_nazvaniyu' | t"
              [emptyLabel]="'tasks.without_parent' | t" />
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'task.responsible' | t }}</span>
            </div>
            <smt-data-select
              [source]="lookups.users"
              [knownRows]="lookups.knownUserRows()"
              [value]="editForm.responsibleUserId"
              [ariaLabel]="'task.responsible' | t"
              (valueChange)="editForm.responsibleUserId = $event"
              [placeholder]="'tasks.vyberite_otvetstvennogo' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'common.not_assigned' | t" />
          </div>

          <!-- Deadlines: End Date / Deadline -->
          <smt-control class="form-group" [smtLabel]="'tasks.srok_sdachi_dedlayn' | t">
            <smt-date-picker smtInputId="task-edit-deadline" name="taskEditDeadline" smtWithTime [ngModel]="editForm.endTime" (ngModelChange)="editForm.endTime = $event ?? ''" />
          </smt-control>
        </div>

        <!-- Executors Searchable Multi-Select Tags Input -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.soispolniteli' | t }}</span>
          </div>
          <smt-multi-data-select
            [source]="lookups.users"
            [knownRows]="lookups.knownUserRows()"
            [value]="editForm.executorUserIds"
            [ariaLabel]="'tasks.soispolniteli' | t"
            (valueChange)="editForm.executorUserIds = [...$event]"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_soispolniteley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t" />
        </div>

        <!-- Observers Searchable Multi-Select Tags Input -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.nablyudateli_poluchayut_uvedomleniya' | t }}</span>
          </div>
          <smt-multi-data-select
            [source]="lookups.users"
            [knownRows]="lookups.knownUserRows()"
            [value]="editForm.observerUserIds"
            [ariaLabel]="'tasks.nablyudateli' | t"
            (valueChange)="editForm.observerUserIds = [...$event]"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_nablyudateley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t" />
        </div>

        <!-- RichText Markdown Editor for Description -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'projects.opisanie' | t }}</span>
          </div>
          <ui-markdown-editor
            [value]="editForm.descriptionMarkdown"
            [ariaLabel]="'projects.opisanie' | t"
            (valueChange)="editForm.descriptionMarkdown = $event"
            [rows]="4"
          ></ui-markdown-editor>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields.length > 0">
          <h4 class="custom-fields-title">{{ 'nav.custom_fields' | t }}</h4>
          <ui-custom-fields
            [fields]="taskCustomFields"
            [(values)]="editForm.attributes"
          ></ui-custom-fields>
        </div>
      </fieldset>
      <div footer>
        <ui-button variant="secondary" size="md" [disabled]="isSubmitting" (onClick)="close.emit()">{{ editLoadError ? ('audit.zakryt' | t) : ('common.cancel' | t) }}</ui-button>
        <ui-button *ngIf="editingTask" variant="primary" size="md" [loading]="isSubmitting" (onClick)="submit.emit()">{{ 'tasks.sohranit_izmeneniya' | t }}</ui-button>
      </div>
    </ui-modal>

    <ui-modal
      [isOpen]="isEditDiscardConfirmationOpen"
      [title]="'tasks.discard_edit_title' | t"
      size="sm"
      (close)="cancelDiscard.emit()"
    >
      <div body class="dictionary-delete-body">
        <p>{{ 'tasks.discard_edit_message' | t }}</p>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="cancelDiscard.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmDiscard.emit()">{{ 'tasks.discard_edit_action' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .modal-form { display: flex; flex-direction: column; gap: 14px; }
    .modal-form-fieldset { border: none; padding: 0; margin: 0; min-width: 0; }
    .modal-form-fieldset:disabled { opacity: 0.75; }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .label-row { display: flex; align-items: center; justify-content: space-between; }
    .clean-label { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .req-tag { font-size: 10px; color: var(--text-muted); }
    .clean-input {
      height: 34px;
      padding: 6px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.12s ease;
    }
    .clean-input:focus { border-color: var(--primary); }
    .title-input { font-size: 14px; font-weight: 500; }
    .input-error { border-color: var(--danger); }
    .error-msg { font-size: 11px; color: var(--danger); margin-top: 2px; }
    .form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    @media (max-width: 640px) { .form-grid-2 { grid-template-columns: 1fr; } }


    .custom-fields-section {
      border-top: 1px dashed var(--border-color);
      padding-top: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .custom-fields-title { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; display: flex; align-items: center; gap: 6px; }
    .font-mono { font-family: ui-monospace, monospace; }
    .dictionary-delete-body { padding: 10px 0; font-size: 13px; color: var(--text-main); }
  `]
})
export class TaskEditModalComponent {
  /** The pickers' sources and the people and parent the task's card already named. */
  readonly lookups = inject(TaskLookupsService);
  private readonly i18n = inject(I18nService);
  private typeCache: { types: TaskType[]; options: SMTRadioOption<string>[] } | null = null;
  private priorityCache: { lang: string; options: SMTRadioOption<string>[] } | null = null;
  /** A task cannot be its own parent. */
  readonly notThisTask = (candidate: TaskRef) => candidate.id === this.editingTask?.id;
  @Input() isOpen = false;
  @Input() editingTask: Task | null = null;
  @Input() editForm: any = {};
  @Input() editLoading = false;
  @Input() editLoadError = false;
  @Input() isSubmitting = false;
  @Input() isEditSubmitted = false;
  @Input() isEditDiscardConfirmationOpen = false;
  @Input() taskTypes: TaskType[] = [];
  @Input() projects: Project[] = [];
  @Input() taskCustomFields: CustomField[] = [];


  @Output() close = new EventEmitter<void>();
  @Output() submit = new EventEmitter<void>();
  @Output() retryEditLoad = new EventEmitter<void>();
  @Output() cancelDiscard = new EventEmitter<void>();
  @Output() confirmDiscard = new EventEmitter<void>();

  /** Task types as chips, each icon in the type's colour; the same array while the types stay the same. */
  typeOptions(): SMTRadioOption<string>[] {
    if (this.typeCache?.types !== this.taskTypes) {
      this.typeCache = { types: this.taskTypes, options: this.taskTypes.map(type => ({ value: type.code, label: type.name, icon: type.icon, color: type.color })) };
    }
    return this.typeCache.options;
  }

  /** Priorities as one segmented bar; a chosen high or critical reads in its warning colour. */
  priorityOptions(): SMTRadioOption<string>[] {
    const lang = this.i18n.currentLang();
    if (this.priorityCache?.lang !== lang) {
      this.priorityCache = { lang, options: [
        { value: 'low', label: this.i18n.translate('task.priority.low'), tone: 'success' },
        { value: 'medium', label: this.i18n.translate('tasks.sredniy') },
        { value: 'high', label: this.i18n.translate('task.priority.high'), tone: 'warning' },
        { value: 'critical', label: this.i18n.translate('tasks.kriticheskiy'), tone: 'danger' },
      ] };
    }
    return this.priorityCache.options;
  }
}
