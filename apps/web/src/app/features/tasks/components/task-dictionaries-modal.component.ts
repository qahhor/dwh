import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SMTSortableActionsDirective, SMTSortableItemDirective, SMTSortableListComponent } from '../../../shared/ui-kit/components/sortable-list';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { TranslatePipe, I18nService } from '../../../core/services/i18n.service';
import { TaskStatus, TaskType } from '../../../core/models/task.models';

@Component({
  selector: 'app-task-dictionaries-modal',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    SMTSortableListComponent, SMTSortableItemDirective, SMTSortableActionsDirective, TranslatePipe,
    UiModalComponent,
    UiButtonComponent
  ],
  template: `
    <ui-modal
      [isOpen]="isOpen"
      [title]="'tasks.nastroyka_spravochnikov_zadach' | t"
      size="md"
      (close)="close.emit()"
    >
      <div body class="settings-modal-content">
        <!-- Settings Tabs -->
        <div class="settings-tabs" role="tablist" [attr.aria-label]="'tasks.spravochniki_zadach' | t">
          <button
            id="task-types-tab"
            type="button"
            role="tab"
            class="tab-btn"
            [class.active]="settingsTab === 'types'"
            [attr.aria-selected]="settingsTab === 'types'"
            aria-controls="task-types-panel"
            (click)="settingsTab = 'types'"
          >
            {{ 'tasks.task_types_count' | t:{count: taskTypes.length} }}
          </button>
          <button
            id="task-statuses-tab"
            type="button"
            role="tab"
            class="tab-btn"
            [class.active]="settingsTab === 'statuses'"
            [attr.aria-selected]="settingsTab === 'statuses'"
            aria-controls="task-statuses-panel"
            (click)="settingsTab = 'statuses'"
          >
            {{ 'tasks.task_statuses_count' | t:{count: statuses.length} }}
          </button>
        </div>

        <!-- TAB 1: Task Types (Drag & Drop Reordering) -->
        <div id="task-types-panel" class="tab-pane" role="tabpanel" aria-labelledby="task-types-tab" *ngIf="settingsTab === 'types'">
          <smt-sortable-list
            class="dict-list"
            [items]="taskTypes"
            [trackBy]="byId"
            [itemLabel]="nameOf"
            (reorder)="reorderTypes.emit($event)">
            <ng-template smtSortableItem let-ty>
              <div class="dict-item-info">
                <span class="material-symbols-outlined dict-ico" aria-hidden="true" [style.color]="ty.color">{{ ty.icon }}</span>
                <span class="dict-name">{{ ty.name }}</span>
                <span class="font-mono text-muted text-xs">({{ ty.code }})</span>
                <span *ngIf="ty.isSystem" class="sys-badge">{{ 'tasks.sistemnyy' | t }}</span>
              </div>
            </ng-template>
            <ng-template smtSortableActions let-ty>
              <button *ngIf="!ty.isSystem" type="button" class="mini-del-btn" [title]="'common.delete' | t" [attr.aria-label]="'tasks.delete_task_type' | t:{name: ty.name}" (click)="requestDelete('type', ty.id, ty.name)">
                <span class="material-symbols-outlined" aria-hidden="true">delete</span>
              </button>
            </ng-template>
          </smt-sortable-list>

          <!-- Add New Type Form -->
          <div class="add-dict-box">
            <h5 class="add-dict-title">{{ 'tasks.dobavit_novyy_tip_zadachi' | t }}</h5>
            <div class="form-grid-3">
              <div class="dict-form-field">
                <label class="clean-label" for="task-type-code">{{ 'tasks.kod_tipa' | t }}</label>
                <input id="task-type-code" name="taskTypeCode" type="text" class="clean-input" [placeholder]="'tasks.naprimer_doc' | t" [(ngModel)]="newTypeForm.code" />
              </div>
              <div class="dict-form-field">
                <label class="clean-label" for="task-type-name">{{ 'tasks.nazvanie_tipa' | t }}</label>
                <input id="task-type-name" name="taskTypeName" type="text" class="clean-input" [placeholder]="'tasks.naprimer_dokument' | t" [(ngModel)]="newTypeForm.name" />
              </div>
              <div class="color-picker-row">
                <label class="clean-label" for="task-type-color">{{ 'tasks.cvet_tipa' | t }}</label>
                <input id="task-type-color" name="taskTypeColor" type="color" class="clean-input color-picker" [(ngModel)]="newTypeForm.color" [title]="'tasks.vybrat_cvet' | t" />
              </div>
            </div>
            <div class="add-dict-actions">
              <ui-button variant="secondary" size="sm" icon="add" (onClick)="submitType()">
                {{ 'tasks.dobavit_tip' | t }}
              </ui-button>
            </div>
          </div>
        </div>

        <!-- TAB 2: Task Statuses (Drag & Drop Reordering) -->
        <div id="task-statuses-panel" class="tab-pane" role="tabpanel" aria-labelledby="task-statuses-tab" *ngIf="settingsTab === 'statuses'">
          <smt-sortable-list
            class="dict-list"
            [items]="statuses"
            [trackBy]="byId"
            [itemLabel]="nameOf"
            (reorder)="reorderStatuses.emit($event)">
            <ng-template smtSortableItem let-s>
              <div class="dict-item-info">
                <span class="status-dot" [style.background-color]="s.color"></span>
                <span class="dict-name">{{ s.name }}</span>
                <span *ngIf="s.isTerminal" class="term-badge">{{ 'tasks.zavershayuschiy' | t }}</span>
                <span *ngIf="s.pcode" class="sys-badge">{{ 'tasks.bazovyy' | t }}</span>
              </div>
            </ng-template>
            <ng-template smtSortableActions let-s>
              <button *ngIf="!s.pcode" type="button" class="mini-del-btn" [title]="'common.delete' | t" [attr.aria-label]="'tasks.delete_status' | t:{name: s.name}" (click)="requestDelete('status', s.id, s.name)">
                <span class="material-symbols-outlined" aria-hidden="true">delete</span>
              </button>
            </ng-template>
          </smt-sortable-list>

          <!-- Add New Status Form -->
          <div class="add-dict-box">
            <h5 class="add-dict-title">{{ 'tasks.dobavit_novyy_status' | t }}</h5>
            <div class="form-grid-3">
              <div class="dict-form-field">
                <label class="clean-label" for="task-status-name">{{ 'tasks.nazvanie_statusa.44a913b' | t }}</label>
                <input id="task-status-name" name="taskStatusName" type="text" class="clean-input" [placeholder]="'tasks.nazvanie_statusa' | t" [(ngModel)]="newStatusForm.name" />
              </div>
              <div class="color-picker-row">
                <label class="clean-label" for="task-status-color">{{ 'tasks.cvet_statusa' | t }}</label>
                <input id="task-status-color" name="taskStatusColor" type="color" class="clean-input color-picker" [(ngModel)]="newStatusForm.color" [title]="'tasks.vybrat_cvet' | t" />
              </div>
              <label class="terminal-toggle-label">
                <input name="taskStatusTerminal" type="checkbox" [(ngModel)]="newStatusForm.isTerminal" />
                <span>{{ 'tasks.zavershayuschiy' | t }}</span>
              </label>
            </div>
            <div class="add-dict-actions">
              <ui-button variant="secondary" size="sm" icon="add" (onClick)="submitStatus()">
                {{ 'tasks.dobavit_status' | t }}
              </ui-button>
            </div>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="close.emit()">{{ 'audit.zakryt' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .settings-modal-content { display: flex; flex-direction: column; gap: 14px; }
    .settings-tabs {
      display: flex;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
    }
    .tab-btn {
      flex: 1;
      border: none;
      background: transparent;
      padding: 6px 12px;
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      transition: all 0.1s ease;
    }
    .tab-btn.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
    }
    .tab-pane { display: flex; flex-direction: column; gap: 12px; }
    .dict-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
      max-height: 220px;
      overflow-y: auto;
    }
    .dict-item-info { display: flex; align-items: center; gap: 6px; }
    .dict-ico { font-size: 16px; }
    .dict-name { font-weight: 500; color: var(--text-main); }
    .sys-badge { font-size: 10px; background-color: rgba(99,102,241,0.1); color: var(--primary); padding: 1px 4px; border-radius: 3px; }
    .term-badge { font-size: 10px; background-color: rgba(16,185,129,0.1); color: var(--success); padding: 1px 4px; border-radius: 3px; }
    .status-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
    }
    .mini-del-btn {
      border: none;
      background: transparent;
      color: var(--danger);
      cursor: pointer;
      padding: 2px;
      display: flex;
    }
    .mini-del-btn .material-symbols-outlined { font-size: 16px; }
    .add-dict-box {
      background-color: var(--bg-hover);
      border: 1px dashed var(--border-color);
      border-radius: var(--radius-sm);
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .add-dict-title { font-size: 11px; font-weight: 600; color: var(--text-muted); margin: 0; }
    .form-grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
    .dict-form-field,
    .color-picker-row { display: flex; flex-direction: column; gap: 4px; }
    .clean-label { font-size: 11px; font-weight: 500; color: var(--text-muted); }
    .clean-input {
      height: 34px;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .clean-input:focus { border-color: var(--primary); }
    .color-picker { width: 100%; padding: 2px; height: 34px; cursor: pointer; }
    .terminal-toggle-label {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      color: var(--text-main);
      cursor: pointer;
    }
    .add-dict-actions { display: flex; justify-content: flex-end; }
    .dictionary-delete-body { display: flex; flex-direction: column; gap: 8px; }
    .dictionary-delete-body p { margin: 0; }
    .dictionary-delete-body span { color: var(--text-muted); font-size: 12px; }
    .font-mono { font-family: ui-monospace, monospace; }
    .text-muted { color: var(--text-muted); }
    .text-xs { font-size: 11px; }
  `]
})
export class TaskDictionariesModalComponent {
  @Input() isOpen = false;
  @Input() taskTypes: TaskType[] = [];
  @Input() statuses: TaskStatus[] = [];

