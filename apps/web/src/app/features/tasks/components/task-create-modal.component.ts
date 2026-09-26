import { Component, inject, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTDataSelectComponent, SMTMultiDataSelectComponent } from '../../../shared/ui-kit/components/forms/data-select';
import { TaskLookupsService } from '../services/task-lookups.service';
import { SMTRadioGroupComponent, SMTRadioOption } from '../../../shared/ui-kit/components/forms/radio-group';
import { ProjectOptionsPipe } from './project-options.pipe';
import { SMTDatePickerComponent, SMTDatePickerValueAccessor } from '../../../shared/ui-kit/components/forms/date-picker';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../shared/ui-kit/components/forms/control';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { SMTSelectComponent, SMTSelectValueAccessor } from '../../../shared/ui-kit/components/forms/select';
import { UiMarkdownEditorComponent } from '../../../shared/ui/ui-markdown-editor.component';
import { UiCustomFieldsComponent } from '../../../shared/ui/ui-custom-fields.component';
import { CustomField } from '../../../core/models/custom-field.models';
import { Project, TaskType } from '../../../core/models/task.models';

@Component({
  selector: 'app-task-create-modal',
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
    SMTInputComponent,
    SMTInputValueAccessor,
    UiMarkdownEditorComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <ui-modal
      [isOpen]="isOpen"
      [title]="createForm.parentTaskId ? ('tasks.create_subtask_for' | t:{id: createForm.parentTaskId}) : ('tasks.create_new_task' | t)"
      size="lg"
      [dismissible]="!isSubmitting"
      (close)="close.emit()"
    >
      <fieldset body class="modal-form modal-form-fieldset task-create-form" [disabled]="isSubmitting">
        <!-- Title Input (Required) -->
        <smt-control class="form-group" [smtLabel]="'task.title' | t" [smtError]="isCreateSubmitted && !createForm.title.trim() ? ('tasks.pozhaluysta_ukazhite_nazvanie_zadachi' | t) : ''">
          <smt-input
            smtFieldId="task-create-title"
            name="taskCreateTitle"
            class="title-input"
            required
            [(ngModel)]="createForm.title"
            [placeholder]="'tasks.kratkaya_i_yasnaya_formulirovka_zadachi' | t" />
        </smt-control>

        <!-- Visual Type Selector Chips -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.tip_zadachi' | t }}</span>
          </div>
          <smt-radio-group
            smtAppearance="chips"
            [options]="typeOptions()"
            [value]="createForm.taskType"
            [smtAriaLabel]="'tasks.tip_zadachi' | t"
            (valueChange)="createForm.taskType = $event ?? createForm.taskType" />
        </div>

        <!-- Visual Priority Selector Pills -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'common.priority' | t }}</span>
          </div>
          <smt-radio-group
            smtAppearance="segmented"
            [options]="priorityOptions()"
            [value]="createForm.priority"
            [smtAriaLabel]="'tasks.prioritet_zadachi' | t"
            (valueChange)="createForm.priority = $event ?? createForm.priority" />
        </div>

        <div class="form-grid-2">
          <!-- Project Selector -->
          <smt-control class="form-group" [smtLabel]="'projects.proekt' | t">
            <smt-select
              smtTriggerId="task-create-project"
              name="taskCreateProject"
              [(ngModel)]="createForm.projectId"
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
              [value]="createForm.parentTaskId"
              [ariaLabel]="'task.parent' | t"
              (valueChange)="createForm.parentTaskId = $event"
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
              [value]="createForm.responsibleUserId"
              [ariaLabel]="'task.responsible' | t"
              (valueChange)="createForm.responsibleUserId = $event"
              [placeholder]="'tasks.vyberite_otvetstvennogo' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'common.not_assigned' | t" />
          </div>

          <!-- Deadlines: End Date / Deadline -->
          <smt-control class="form-group" [smtLabel]="'tasks.srok_sdachi_dedlayn' | t">
            <smt-date-picker smtInputId="task-create-deadline" name="taskCreateDeadline" smtWithTime [ngModel]="createForm.endTime" (ngModelChange)="createForm.endTime = $event ?? ''" />
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
            [value]="createForm.executorUserIds"
            [ariaLabel]="'tasks.soispolniteli' | t"
            (valueChange)="createForm.executorUserIds = [...$event]"
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
            [value]="createForm.observerUserIds"
            [ariaLabel]="'tasks.nablyudateli' | t"
            (valueChange)="createForm.observerUserIds = [...$event]"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_nablyudateley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t" />
        </div>

        <!-- RichText Markdown Editor for Description -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'projects.opisanie' | t }}</span>
          </div>
          <ui-markdown-editor
            [value]="createForm.descriptionMarkdown"
            [ariaLabel]="'projects.opisanie' | t"
            (valueChange)="createForm.descriptionMarkdown = $event"
            [placeholder]="'tasks.kontekst_kriterii_gotovnosti_zadachi_ssylki_podd' | t"
            [rows]="4"
          ></ui-markdown-editor>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields.length > 0">
          <h4 class="custom-fields-title">
            <span>{{ 'nav.custom_fields' | t }}</span>
          </h4>
          <ui-custom-fields
            [fields]="taskCustomFields"
            [(values)]="createForm.attributes"
          ></ui-custom-fields>
        </div>

        <div class="custom-fields-empty-tip" *ngIf="taskCustomFields.length === 0">
          <span class="material-symbols-outlined tip-icon" aria-hidden="true">extension</span>
          <span class="tip-text">{{ 'tasks.nuzhny_specificheskie_polya_byudzhet_nomer_dogov' | t }} <strong>{{ 'nav.custom_fields' | t }}</strong>.</span>
        </div>
      </fieldset>
      <div footer>
        <ui-button variant="secondary" size="md" [disabled]="isSubmitting" (onClick)="close.emit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting" (onClick)="submit.emit()">{{ 'tasks.sozdat_zadachu' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .modal-form { display: flex; flex-direction: column; gap: 14px; width: 100%; max-width: 100%; min-width: 0; box-sizing: border-box; }
    .modal-form-fieldset { border: none; padding: 0; margin: 0; min-width: 0; max-width: 100%; box-sizing: border-box; }
    .modal-form-fieldset:disabled { opacity: 0.75; }
    .form-group { display: flex; flex-direction: column; gap: 4px; min-width: 0; max-width: 100%; box-sizing: border-box; }
    .label-row { display: flex; align-items: center; justify-content: space-between; min-width: 0; max-width: 100%; }
    .clean-label { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .req-tag { font-size: 10px; color: var(--text-muted); }
    .title-input { font-size: 14px; font-weight: 500; }
    .input-error { border-color: var(--danger); }
    .error-msg { font-size: 11px; color: var(--danger); margin-top: 2px; }
    .form-grid-2 { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; min-width: 0; max-width: 100%; }
    .form-grid-2 > * { min-width: 0; max-width: 100%; }
    @media (max-width: 640px) { .form-grid-2 { grid-template-columns: minmax(0, 1fr); } }


    .custom-fields-section {
      border-top: 1px dashed var(--border-color);
      padding-top: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .custom-fields-title { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; display: flex; align-items: center; gap: 6px; }
    .custom-fields-empty-tip {
      background: rgba(99, 102, 241, 0.06);
      border: 1px dashed rgba(99, 102, 241, 0.2);
      border-radius: 8px;
      padding: 10px 14px;
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 12px;
      color: var(--text-muted);
      margin-top: 6px;
    }
    .custom-fields-empty-tip .tip-icon { font-size: 18px; color: var(--primary); flex-shrink: 0; }
    .custom-fields-empty-tip .tip-text { line-height: 1.4; }
    .font-mono { font-family: ui-monospace, monospace; }
  `]
})
export class TaskCreateModalComponent {
  /** The pickers' sources and the people and parent the task's card already named. */
  readonly lookups = inject(TaskLookupsService);
  private readonly i18n = inject(I18nService);
  private typeCache: { types: TaskType[]; options: SMTRadioOption<string>[] } | null = null;
  private priorityCache: { lang: string; options: SMTRadioOption<string>[] } | null = null;
  @Input() isOpen = false;
  @Input() createForm: any = {};
  @Input() isSubmitting = false;
  @Input() isCreateSubmitted = false;
  @Input() taskTypes: TaskType[] = [];
  @Input() projects: Project[] = [];
  @Input() taskCustomFields: CustomField[] = [];

  @Output() close = new EventEmitter<void>();
  @Output() submit = new EventEmitter<void>();

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
