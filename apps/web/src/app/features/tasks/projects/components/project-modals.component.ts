import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../../shared/ui-kit/components/modal';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { UiCustomFieldsComponent } from '../../../../shared/ui/ui-custom-fields.component';
import { TranslatePipe, I18nService } from '../../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../../shared/ui-kit/components/forms/control';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../../shared/ui-kit/components/forms/input';
import { SMTTextareaComponent, SMTTextareaValueAccessor } from '../../../../shared/ui-kit/components/forms/textarea';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group/radio-options';
import { Project } from '../../../../core/models/task.models';
import { CustomField } from '../../../../core/models/custom-field.models';
import { ProjectCreateForm, ProjectEditForm, ProjectAttributeItem } from '../projects.models';

@Component({
  selector: 'app-project-modals',
  standalone: true,
  imports: [
    SMTControlComponent, SMTInputComponent, SMTInputValueAccessor, SMTTextareaComponent, SMTTextareaValueAccessor, SMTSelectComponent, SMTSelectValueAccessor,
    CommonModule,
    FormsModule,
    TranslatePipe,
    SMTDialogComponent, SMTDialogContentDirective,
    SMTButtonComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <!-- Record View Modal -->
    <smt-dialog *ngIf="routeRecordId !== null" [open]="true" [smtTitle]="'projects.proekt' | t" smtSize="sm" (closed)="closeRecordView.emit()">
      <ng-template smtDialogContent>
      <div body>
        <p *ngIf="recordLoading" role="status">{{ 'search.record_loading' | t }}</p>
        <div *ngIf="recordError" role="alert">
          <p>{{ (recordNotFound ? 'search.record_not_found' : 'search.record_load_error') | t }}</p>
          <button smt-button type="button" *ngIf="!recordNotFound" smtVariant="secondary" (click)="loadRecordView.emit(routeRecordId)">{{ 'audit.retry' | t }}</button>
        </div>
        <div *ngIf="viewingProject as project" [attr.data-record-id]="routeRecordId">
          <p>#{{ routeRecordId }}</p>
          <h3>{{ project.name }}</h3>
          <p>{{ project.description }}</p>
          <p>{{ (project.state === 'A' ? 'common.active_masculine' : 'common.blocked_masculine') | t }}</p>
          <div class="attributes-stack" *ngIf="hasAttributes(project.attributes)">
            <h4>{{ 'projects.custom_fields' | t }}</h4>
            <div *ngFor="let item of formatAttributes(project.attributes)" class="attr-stack-item">
              <span class="attr-k">{{ item.key }}:</span>
              <span class="attr-v">{{ item.value }}</span>
            </div>
          </div>
        </div>
      </div>
      <div footer><button smt-button type="button" smtVariant="secondary" (click)="closeRecordView.emit()">{{ 'search.back_to_list' | t }}</button></div>
      </ng-template>
    </smt-dialog>

    <!-- Create Project Modal -->
    <smt-dialog
      [open]="isCreateModalOpen"
      [smtTitle]="'projects.sozdanie_novogo_proekta' | t"
      smtSize="sm"
      [dismissible]="!isSubmitting"
      (closed)="requestCloseCreate.emit()">
      <ng-template smtDialogContent>
      <form body id="project-create-form" (ngSubmit)="submitCreateProject.emit()">
        <fieldset class="modal-form modal-form-fieldset project-create-form" [disabled]="isSubmitting">
          <smt-control class="form-group" [smtLabel]="'projects.nazvanie_proekta' | t" [smtError]="isCreateSubmitted && !createForm.name.trim() ? ('projects.pozhaluysta_ukazhite_nazvanie_proekta' | t) : ''">
            <smt-input
              smtFieldId="project-create-name"
              name="projectCreateName"
              required
              [(ngModel)]="createForm.name"
              [placeholder]="'projects.naprimer_vnedrenie_dwh_cdc' | t" />
          </smt-control>
          <smt-control class="form-group" [smtLabel]="'projects.opisanie_proekta' | t">
            <smt-textarea
              smtFieldId="project-create-description"
              name="projectCreateDescription"
              [(ngModel)]="createForm.description"
              [placeholder]="'projects.celi_granicy_i_kontekst_proekta' | t" />
          </smt-control>
          <div class="form-group" *ngIf="projectCustomFields.length > 0">
            <ui-custom-fields
              [fields]="projectCustomFields"
              [values]="createForm.attributes || {}"
              (valuesChange)="createForm.attributes = $event"
            ></ui-custom-fields>
          </div>
          <div *ngIf="createSaveError" class="request-state request-error" data-testid="project-create-save-error" role="alert">
            {{ createSaveError }}
          </div>
        </fieldset>
      </form>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" [disabled]="isSubmitting" (click)="requestCloseCreate.emit()">{{ 'common.cancel' | t }}</button>
        <button smt-button type="submit" form="project-create-form" smtVariant="primary" smtSize="md" [smtLoading]="isSubmitting">{{ 'projects.sozdat_proekt' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>

    <smt-dialog
      [open]="isCreateDiscardConfirmationOpen"
      [smtTitle]="'projects.discard_create_title' | t"
      smtSize="sm"
      (closed)="cancelNavigationDiscard.emit('create')">
      <ng-template smtDialogContent>
      <div body><p>{{ 'projects.discard_create_message' | t }}</p></div>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="cancelNavigationDiscard.emit('create')">{{ 'common.cancel' | t }}</button>
        <button smt-button type="button" smtVariant="danger" smtSize="md" (click)="confirmDiscardCreate.emit()">{{ 'projects.discard_create_action' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>

    <!-- Edit Project Modal -->
    <smt-dialog
      [open]="isEditModalOpen"
      [smtTitle]="'projects.redaktirovanie_proekta' | t"
      smtSize="sm"
      [dismissible]="!isSubmitting"
      (closed)="requestCloseEdit.emit()">
      <ng-template smtDialogContent>
      <div body class="request-state" data-testid="project-edit-loading" *ngIf="editLoading" role="status">
        {{ 'projects.edit_loading' | t }}
      </div>
      <div body class="request-state request-error" data-testid="project-edit-load-error" *ngIf="editLoadError" role="alert">
        <span>{{ 'projects.edit_load_error' | t }}</span>
        <button smt-button type="button" class="project-edit-retry" smtVariant="secondary" smtSize="sm" (click)="retryEditLoad.emit()">{{ 'projects.retry_edit_load' | t }}</button>
      </div>
      <form body id="project-edit-form" (ngSubmit)="submitEditProject.emit()" *ngIf="editingProject as p">
        <fieldset class="modal-form modal-form-fieldset project-edit-form" [disabled]="isSubmitting">
          <smt-control class="form-group" [smtLabel]="'projects.nazvanie_proekta' | t" [smtError]="isEditSubmitted && !editForm.name.trim() ? ('projects.nazvanie_proekta_ne_mozhet_byt_pustym' | t) : ''">
            <smt-input
              smtFieldId="project-edit-name"
              name="projectEditName"
              required
              [(ngModel)]="editForm.name" />
          </smt-control>
          <smt-control class="form-group" [smtLabel]="'iam.status_aktivnosti' | t">
            <smt-select
              smtTriggerId="project-edit-state"
              name="projectEditState"
              [(ngModel)]="editForm.state"
              [options]="stateOptions()"
              [allowClear]="false"
            ></smt-select>
          </smt-control>
          <smt-control class="form-group" [smtLabel]="'projects.opisanie' | t">
            <smt-textarea smtFieldId="project-edit-description" name="projectEditDescription" [(ngModel)]="editForm.description" />
          </smt-control>
          <div class="form-group" *ngIf="projectCustomFields.length > 0">
            <ui-custom-fields
              [fields]="projectCustomFields"
              [values]="editForm.attributes || {}"
              (valuesChange)="editForm.attributes = $event"
            ></ui-custom-fields>
          </div>
          <div *ngIf="editSaveError" class="request-state request-error" data-testid="project-edit-save-error" role="alert">
            {{ editSaveError }}
          </div>
        </fieldset>
      </form>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" [disabled]="isSubmitting" (click)="requestCloseEdit.emit()">{{ 'common.cancel' | t }}</button>
        <button smt-button *ngIf="editingProject" type="submit" form="project-edit-form" smtVariant="primary" smtSize="md" [smtLoading]="isSubmitting">{{ 'common.save' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>

    <smt-dialog
      [open]="isEditDiscardConfirmationOpen"
      [smtTitle]="'projects.discard_edit_title' | t"
      smtSize="sm"
      (closed)="cancelNavigationDiscard.emit('edit')">
      <ng-template smtDialogContent>
      <div body><p>{{ 'projects.discard_edit_message' | t }}</p></div>
      <div footer>
        <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="cancelNavigationDiscard.emit('edit')">{{ 'common.cancel' | t }}</button>
        <button smt-button type="button" smtVariant="danger" smtSize="md" (click)="confirmDiscardEdit.emit()">{{ 'projects.discard_edit_action' | t }}</button>
      </div>
      </ng-template>
    </smt-dialog>
  `,
  styles: [`
    .modal-form { display: flex; flex-direction: column; gap: 12px; }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .label-row { display: flex; align-items: center; justify-content: space-between; }
    /* The same as the smt-control label, so wrapped and plain fields read alike. */
    .clean-label { font-size: 12px; font-weight: 600; color: var(--text-main); }
    .req-tag {
      font-size: 10px;
      font-weight: 500;
      color: var(--danger-text);
      background-color: var(--danger-bg);
      padding: 1px 5px;
      border-radius: 4px;
    }
    .error-msg { font-size: 11px; color: var(--danger); margin-top: 2px; }
    .modal-form-fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
    .clean-textarea { height: auto; padding: 6px 8px; resize: vertical; font-family: inherit; }

    .request-state {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      min-height: 40px;
      padding: 9px 12px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      font-size: 12px;
    }
    .request-error {
      border-color: var(--danger);
      background-color: var(--danger-bg);
      color: var(--danger-text);
    }
    .attributes-stack { display: flex; flex-direction: column; gap: 4px; margin-top: 12px; }
    .attr-stack-item { display: flex; gap: 8px; font-size: 13px; }
    .attr-k { color: var(--text-muted); font-weight: 500; }
    .attr-v { color: var(--text-main); }
  `]
})
export class ProjectModalsComponent {
  private readonly uiI18n = inject(I18nService);
  private readonly stateMemo = optionsMemo<SMTSelectOption<'A' | 'P'>[]>();

  // Record View
  @Input() routeRecordId: string | null = null;
  @Input() viewingProject: Project | null = null;
  @Input() recordLoading = false;
  @Input() recordError = false;
  @Input() recordNotFound = false;
  @Output() closeRecordView = new EventEmitter<void>();
  @Output() loadRecordView = new EventEmitter<string | null>();

  // Create Modal
  @Input() isCreateModalOpen = false;
  @Input() isCreateSubmitted = false;
  @Input() isCreateDiscardConfirmationOpen = false;
  @Input() createSaveError: string | null = null;
  @Input() createForm: ProjectCreateForm = { name: '', description: '', attributes: {} };
  @Output() requestCloseCreate = new EventEmitter<void>();
  @Output() confirmDiscardCreate = new EventEmitter<void>();
  @Output() submitCreateProject = new EventEmitter<void>();

  // Edit Modal
  @Input() isEditModalOpen = false;
  @Input() isEditSubmitted = false;
  @Input() isEditDiscardConfirmationOpen = false;
  @Input() editLoading = false;
  @Input() editLoadError = false;
  @Input() editSaveError: string | null = null;
  @Input() editingProject: Project | null = null;
  @Input() editForm: ProjectEditForm = { name: '', description: '', state: 'A', attributes: {} };
  @Output() requestCloseEdit = new EventEmitter<void>();
  @Output() confirmDiscardEdit = new EventEmitter<void>();
  @Output() submitEditProject = new EventEmitter<void>();
  @Output() retryEditLoad = new EventEmitter<void>();

  // Discard helper
  @Output() cancelNavigationDiscard = new EventEmitter<'create' | 'edit'>();

  // Shared
  @Input() isSubmitting = false;
  @Input() projectCustomFields: CustomField[] = [];

  /** Project states for the edit form; translated again when the language changes. */
  stateOptions(): SMTSelectOption<'A' | 'P'>[] {
    return this.stateMemo([this.uiI18n.currentLang()], () => [
      { id: 'A', label: this.uiI18n.translate('projects.state_active') },
      { id: 'P', label: this.uiI18n.translate('projects.state_archived') },
    ]);
  }

  hasAttributes(attrs: any): boolean {
    if (!attrs || typeof attrs !== 'object') return false;
    return Object.keys(attrs).length > 0;
  }

  formatAttributes(attrs: any): ProjectAttributeItem[] {
    if (!this.hasAttributes(attrs)) return [];
    const fields = this.projectCustomFields;
    return Object.entries(attrs).map(([k, v]) => {
      const field = fields.find(f => f.code === k);
      const keyLabel = field ? field.name : k;
      let valueStr = String(v ?? '');
      if (field?.fieldType === 'boolean') {
        valueStr = v === true || v === 'true'
          ? this.uiI18n.translate('common.yes')
          : this.uiI18n.translate('common.no');
      } else if (field?.fieldType === 'select' && field.optionsJson) {
        try {
          const opts = JSON.parse(field.optionsJson);
          if (Array.isArray(opts)) {
            const matched = opts.find(o => typeof o === 'object' && o !== null ? o.value === v : o === v);
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