  @Output() close = new EventEmitter<void>();
  @Output() createType = new EventEmitter<{ code: string; name: string; icon: string; color: string }>();
  @Output() createStatus = new EventEmitter<{ name: string; color: string; isTerminal: boolean }>();
  @Output() deleteItem = new EventEmitter<{ kind: 'type' | 'status'; id: number; name: string }>();
  @Output() reorderTypes = new EventEmitter<TaskType[]>();
  @Output() reorderStatuses = new EventEmitter<TaskStatus[]>();

  settingsTab: 'types' | 'statuses' = 'types';
  newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
  newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };

  /** Rows are kept by id, so a moved row keeps its focus. */
  readonly byId = (item: { id: number }) => item.id;

  readonly nameOf = (item: { name: string }) => item.name;

  submitType() {
    if (!this.newTypeForm.code.trim() || !this.newTypeForm.name.trim()) return;
    this.createType.emit({
      code: this.newTypeForm.code.trim(),
      name: this.newTypeForm.name.trim(),
      icon: this.newTypeForm.icon.trim() || 'task_alt',
      color: this.newTypeForm.color
    });
    this.newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
  }

  submitStatus() {
    if (!this.newStatusForm.name.trim()) return;
    this.createStatus.emit({
      name: this.newStatusForm.name.trim(),
      color: this.newStatusForm.color,
      isTerminal: this.newStatusForm.isTerminal
    });
    this.newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
  }

  /** Asks the page to delete; the page confirms it first. */
  requestDelete(kind: 'type' | 'status', id: number, name: string) {
    this.deleteItem.emit({ kind, id, name });
  }
}
