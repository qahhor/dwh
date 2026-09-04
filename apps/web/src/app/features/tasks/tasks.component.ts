import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { DragDropModule, CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../shared/ui/ui-custom-fields.component';
import { UiSearchableSelectComponent, SelectOption } from '../../shared/ui/ui-searchable-select.component';
import { UiUserMultiSelectComponent } from '../../shared/ui/ui-user-multi-select.component';
import { UiMarkdownEditorComponent } from '../../shared/ui/ui-markdown-editor.component';
import { UiMarkdownViewComponent } from '../../shared/ui/ui-markdown-view.component';
import { UiPaginationComponent } from '../../shared/ui/ui-pagination.component';
import { UiFileUploadComponent } from '../../shared/ui/ui-file-upload.component';
import { Task, Project, TaskStatus, TaskType, TaskComment, TaskMember, TaskDetailResponse, TaskFile } from '../../core/models/task.models';
import { CustomField } from '../../core/models/custom-field.models';
import { User } from '../../core/models/auth.models';
import { KeysetPage } from '../../core/models/common.models';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';

@Component({
  selector: 'app-tasks',
  standalone: true,
  imports: [
    TranslatePipe,
    CommonModule,
    FormsModule,
    DragDropModule,
    UiButtonComponent,
    UiModalComponent,
    UiCustomFieldsComponent,
    UiSearchableSelectComponent,
    UiUserMultiSelectComponent,
    UiMarkdownEditorComponent,
    UiMarkdownViewComponent,
    UiPaginationComponent,
    UiFileUploadComponent
  ],


  template: `
    <div class="tasks-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'nav.tasks' | t }}</h1>
          <span class="count-badge">{{ tasks().length }}</span>

          <!-- View Mode Switcher: Table / Kanban -->
          <div class="status-tabs" role="group" [attr.aria-label]="'tasks.rezhim_otobrazheniya_zadach' | t">
            <button
              type="button"
              class="status-tab"
              [class.active]="viewMode === 'table'"
              [attr.aria-pressed]="viewMode === 'table'"
              (click)="viewMode = 'table'"
              [title]="'tasks.tablichnyy_vid' | t"
            >
              <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">table_rows</span>
              <span>{{ 'projects.spisok' | t }}</span>
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="viewMode === 'kanban'"
              [attr.aria-pressed]="viewMode === 'kanban'"
              (click)="viewMode = 'kanban'"
              [title]="'tasks.kanban_doska' | t"
            >
              <span class="material-symbols-outlined" style="font-size: 16px;" aria-hidden="true">view_kanban</span>
              <span>{{ 'tasks.kanban' | t }}</span>
            </button>
          </div>
        </div>

        <div class="header-right">
          <!-- Dictionary Settings Button -->
          <button
            type="button"
            class="btn btn-secondary"
            [title]="'tasks.upravlenie_statusami_i_tipami_zadach' | t"
            (click)="openSettingsModal()"
          >
            <span class="material-symbols-outlined" aria-hidden="true">tune</span>
            <span>{{ 'tasks.spravochniki' | t }}</span>
          </button>

          <!-- Export Dropdown -->
          <div class="export-dropdown-container" style="position: relative; display: inline-block;">
            <button
              type="button"
              class="btn btn-secondary"
              [title]="'tasks.eksport_spiska_zadach' | t"
              (click)="showExportMenu = !showExportMenu"
            >
              <span class="material-symbols-outlined" aria-hidden="true">download</span>
              <span>{{ 'analytics.eksport' | t }}</span>
              <span class="material-symbols-outlined" style="font-size: 16px;">arrow_drop_down</span>
            </button>
            <div class="export-popover" *ngIf="showExportMenu" style="position: absolute; top: 100%; right: 0; margin-top: 4px; background: var(--bg-surface); border: 1px solid var(--border-color); border-radius: var(--radius-md); box-shadow: var(--shadow-md); z-index: 100; min-width: 160px; overflow: hidden; display: flex; flex-direction: column;">
              <button type="button" class="export-item-btn" style="display: flex; align-items: center; gap: 8px; padding: 8px 12px; font-size: 12px; background: none; border: none; width: 100%; text-align: left; cursor: pointer; color: var(--text-main);" (click)="exportTasks('xlsx')">
                <span class="material-symbols-outlined" style="color: var(--success); font-size: 18px;">table_view</span>
                <span>Excel (.xlsx)</span>
              </button>
              <button type="button" class="export-item-btn" style="display: flex; align-items: center; gap: 8px; padding: 8px 12px; font-size: 12px; background: none; border: none; width: 100%; text-align: left; cursor: pointer; color: var(--text-main); border-top: 1px solid var(--border-subtle);" (click)="exportTasks('csv')">
                <span class="material-symbols-outlined" style="color: var(--primary); font-size: 18px;">description</span>
                <span>CSV (UTF-8)</span>
              </button>
            </div>
          </div>

          <!-- Create Task Button -->
          <ui-button
            *ngIf="canCreateTask()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateTaskModal()"
          >
            {{ 'task.new' | t }}
          </ui-button>
        </div>
      </div>

      <!-- Linear-Style Toolbar -->
      <div class="toolbar">
        <div class="search-field">
          <span class="material-symbols-outlined search-icon" aria-hidden="true">search</span>
          <label class="sr-only" for="task-search">{{ 'tasks.poisk_zadach' | t }}</label>
          <input
            id="task-search"
            name="taskSearch"
            type="text"
            class="search-input"
            [placeholder]="'projects.poisk_po_nazvaniyu_ili_opisaniyu' | t"
            [(ngModel)]="searchQuery"
            (keyup.enter)="loadTasks(true)"
          />
          <button *ngIf="searchQuery" type="button" class="clear-btn" [attr.aria-label]="'tasks.ochistit_poisk_zadach' | t" (click)="clearSearch()">
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </div>

        <div class="toolbar-controls">
          <!-- Status Filter Tabs (Default: 'active' which excludes done & cancelled) -->
          <div class="status-tabs" *ngIf="viewMode === 'table'" role="group" [attr.aria-label]="'tasks.filtr_po_statusu' | t">
            <button
              type="button"
              class="status-tab"
              [class.active]="statusFilterMode === 'active'"
              [attr.aria-pressed]="statusFilterMode === 'active'"
              (click)="setStatusFilterMode('active')"
              [title]="'tasks.tolko_aktivnye_zadachi_bez_vypolnennyh_i_otmenen' | t"
            >
              <span class="status-tab-dot active-dot" aria-hidden="true"></span>
              {{ 'iam.aktivnye' | t }}
            </button>
            <button
              type="button"
              class="status-tab"
              [class.active]="statusFilterMode === 'all'"
              [attr.aria-pressed]="statusFilterMode === 'all'"
              (click)="setStatusFilterMode('all')"
              [title]="'tasks.vse_zadachi_vklyuchaya_zavershennye' | t"
            >
              {{ 'common.all' | t }}
            </button>
            <button
              *ngFor="let s of statuses()"
              type="button"
              class="status-tab"
              [class.active]="statusFilterMode === s.id"
              [attr.aria-pressed]="statusFilterMode === s.id"
              (click)="setStatusFilterMode(s.id)"
            >
              <span class="status-tab-dot" [style.background-color]="s.color || 'var(--primary)'" aria-hidden="true"></span>
              {{ s.name }}
            </button>
          </div>

          <!-- Project Filter -->
          <label class="sr-only" for="task-project-filter">{{ 'tasks.filtr_po_proektu' | t }}</label>
          <select
            id="task-project-filter"
            name="taskProjectFilter"
            class="clean-select"
            [(ngModel)]="selectedProjectId"
            (change)="loadTasks(true)"
          >
            <option [ngValue]="null">{{ 'tasks.vse_proekty' | t }}</option>
            <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
          </select>

          <!-- Priority Filter -->
          <label class="sr-only" for="task-priority-filter">{{ 'tasks.filtr_po_prioritetu' | t }}</label>
          <select
            id="task-priority-filter"
            name="taskPriorityFilter"
            class="clean-select"
            [(ngModel)]="selectedPriority"
            (change)="loadTasks(true)"
          >
            <option value="">{{ 'tasks.vse_prioritety' | t }}</option>
            <option value="critical">{{ 'tasks.kriticheskiy' | t }}</option>
            <option value="high">{{ 'task.priority.high' | t }}</option>
            <option value="medium">{{ 'tasks.sredniy' | t }}</option>
            <option value="low">{{ 'task.priority.low' | t }}</option>
          </select>

          <button
            *ngIf="hasActiveFilters()"
            type="button"
            class="reset-filters-btn"
            [attr.aria-label]="'tasks.sbrosit_vse_filtry' | t"
            (click)="resetFilters()"
            [title]="'tasks.sbrosit_vse_filtry' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">filter_alt_off</span>
          </button>
        </div>
      </div>

      <!-- ======================================================================= -->
      <!-- VIEW 1: TABLE / LIST VIEW (Default View)                                -->
      <!-- ======================================================================= -->
      <div class="table-card" *ngIf="viewMode === 'table'">
        <div class="table-wrapper" role="region" [attr.aria-label]="'tasks.tablica_zadach' | t" tabindex="0">
          <table class="data-table" [attr.aria-label]="'tasks.spisok_zadach' | t">
            <thead>
              <tr>
                <th style="width: 60px;">ID</th>
                <th style="width: 120px;">{{ 'settings.tip' | t }}</th>
                <th>{{ 'tasks.zadacha' | t }}</th>
                <th>{{ 'projects.proekt' | t }}</th>
                <th>{{ 'common.priority' | t }}</th>
                <th>{{ 'common.status' | t }}</th>
                <th>{{ 'tasks.srok' | t }}</th>
                <th class="text-right" style="width: 110px;">{{ 'common.actions' | t }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                *ngFor="let t of paginatedTasks()"
                class="task-row"
                role="button"
                tabindex="0"
                [attr.aria-label]="'tasks.open_task_named' | t:{id: t.id, title: t.title}"
                [class.row-overdue]="isOverdue(t.endTime, t.statusId)"
                (click)="openTaskDetails(t)"
                (keydown.enter)="openTaskDetails(t)"
                (keydown.space)="$event.preventDefault(); openTaskDetails(t)"
              >
                <td class="tabular-nums font-mono" [class.text-danger]="isOverdue(t.endTime, t.statusId)" [class.text-muted]="!isOverdue(t.endTime, t.statusId)">
                  #{{ t.id }}
                </td>
                <td>
                  <span class="task-type-badge" [style.color]="getTypeColor(t)" [style.background-color]="getTypeBg(t)">
                    <span class="material-symbols-outlined type-icon" aria-hidden="true">{{ getTypeIcon(t) }}</span>
                    {{ getTypeLabel(t) }}
                  </span>
                </td>
                <td>
                  <div class="task-title-cell">
                    <span class="task-title" [class.title-overdue]="isOverdue(t.endTime, t.statusId)">
                      {{ t.title }}
                    </span>
                    <span *ngIf="isOverdue(t.endTime, t.statusId)" class="overdue-tag">
                      {{ 'tasks.prosrocheno' | t }}
                    </span>
                    <span *ngIf="t.parentTaskId" class="parent-chip font-mono" [title]="'task.parent' | t">
                      {{ 'tasks.subtask_number' | t:{id: t.parentTaskId} }}
                    </span>
                  </div>
                </td>
                <td>
                  <span class="project-tag" *ngIf="getProjectName(t.projectId) as pName">
                    <span class="material-symbols-outlined folder-ico">folder</span>
                    {{ pName }}
                  </span>
                  <span class="text-muted" *ngIf="!t.projectId">—</span>
                </td>
                <td>
                  <span class="priority-pill" [attr.data-priority]="t.priority">
                    {{ getPriorityLabel(t.priority) }}
                  </span>
                </td>
                <td (click)="$event.stopPropagation()">
                  <!-- Quick Status Changer Dropdown -->
                  <div class="inline-status-wrapper">
                    <select
                      class="inline-status-select"
                      [style.color]="getStatusColor(t.statusId)"
                      [ngModel]="t.statusId"
                      (ngModelChange)="updateStatus(t.id, $event)"
                      [disabled]="!canUpdateTask()"
                      [attr.aria-label]="'tasks.task_status_aria' | t:{id: t.id}"
                      [title]="'tasks.nazhmite_dlya_smeny_statusa' | t"
                    >
                      <option *ngFor="let s of statuses()" [ngValue]="s.id">{{ s.name }}</option>
                    </select>
                  </div>
                </td>
                <td>
                  <span
                    *ngIf="t.endTime"
                    class="deadline-pill"
                    [class.overdue]="isOverdue(t.endTime, t.statusId)"
                    [title]="'tasks.deadline_value' | t:{date: (t.endTime | date:'dd.MM.yyyy HH:mm') || ''}"
                  >
                    <span class="material-symbols-outlined ico" aria-hidden="true">
                      {{ isOverdue(t.endTime, t.statusId) ? 'warning' : 'event' }}
                    </span>
                    {{ t.endTime | date:'dd.MM.yyyy' }}
                  </span>
                  <span *ngIf="!t.endTime" class="text-muted">—</span>
                </td>
                <td class="text-right actions-cell" (click)="$event.stopPropagation()">
                  <div class="row-action-btns">
                    <button
                      *ngIf="canUpdateTask()"
                      type="button"
                      class="icon-ghost-btn"
                      [attr.aria-label]="'tasks.edit_task_number' | t:{id: t.id}"
                      [title]="'tasks.redaktirovat_zadachu' | t"
                      (click)="openEditModal(t)"
                    >
                      <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                    </button>
                    <button
                      type="button"
                      class="icon-ghost-btn"
                      [attr.aria-label]="'tasks.view_task_number' | t:{id: t.id}"
                      [title]="'tasks.prosmotret_detali' | t"
                      (click)="openTaskDetails(t)"
                    >
                      <span class="material-symbols-outlined" aria-hidden="true">visibility</span>
                    </button>
                  </div>
                </td>
              </tr>
              <tr *ngIf="tasks().length === 0 && !isLoading()">
                <td colspan="8" class="empty-state-cell">
                  <span class="material-symbols-outlined icon" aria-hidden="true">task</span>
                  <p>{{ 'tasks.zadachi_ne_naydeny' | t }}</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Pagination Bar -->
        <ui-pagination
          [totalItems]="tasks().length"
          [currentPage]="currentPage"
          [pageSize]="pageSize"
          (pageChange)="currentPage = $event"
          (pageSizeChange)="pageSize = $event; currentPage = 1"
        ></ui-pagination>
      </div>


      <!-- ======================================================================= -->
      <!-- VIEW 2: KANBAN BOARD WITH DRAG & DROP                                  -->
      <!-- ======================================================================= -->
      <div class="kanban-board" cdkDropListGroup *ngIf="viewMode === 'kanban'" role="region" [attr.aria-label]="'tasks.kanban_doska_zadach' | t">
        <div
          *ngFor="let status of statuses()"
          class="kanban-column"
          [style.border-top-color]="status.color || 'var(--primary)'"
          (dragover)="onHtml5DragOver($event)"
          (dragleave)="onHtml5DragLeave($event)"
          (drop)="onHtml5Drop($event, status.id)"
        >
          <!-- Column Header -->
          <div class="column-header">
            <div class="column-title-group">
              <span class="status-dot" [style.background-color]="status.color || 'var(--primary)'"></span>
              <h3 class="column-title">{{ status.name }}</h3>
            </div>
            <span class="column-badge">{{ getTasksByStatus(status.id).length }}</span>
          </div>

          <!-- Drop List Zone for CDK Drag & Drop -->
          <div
            cdkDropList
            [cdkDropListData]="getTasksByStatus(status.id)"
            [id]="'col-' + status.id"
            class="column-tasks-dropzone"
            (cdkDropListDropped)="onTaskDrop($event, status.id)"
          >
            <div
              *ngFor="let task of getTasksByStatus(status.id)"
              cdkDrag
              [cdkDragData]="task"
              draggable="true"
              (dragstart)="onHtml5DragStart($event, task)"
              class="kanban-card"
              role="button"
              tabindex="0"
              [attr.aria-label]="'tasks.open_task_named' | t:{id: task.id, title: task.title}"
              [class.card-overdue]="isOverdue(task.endTime, task.statusId)"
              (click)="openTaskDetails(task)"
              (keydown.enter)="openTaskDetails(task)"
              (keydown.space)="$event.preventDefault(); openTaskDetails(task)"
            >
              <!-- Card Top -->
              <div class="card-top-row">
                <div class="card-type-group">
                  <span class="material-symbols-outlined drag-grip-icon" cdkDragHandle [title]="'tasks.peretaschit_kartochku' | t">
                    drag_indicator
                  </span>
                  <span class="task-type-badge-mini" [style.color]="getTypeColor(task)">
                    <span class="material-symbols-outlined mini-ico">{{ getTypeIcon(task) }}</span>
                    <span class="task-id font-mono">#{{ task.id }}</span>
                  </span>
                </div>
                <span class="priority-pill" [attr.data-priority]="task.priority">
                  {{ getPriorityLabel(task.priority) }}
                </span>
              </div>

              <!-- Card Title -->
              <h4 class="card-title" [class.title-overdue]="isOverdue(task.endTime, task.statusId)">
                {{ task.title }}
              </h4>

              <!-- Card Meta -->
              <div class="card-meta" *ngIf="task.projectId || task.parentTaskId">
                <span class="project-tag-mini" *ngIf="getProjectName(task.projectId) as pName">
                  <span class="material-symbols-outlined folder-ico">folder</span>
                  {{ pName }}
                </span>
                <span *ngIf="task.parentTaskId" class="parent-chip font-mono">
                  ↳ #{{ task.parentTaskId }}
                </span>
              </div>

              <!-- Card Bottom Row -->
              <div class="card-bottom-row" (click)="$event.stopPropagation()">
                <span
                  *ngIf="task.endTime"
                  class="deadline-pill"
                  [class.overdue]="isOverdue(task.endTime, task.statusId)"
                >
                  <span class="material-symbols-outlined ico">
                    {{ isOverdue(task.endTime, task.statusId) ? 'warning' : 'event' }}
                  </span>
                  {{ task.endTime | date:'dd.MM' }}
                </span>
                <span *ngIf="!task.endTime" class="text-muted text-xs">{{ 'tasks.bez_sroka' | t }}</span>

                <!-- Quick Move Buttons -->
                <div class="kanban-move-actions" *ngIf="canUpdateTask()">
                  <button
                    type="button"
                    class="move-btn"
                    [attr.aria-label]="'tasks.move_task_back' | t:{id: task.id}"
                    [title]="'tasks.peremestit_nazad' | t"
                    [disabled]="isFirstStatus(status.id)"
                    (click)="moveTaskStatus(task, -1)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">chevron_left</span>
                  </button>
                  <button
                    type="button"
                    class="move-btn"
                    [attr.aria-label]="'tasks.move_task_forward' | t:{id: task.id}"
                    [title]="'tasks.peremestit_vpered' | t"
                    [disabled]="isLastStatus(status.id)"
                    (click)="moveTaskStatus(task, 1)"
                  >
                    <span class="material-symbols-outlined" aria-hidden="true">chevron_right</span>
                  </button>
                </div>
              </div>
            </div>

            <div *ngIf="getTasksByStatus(status.id).length === 0" class="kanban-empty-col">
              {{ 'tasks.peretaschite_zadachu_syuda' | t }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ======================================================================= -->
    <!-- Task Details Modal (Sleek Linear/Jira 2-Pane View)                     -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="selectedTask() !== null"
      [title]="'tasks.task_number' | t:{id: selectedTask()?.id || ''}"
      size="lg"
      (close)="selectedTask.set(null)"
    >
      <div body class="task-details-view" *ngIf="selectedTask() as t">
        <!-- Ancestor Breadcrumbs Trail -->
        <div class="ancestor-trail" *ngIf="taskAncestors().length > 0">
          <span class="trail-label">{{ 'tasks.ierarhiya' | t }}</span>
          <ng-container *ngFor="let anc of taskAncestors()">
            <button type="button" class="anc-link" (click)="openTaskDetails(anc)">
              #{{ anc.id }} {{ anc.title }}
            </button>
            <span class="anc-sep" aria-hidden="true">›</span>
          </ng-container>
          <span class="anc-current">#{{ t.id }} {{ t.title }}</span>
        </div>

        <!-- Overdue Notice Banner -->
        <div class="overdue-banner" *ngIf="isOverdue(t.endTime, t.statusId)">
          <span class="material-symbols-outlined">error</span>
          <span>{{ 'tasks.deadline_expired_at' | t:{date: (t.endTime | date:'dd.MM.yyyy HH:mm') || ''} }}</span>
        </div>

        <div class="details-2col-layout">
          <!-- Left Column (Main) -->
          <div class="details-main-col">
            <div class="detail-header-group">
              <h2 class="detail-main-title">{{ t.title }}</h2>
            </div>

            <!-- Description (Rich Markdown View) -->
            <div class="detail-section">
              <h4 class="section-label">{{ 'tasks.opisanie_zadachi' | t }}</h4>
              <div class="description-card" *ngIf="t.descriptionMarkdown">
                <ui-markdown-view [content]="t.descriptionMarkdown"></ui-markdown-view>
              </div>
              <div class="description-card empty-desc text-muted" *ngIf="!t.descriptionMarkdown">
                {{ 'tasks.opisanie_otsutstvuet_nazhmite_redaktirovat_chtob' | t }}
              </div>
            </div>

            <!-- Subtasks Section -->
            <div class="detail-section">
              <div class="section-header-between">
                <h4 class="section-label">{{ 'tasks.subtasks_count' | t:{count: taskSubtasks().length} }}</h4>
                <button
                  *ngIf="canCreateTask()"
                  type="button"
                  class="add-subtask-btn"
                  (click)="openAddSubtaskModal(t)"
                >
                  <span class="material-symbols-outlined">add</span>
                  {{ 'tasks.dobavit_podzadachu' | t }}
                </button>
              </div>

              <div class="subtasks-list" *ngIf="taskSubtasks().length > 0">
                <button
                  type="button"
                  *ngFor="let sub of taskSubtasks()"
                  class="subtask-row"
                  [attr.aria-label]="'tasks.open_subtask_named' | t:{id: sub.id, title: sub.title}"
                  [class.row-overdue]="isOverdue(sub.endTime, sub.statusId)"
                  (click)="openTaskDetails(sub)"
                >
                  <span class="subtask-type" [style.color]="getTypeColor(sub)">
                    <span class="material-symbols-outlined type-icon">{{ getTypeIcon(sub) }}</span>
                  </span>
                  <span class="font-mono text-muted text-xs">#{{ sub.id }}</span>
                  <span class="subtask-title">{{ sub.title }}</span>
                  <span class="inline-status-badge" [style.color]="getStatusColor(sub.statusId)">
                    {{ getStatusName(sub.statusId) }}
                  </span>
                  <span class="priority-pill" [attr.data-priority]="sub.priority">
                    {{ getPriorityLabel(sub.priority) }}
                  </span>
                </button>
              </div>
              <div *ngIf="taskSubtasks().length === 0" class="no-subtasks-hint text-muted">
                {{ 'tasks.u_etoy_zadachi_poka_net_podzadach' | t }}
              </div>
            </div>

            <!-- Attachments & Files Section -->
            <div class="detail-section files-section">
              <h4 class="section-label">{{ 'tasks.attachments_count' | t:{count: taskFiles().length} }}</h4>
              <ui-file-upload
                [files]="taskFiles()"
                [canUpload]="canUpdateTask()"
                [canDelete]="canUpdateTask()"
                (fileAttached)="onTaskFileAttached($event)"
                (fileRemoved)="onTaskFileRemoved($event)"
              ></ui-file-upload>
            </div>


            <!-- Comments Feed -->
            <div class="detail-section comments-section">
              <h4 class="section-label">{{ 'tasks.comments_count' | t:{count: comments().length} }}</h4>

              <div class="comments-feed">
                <div *ngFor="let c of comments()" class="comment-card">
                  <div class="comment-top">
                    <div class="comment-author-badge">
                      <span class="avatar-mini">{{ getInitials(c.userName) }}</span>
                      <span class="comment-author">{{ c.userName }} <span class="text-muted">&#64;{{ c.userLogin }}</span></span>
                    </div>
                    <span class="comment-time tabular-nums">{{ c.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
                  </div>
                  <div class="comment-text">
                    <ui-markdown-view [content]="c.textMarkdown || c.commentMarkdown"></ui-markdown-view>
                  </div>
                </div>
                <div *ngIf="comments().length === 0" class="no-comments-hint text-muted">
                  {{ 'tasks.kommentariev_poka_net' | t }}
                </div>
              </div>

              <div class="add-comment-box">
                <textarea
                  class="comment-textarea"
                  rows="2"
                  [placeholder]="'tasks.napisat_kommentariy_k_zadache_ctrl_enter_dlya_ot' | t"
                  [(ngModel)]="newCommentText"
                  (keydown.ctrl.enter)="submitComment()"
                ></textarea>
                <ui-button variant="primary" size="sm" icon="send" (onClick)="submitComment()">
                  {{ 'tasks.otpravit' | t }}
                </ui-button>
              </div>
            </div>
          </div>

          <!-- Right Column (Properties Sidebar) -->
          <div class="details-side-col">
            <div class="side-card">
              <div class="side-prop-row">
                <span class="prop-k">{{ 'common.status' | t }}</span>
                <div class="prop-v">
                  <select
                    class="clean-select status-select"
                    [style.color]="getStatusColor(t.statusId)"
                    [ngModel]="t.statusId"
                    (ngModelChange)="updateStatus(t.id, $event)"
                    [disabled]="!canUpdateTask()"
                  >
                    <option *ngFor="let s of statuses()" [ngValue]="s.id">{{ s.name }}</option>
                  </select>
                </div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">{{ 'tasks.tip_zadachi' | t }}</span>
                <div class="prop-v">
                  <span class="task-type-badge" [style.color]="getTypeColor(t)" [style.background-color]="getTypeBg(t)">
                    <span class="material-symbols-outlined type-icon">{{ getTypeIcon(t) }}</span>
                    {{ getTypeLabel(t) }}
                  </span>
                </div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">{{ 'common.priority' | t }}</span>
                <div class="prop-v">
                  <span class="priority-pill" [attr.data-priority]="t.priority">
                    {{ getPriorityLabel(t.priority) }}
                  </span>
                </div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">{{ 'projects.proekt' | t }}</span>
                <div class="prop-v">{{ getProjectName(t.projectId) || ('tasks.without_project' | t) }}</div>
              </div>

              <div class="side-prop-row" *ngIf="t.parentTaskId">
                <span class="prop-k">{{ 'tasks.roditel' | t }}</span>
                <div class="prop-v font-mono text-xs">#{{ t.parentTaskId }}</div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">{{ 'tasks.dedlayn' | t }}</span>
                <div class="prop-v" [class.text-danger]="isOverdue(t.endTime, t.statusId)">
                  {{ t.endTime ? (t.endTime | date:'dd.MM.yyyy HH:mm') : ('common.not_set' | t) }}
                </div>
              </div>

              <div class="side-prop-row" *ngIf="t.beginTime">
                <span class="prop-k">{{ 'tasks.data_nachala' | t }}</span>
                <div class="prop-v">{{ t.beginTime | date:'dd.MM.yyyy HH:mm' }}</div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">{{ 'iam.sozdana' | t }}</span>
                <div class="prop-v text-muted">{{ t.createdAt | date:'dd.MM.yyyy HH:mm' }}</div>
              </div>
            </div>

            <!-- Members Card -->
            <div class="side-card" *ngIf="taskMembers().length > 0">
              <h5 class="side-card-title">{{ 'tasks.uchastniki' | t }}</h5>
              <div class="members-stack">
                <div *ngFor="let m of taskMembers()" class="member-stack-item">
                  <span class="member-role-badge" [attr.data-role]="m.involveKind || m.involvementKind">
                    {{ getInvolveKindLabel(m.involveKind || m.involvementKind) }}
                  </span>
                  <span class="member-name">{{ m.userName }}</span>
                  <span class="member-login text-muted">&#64;{{ m.userLogin }}</span>
                </div>
              </div>
            </div>

            <!-- Custom Attributes Card -->
            <div class="side-card" *ngIf="hasAttributes(t.attributes)">
              <h5 class="side-card-title">{{ 'tasks.dop_polya' | t }}</h5>
              <div class="attributes-stack">
                <div *ngFor="let item of formatAttributes(t.attributes)" class="attr-stack-item">
                  <span class="attr-k">{{ item.key }}:</span>
                  <span class="attr-v">{{ item.value }}</span>
                </div>
              </div>
            </div>

            <button
              *ngIf="canUpdateTask()"
              type="button"
              class="side-edit-btn"
              (click)="openEditModal(t)"
            >
              <span class="material-symbols-outlined">edit</span>
              {{ 'tasks.redaktirovat_zadachu' | t }}
            </button>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="selectedTask.set(null)">{{ 'audit.zakryt' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Create Task Modal (With RichText MD Editor & User Multi-Select)         -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      [title]="createForm.parentTaskId ? ('tasks.create_subtask_for' | t:{id: createForm.parentTaskId}) : ('tasks.create_new_task' | t)"
      size="lg"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <!-- Title Input (Required) -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label" for="task-create-title">{{ 'task.title' | t }}</label>
            <span class="req-tag">{{ 'projects.obyazatelnoe_pole' | t }}</span>
          </div>
          <input
            id="task-create-title"
            name="taskCreateTitle"
            type="text"
            class="clean-input title-input"
            required
            [attr.aria-invalid]="isCreateSubmitted && !createForm.title.trim()"
            [attr.aria-describedby]="isCreateSubmitted && !createForm.title.trim() ? 'task-create-title-error' : null"
            [class.input-error]="isCreateSubmitted && !createForm.title.trim()"
            [(ngModel)]="createForm.title"
            [placeholder]="'tasks.kratkaya_i_yasnaya_formulirovka_zadachi' | t"
          />
          <span id="task-create-title-error" class="error-msg" *ngIf="isCreateSubmitted && !createForm.title.trim()">
            {{ 'tasks.pozhaluysta_ukazhite_nazvanie_zadachi' | t }}
          </span>
        </div>

        <!-- Visual Type Selector Chips -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.tip_zadachi' | t }}</span>
          </div>
          <div class="type-chips-selector" role="group" [attr.aria-label]="'tasks.tip_zadachi' | t">
            <button
              *ngFor="let ty of taskTypes()"
              type="button"
              class="type-chip-btn"
              [class.active]="createForm.taskType === ty.code"
              [attr.aria-pressed]="createForm.taskType === ty.code"
              (click)="createForm.taskType = ty.code"
              [style.--chip-color]="ty.color"
            >
              <span class="material-symbols-outlined" aria-hidden="true">{{ ty.icon }}</span>
              <span>{{ ty.name }}</span>
            </button>
          </div>
        </div>

        <!-- Visual Priority Selector Pills -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'common.priority' | t }}</span>
          </div>
          <div class="priority-chips-selector" role="group" [attr.aria-label]="'tasks.prioritet_zadachi' | t">
            <button
              type="button"
              class="prio-chip-btn prio-low"
              [class.active]="createForm.priority === 'low'"
              [attr.aria-pressed]="createForm.priority === 'low'"
              (click)="createForm.priority = 'low'"
            >
              {{ 'task.priority.low' | t }}
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-medium"
              [class.active]="createForm.priority === 'medium'"
              [attr.aria-pressed]="createForm.priority === 'medium'"
              (click)="createForm.priority = 'medium'"
            >
              {{ 'tasks.sredniy' | t }}
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-high"
              [class.active]="createForm.priority === 'high'"
              [attr.aria-pressed]="createForm.priority === 'high'"
              (click)="createForm.priority = 'high'"
            >
              {{ 'task.priority.high' | t }}
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-critical"
              [class.active]="createForm.priority === 'critical'"
              [attr.aria-pressed]="createForm.priority === 'critical'"
              (click)="createForm.priority = 'critical'"
            >
              {{ 'tasks.kriticheskiy' | t }}
            </button>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Project Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label" for="task-create-project">{{ 'projects.proekt' | t }}</label>
            </div>
            <select id="task-create-project" name="taskCreateProject" class="clean-input" [(ngModel)]="createForm.projectId">
              <option [ngValue]="null">{{ 'tasks.bez_proekta' | t }}</option>
              <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
            </select>
          </div>

          <!-- Parent Task (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'task.parent' | t }}</span>
            </div>
            <ui-searchable-select
              [options]="taskSelectOptions()"
              [selectedId]="createForm.parentTaskId"
              [ariaLabel]="'task.parent' | t"
              (selectedIdChange)="createForm.parentTaskId = $event"
              [placeholder]="'tasks.bez_roditelya_kornevaya_zadacha' | t"
              [searchPlaceholder]="'tasks.poisk_zadachi_po_id_ili_nazvaniyu' | t"
              [emptyLabel]="'tasks.without_parent' | t"
            ></ui-searchable-select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'tasks.otvetstvennyy_sotrudnik_i_t1' | t }}</span>
            </div>
            <ui-searchable-select
              [options]="userSelectOptions()"
              [selectedId]="createForm.responsibleUserId"
              [ariaLabel]="'tasks.otvetstvennyy_sotrudnik' | t"
              (selectedIdChange)="createForm.responsibleUserId = $event"
              [placeholder]="'tasks.vyberite_otvetstvennogo' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'common.not_assigned' | t"
            ></ui-searchable-select>
          </div>

          <!-- Deadlines: End Date / Deadline -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label" for="task-create-deadline">{{ 'tasks.srok_sdachi_dedlayn' | t }}</label>
            </div>
            <input id="task-create-deadline" name="taskCreateDeadline" type="datetime-local" class="clean-input font-mono" [(ngModel)]="createForm.endTime" />
          </div>
        </div>

        <!-- Observers Searchable Multi-Select Tags Input -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.nablyudateli_poluchayut_uvedomleniya' | t }}</span>
          </div>
          <ui-user-multi-select
            [users]="usersList()"
            [selectedUserIds]="createForm.observerUserIds"
            [ariaLabel]="'tasks.nablyudateli' | t"
            (selectedUserIdsChange)="createForm.observerUserIds = $event"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_nablyudateley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t"
          ></ui-user-multi-select>
        </div>

        <!-- RichText Markdown Editor for Description -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.podrobnoe_opisanie_markdown_richtext' | t }}</span>
          </div>
          <ui-markdown-editor
            [value]="createForm.descriptionMarkdown"
            [ariaLabel]="'tasks.podrobnoe_opisanie_zadachi' | t"
            (valueChange)="createForm.descriptionMarkdown = $event"
            [placeholder]="'tasks.kontekst_kriterii_gotovnosti_zadachi_ssylki_podd' | t"
            [rows]="4"
          ></ui-markdown-editor>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">
            <span>{{ 'tasks.dopolnitelnye_nastraivaemye_polya' | t }}</span>
            <span class="custom-fields-subhint">{{ 'tasks.nastraivayutsya_v_menyu_nastraivaemye_polya' | t }}</span>
          </h4>
          <ui-custom-fields
            [fields]="taskCustomFields()"
            [(values)]="createForm.attributes"
          ></ui-custom-fields>
        </div>

        <div class="custom-fields-empty-tip" *ngIf="taskCustomFields().length === 0">
          <span class="material-symbols-outlined tip-icon" aria-hidden="true">extension</span>
          <span class="tip-text">{{ 'tasks.nuzhny_specificheskie_polya_byudzhet_nomer_dogov' | t }} <strong>{{ 'tasks.nastraivaemye_polya' | t }}</strong>.</span>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateTask()">{{ 'tasks.sozdat_zadachu' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Edit Task Modal (With RichText MD Editor & User Multi-Select)           -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      [title]="'tasks.redaktirovanie_zadachi' | t"
      size="lg"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-form" *ngIf="editingTask as task">
        <!-- Title Input (Required) -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label" for="task-edit-title">{{ 'task.title' | t }}</label>
            <span class="req-tag">{{ 'projects.obyazatelnoe_pole' | t }}</span>
          </div>
          <input
            id="task-edit-title"
            name="taskEditTitle"
            type="text"
            class="clean-input title-input"
            required
            [attr.aria-invalid]="isEditSubmitted && !editForm.title.trim()"
            [attr.aria-describedby]="isEditSubmitted && !editForm.title.trim() ? 'task-edit-title-error' : null"
            [class.input-error]="isEditSubmitted && !editForm.title.trim()"
            [(ngModel)]="editForm.title"
          />
          <span id="task-edit-title-error" class="error-msg" *ngIf="isEditSubmitted && !editForm.title.trim()">
            {{ 'tasks.nazvanie_zadachi_ne_mozhet_byt_pustym' | t }}
          </span>
        </div>

        <!-- Visual Type Selector Chips -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.tip_zadachi' | t }}</span>
          </div>
          <div class="type-chips-selector" role="group" [attr.aria-label]="'tasks.tip_zadachi' | t">
            <button
              *ngFor="let ty of taskTypes()"
              type="button"
              class="type-chip-btn"
              [class.active]="editForm.taskType === ty.code"
              [attr.aria-pressed]="editForm.taskType === ty.code"
              (click)="editForm.taskType = ty.code"
              [style.--chip-color]="ty.color"
            >
              <span class="material-symbols-outlined" aria-hidden="true">{{ ty.icon }}</span>
              <span>{{ ty.name }}</span>
            </button>
          </div>
        </div>

        <!-- Visual Priority Selector Pills -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'common.priority' | t }}</span>
          </div>
          <div class="priority-chips-selector" role="group" [attr.aria-label]="'tasks.prioritet_zadachi' | t">
            <button
              type="button"
              class="prio-chip-btn prio-low"
              [class.active]="editForm.priority === 'low'"
              [attr.aria-pressed]="editForm.priority === 'low'"
              (click)="editForm.priority = 'low'"
            >
              {{ 'task.priority.low' | t }}
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-medium"
              [class.active]="editForm.priority === 'medium'"
              [attr.aria-pressed]="editForm.priority === 'medium'"
              (click)="editForm.priority = 'medium'"
            >
              {{ 'tasks.sredniy' | t }}
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-high"
              [class.active]="editForm.priority === 'high'"
              [attr.aria-pressed]="editForm.priority === 'high'"
              (click)="editForm.priority = 'high'"
            >
              {{ 'task.priority.high' | t }}
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-critical"
              [class.active]="editForm.priority === 'critical'"
              [attr.aria-pressed]="editForm.priority === 'critical'"
              (click)="editForm.priority = 'critical'"
            >
              {{ 'tasks.kriticheskiy' | t }}
            </button>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Project Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label" for="task-edit-project">{{ 'projects.proekt' | t }}</label>
            </div>
            <select id="task-edit-project" name="taskEditProject" class="clean-input" [(ngModel)]="editForm.projectId">
              <option [ngValue]="null">{{ 'tasks.bez_proekta' | t }}</option>
              <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
            </select>
          </div>

          <!-- Parent Task (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'task.parent' | t }}</span>
            </div>
            <ui-searchable-select
              [options]="getAvailableParentTaskOptions(task.id)"
              [selectedId]="editForm.parentTaskId"
              [ariaLabel]="'task.parent' | t"
              (selectedIdChange)="editForm.parentTaskId = $event"
              [placeholder]="'tasks.bez_roditelya_kornevaya_zadacha' | t"
              [searchPlaceholder]="'tasks.poisk_zadachi_po_id_ili_nazvaniyu' | t"
              [emptyLabel]="'tasks.without_parent' | t"
            ></ui-searchable-select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'tasks.otvetstvennyy_sotrudnik_i_t1' | t }}</span>
            </div>
            <ui-searchable-select
              [options]="userSelectOptions()"
              [selectedId]="editForm.responsibleUserId"
              [ariaLabel]="'tasks.otvetstvennyy_sotrudnik' | t"
              (selectedIdChange)="editForm.responsibleUserId = $event"
              [placeholder]="'tasks.vyberite_otvetstvennogo' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'common.not_assigned' | t"
            ></ui-searchable-select>
          </div>

          <!-- Deadlines: End Date / Deadline -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label" for="task-edit-deadline">{{ 'tasks.srok_sdachi_dedlayn' | t }}</label>
            </div>
            <input id="task-edit-deadline" name="taskEditDeadline" type="datetime-local" class="clean-input font-mono" [(ngModel)]="editForm.endTime" />
          </div>
        </div>

        <!-- Observers Searchable Multi-Select Tags Input -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.nablyudateli_poluchayut_uvedomleniya' | t }}</span>
          </div>
          <ui-user-multi-select
            [users]="usersList()"
            [selectedUserIds]="editForm.observerUserIds"
            [ariaLabel]="'tasks.nablyudateli' | t"
            (selectedUserIdsChange)="editForm.observerUserIds = $event"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_nablyudateley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t"
          ></ui-user-multi-select>
        </div>

        <!-- RichText Markdown Editor for Description -->
        <div class="form-group">
          <div class="label-row">
            <span class="clean-label">{{ 'tasks.podrobnoe_opisanie_markdown_richtext' | t }}</span>
          </div>
          <ui-markdown-editor
            [value]="editForm.descriptionMarkdown"
            [ariaLabel]="'tasks.podrobnoe_opisanie_zadachi' | t"
            (valueChange)="editForm.descriptionMarkdown = $event"
            [rows]="4"
          ></ui-markdown-editor>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">{{ 'tasks.dopolnitelnye_nastraivaemye_polya.615ccfa' | t }}</h4>
          <ui-custom-fields
            [fields]="taskCustomFields()"
            [(values)]="editForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditTask()">{{ 'tasks.sohranit_izmeneniya' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Dictionaries Settings Modal (With CDK Drag & Drop Reordering)           -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isSettingsModalOpen()"
      [title]="'tasks.nastroyka_spravochnikov_zadach' | t"
      size="md"
      (close)="isSettingsModalOpen.set(false)"
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
            {{ 'tasks.task_types_count' | t:{count: taskTypes().length} }}
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
            {{ 'tasks.task_statuses_count' | t:{count: statuses().length} }}
          </button>
        </div>

        <!-- TAB 1: Task Types (Drag & Drop Reordering) -->
        <div id="task-types-panel" class="tab-pane" role="tabpanel" aria-labelledby="task-types-tab" *ngIf="settingsTab === 'types'">
          <div
            cdkDropList
            class="dict-list"
            (cdkDropListDropped)="onTypeDrop($event)"
          >
            <div
              *ngFor="let ty of taskTypes(); let typeIndex = index"
              cdkDrag
              class="dict-row"
            >
              <div class="dict-item-info">
                <span cdkDragHandle class="material-symbols-outlined drag-grip-icon" aria-hidden="true" [title]="'tasks.peretaschite_dlya_izmeneniya_poryadka' | t">
                  drag_indicator
                </span>
                <span class="material-symbols-outlined dict-ico" aria-hidden="true" [style.color]="ty.color">{{ ty.icon }}</span>
                <span class="dict-name">{{ ty.name }}</span>
                <span class="font-mono text-muted text-xs">({{ ty.code }})</span>
                <span *ngIf="ty.isSystem" class="sys-badge">{{ 'tasks.sistemnyy' | t }}</span>
              </div>
              <div class="dict-actions" *ngIf="!ty.isSystem">
                <button type="button" class="mini-move-btn" [disabled]="typeIndex === 0" [attr.aria-label]="'tasks.raise_task_type' | t:{name: ty.name}" (click)="moveDictionaryType(typeIndex, -1)">
                  <span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span>
                </button>
                <button type="button" class="mini-move-btn" [disabled]="typeIndex === taskTypes().length - 1" [attr.aria-label]="'tasks.lower_task_type' | t:{name: ty.name}" (click)="moveDictionaryType(typeIndex, 1)">
                  <span class="material-symbols-outlined" aria-hidden="true">arrow_downward</span>
                </button>
                <button type="button" class="mini-del-btn" [title]="'common.delete' | t" [attr.aria-label]="'tasks.delete_task_type' | t:{name: ty.name}" (click)="requestDeleteDictionaryItem('type', ty.id, ty.name)">
                  <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                </button>
              </div>
            </div>
          </div>

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
              <ui-button variant="secondary" size="sm" icon="add" (onClick)="submitCreateType()">
                {{ 'tasks.dobavit_tip' | t }}
              </ui-button>
            </div>
          </div>
        </div>

        <!-- TAB 2: Task Statuses (Drag & Drop Reordering) -->
        <div id="task-statuses-panel" class="tab-pane" role="tabpanel" aria-labelledby="task-statuses-tab" *ngIf="settingsTab === 'statuses'">
          <div
            cdkDropList
            class="dict-list"
            (cdkDropListDropped)="onStatusDrop($event)"
          >
            <div
              *ngFor="let s of statuses(); let statusIndex = index"
              cdkDrag
              class="dict-row"
            >
              <div class="dict-item-info">
                <span cdkDragHandle class="material-symbols-outlined drag-grip-icon" aria-hidden="true" [title]="'tasks.peretaschite_dlya_izmeneniya_poryadka' | t">
                  drag_indicator
                </span>
                <span class="status-dot" [style.background-color]="s.color"></span>
                <span class="dict-name">{{ s.name }}</span>
                <span *ngIf="s.isTerminal" class="term-badge">{{ 'tasks.zavershayuschiy' | t }}</span>
                <span *ngIf="s.pcode" class="sys-badge">{{ 'tasks.bazovyy' | t }}</span>
              </div>
              <div class="dict-actions" *ngIf="!s.pcode">
                <button type="button" class="mini-move-btn" [disabled]="statusIndex === 0" [attr.aria-label]="'tasks.raise_status' | t:{name: s.name}" (click)="moveDictionaryStatus(statusIndex, -1)">
                  <span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span>
                </button>
                <button type="button" class="mini-move-btn" [disabled]="statusIndex === statuses().length - 1" [attr.aria-label]="'tasks.lower_status' | t:{name: s.name}" (click)="moveDictionaryStatus(statusIndex, 1)">
                  <span class="material-symbols-outlined" aria-hidden="true">arrow_downward</span>
                </button>
                <button type="button" class="mini-del-btn" [title]="'common.delete' | t" [attr.aria-label]="'tasks.delete_status' | t:{name: s.name}" (click)="requestDeleteDictionaryItem('status', s.id, s.name)">
                  <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                </button>
              </div>
            </div>
          </div>

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
              <ui-button variant="secondary" size="sm" icon="add" (onClick)="submitCreateStatus()">
                {{ 'tasks.dobavit_status' | t }}
              </ui-button>
            </div>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isSettingsModalOpen.set(false)">{{ 'audit.zakryt' | t }}</ui-button>
      </div>
    </ui-modal>

    <ui-modal
      [isOpen]="dictionaryDeleteTarget !== null"
      [title]="'tasks.udalenie_elementa_spravochnika' | t"
      size="sm"
      (close)="dictionaryDeleteTarget = null"
    >
      <div body class="dictionary-delete-body" *ngIf="dictionaryDeleteTarget as target">
        <p>{{ 'tasks.delete_dictionary_confirm' | t:{kind: ((target.kind === 'type' ? 'tasks.task_type_accusative' : 'tasks.status_accusative') | t), name: target.name} }}</p>
        <span>{{ 'tasks.udalenie_budet_otkloneno_esli_element_uzhe_ispol' | t }}</span>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="dictionaryDeleteTarget = null">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmDeleteDictionaryItem()">{{ 'common.delete' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .tasks-page {
      display: flex;
      flex-direction: column;
      gap: 16px;
      width: 100%;
    }

    /* Header */
    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .header-left { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .view-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .task-count {
      font-size: 12px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 7px;
      border-radius: 10px;
      font-weight: 500;
      border: 1px solid var(--border-color);
    }

    /* View Switcher */
    .view-switcher {
      display: flex;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
    }
    .view-btn {
      border: none;
      background: transparent;
      padding: 3px 8px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      transition: all 0.1s ease;
    }
    .view-btn .material-symbols-outlined { font-size: 15px; }
    .view-btn.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
    }

    .header-right { display: flex; align-items: center; gap: 8px; }

    .settings-btn {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      height: 32px;
      padding: 0 10px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      color: var(--text-main);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.12s ease;
    }
    .settings-btn:hover { border-color: var(--primary); }
    .settings-btn .material-symbols-outlined { font-size: 16px; color: var(--text-muted); }

    /* Compact Toolbar */
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 8px 12px;
    }
    .search-field {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 4px 8px;
      width: 260px;
      max-width: 100%;
    }
    .search-icon { font-size: 16px; color: var(--text-muted); }
    .search-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 12px;
      color: var(--text-main);
      width: 100%;
    }
    .clear-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      padding: 0;
    }
    .clear-btn .material-symbols-outlined { font-size: 14px; }

    .toolbar-controls {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }

    .status-tabs {
      display: flex;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
    }
    .status-tab {
      border: none;
      background: transparent;
      min-height: 28px;
      padding: 3px 8px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      transition: all 0.1s ease;
    }
    .status-tab:hover { color: var(--text-main); }
    .status-tab.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
    }
    .status-tab-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
    }
    .status-tab-dot.active-dot {
      background-color: #10b981;
    }

    .clean-select {
      height: 30px;
      padding: 2px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 12px;
      outline: none;
    }
    .clean-select:focus { border-color: var(--primary); }

    .reset-filters-btn {
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border-radius: var(--radius-sm);
      height: 30px;
      width: 30px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
    }
    .reset-filters-btn:hover { color: var(--text-main); border-color: var(--text-muted); }
    .reset-filters-btn .material-symbols-outlined { font-size: 16px; }

    /* Table Card */
    .table-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      overflow: hidden;
    }
    .table-wrapper { overflow-x: auto; }
    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }
    .data-table th {
      text-align: left;
      padding: 8px 12px;
      background-color: var(--bg-hover);
      border-bottom: 1px solid var(--border-color);
      color: var(--text-muted);
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.4px;
    }
    .data-table td {
      padding: 10px 12px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
    }
    .task-row {
      cursor: pointer;
      transition: background 0.1s ease;
    }
    .task-row:hover { background-color: var(--bg-hover); }
    .task-row:last-child td { border-bottom: none; }

    /* Overdue Highlighting in Table */
    .task-row.row-overdue {
      background-color: rgba(239, 68, 68, 0.04);
      border-left: 3px solid var(--danger);
    }
    .task-row.row-overdue:hover {
      background-color: rgba(239, 68, 68, 0.08);
    }
    .overdue-tag {
      font-size: 9px;
      font-weight: 600;
      color: var(--danger);
      background-color: var(--danger-bg);
      padding: 1px 5px;
      border-radius: 3px;
      text-transform: uppercase;
      letter-spacing: 0.3px;
    }

    .task-type-badge {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      font-size: 11px;
      font-weight: 500;
      padding: 1px 6px;
      border-radius: 4px;
    }
    .task-type-badge .type-icon { font-size: 13px; }

    .task-title-cell {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .task-title { font-weight: 500; }
    .parent-chip {
      font-size: 10px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
      padding: 1px 5px;
      border-radius: 4px;
      border: 1px solid var(--border-color);
    }

    .project-tag {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: var(--text-muted);
    }
    .folder-ico { font-size: 14px; color: var(--warning); }

    /* Priority Pills */
    .priority-pill {
      font-size: 11px;
      font-weight: 500;
      padding: 2px 7px;
      border-radius: 10px;
      display: inline-block;
    }
    .priority-pill[data-priority="critical"] { background-color: var(--danger-bg); color: var(--danger); }
    .priority-pill[data-priority="high"] { background-color: var(--warning-bg); color: var(--warning); }
    .priority-pill[data-priority="medium"] { background-color: var(--bg-hover); color: var(--text-muted); }
    .priority-pill[data-priority="low"] { background-color: var(--bg-hover); color: var(--text-light); }

    /* Inline Status Select */
    .inline-status-wrapper { display: inline-block; }
    .inline-status-select {
      height: 26px;
      padding: 1px 6px;
      font-size: 11px;
      font-weight: 500;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      cursor: pointer;
      outline: none;
    }
    .inline-status-select:focus { border-color: var(--primary); }

    .deadline-pill {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      font-size: 11px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 6px;
      border-radius: 4px;
      border: 1px solid var(--border-color);
    }
    .deadline-pill .ico { font-size: 13px; }
    .deadline-pill.overdue {
      color: var(--danger);
      background-color: var(--danger-bg);
      border-color: rgba(239,68,68,0.3);
      font-weight: 600;
    }

    .row-action-btns { display: inline-flex; gap: 4px; }
    .icon-ghost-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 4px;
      border-radius: 4px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .icon-ghost-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .icon-ghost-btn .material-symbols-outlined { font-size: 17px; }

    .empty-state-cell {
      padding: 40px;
      text-align: center;
      color: var(--text-muted);
    }
    .empty-state-cell .icon { font-size: 36px; color: var(--text-light); margin-bottom: 6px; }

    .load-more-bar {
      padding: 10px;
      display: flex;
      justify-content: center;
      border-top: 1px solid var(--border-color);
      background-color: var(--bg-hover);
    }

    /* Kanban Board & CDK Drag & Drop */
    .kanban-board {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 16px;
      align-items: start;
      width: 100%;
    }

    .kanban-column {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-top: 3px solid var(--primary);
      border-radius: var(--radius-md);
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 12px;
      min-height: 250px;
      transition: background-color 0.15s ease, border-color 0.15s ease;
    }
    .kanban-column.drag-over {
      background-color: rgba(99,102,241,0.06);
      border-color: var(--primary);
    }

    .column-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding-bottom: 6px;
      border-bottom: 1px solid var(--border-color);
    }
    .column-title-group { display: flex; align-items: center; gap: 6px; }
    .column-title { font-size: 13px; font-weight: 600; margin: 0; color: var(--text-main); }
    .column-badge {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 6px;
      border-radius: 10px;
    }

    .column-tasks-dropzone {
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-height: 150px;
      border-radius: var(--radius-xs);
      transition: background-color 0.15s ease;
    }
    .column-tasks-dropzone.cdk-drop-list-dragging {
      background-color: rgba(99,102,241,0.03);
    }

    .kanban-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      cursor: grab;
      transition: all 0.12s ease;
      box-shadow: var(--shadow-sm);
      user-select: none;
    }
    .kanban-card:active { cursor: grabbing; }
    .kanban-card:hover {
      border-color: var(--primary);
      transform: translateY(-1px);
      box-shadow: var(--shadow-md);
    }
    .kanban-card.card-overdue {
      border-color: rgba(239, 68, 68, 0.4);
      background-color: rgba(239, 68, 68, 0.02);
    }

    /* CDK Dragging Preview & Placeholder */
    .cdk-drag-preview {
      box-sizing: border-box;
      border-radius: var(--radius-sm);
      box-shadow: var(--shadow-overlay);
      background-color: var(--bg-surface);
      border: 1px solid var(--primary);
      padding: 10px;
      opacity: 0.95;
    }
    .cdk-drag-placeholder {
      opacity: 0.3;
      border: 2px dashed var(--primary);
      background-color: rgba(99,102,241,0.05);
      border-radius: var(--radius-sm);
      min-height: 70px;
    }
    .cdk-drag-animating {
      transition: transform 250ms cubic-bezier(0, 0, 0.2, 1);
    }

    .card-top-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .card-type-group { display: flex; align-items: center; gap: 4px; }
    .drag-grip-icon {
      font-size: 16px;
      color: var(--text-muted);
      cursor: grab;
      opacity: 0.6;
    }
    .drag-grip-icon:hover { opacity: 1; color: var(--primary); }

    .task-type-badge-mini {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      font-size: 10px;
      font-weight: 500;
    }
    .task-type-badge-mini .mini-ico { font-size: 13px; }

    .card-title {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-main);
      margin: 0;
      line-height: 1.35;
    }

    .card-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .project-tag-mini {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      font-size: 11px;
      color: var(--text-muted);
    }

    .card-bottom-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding-top: 6px;
      border-top: 1px solid var(--border-color);
    }

    .kanban-move-actions { display: inline-flex; gap: 2px; }
    .move-btn {
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-muted);
      border-radius: 3px;
      width: 22px;
      height: 22px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      padding: 0;
    }
    .move-btn:hover:not(:disabled) { color: var(--text-main); border-color: var(--primary); }
    .move-btn:disabled { opacity: 0.3; cursor: not-allowed; }
    .move-btn .material-symbols-outlined { font-size: 14px; }

    .kanban-empty-col {
      padding: 30px 0;
      text-align: center;
      color: var(--text-muted);
      font-size: 12px;
      border: 1px dashed var(--border-color);
      border-radius: var(--radius-sm);
    }

    /* 2-Pane Details View */
    .task-details-view { display: flex; flex-direction: column; gap: 14px; }

    .ancestor-trail {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
      font-size: 11px;
      background-color: var(--bg-hover);
      padding: 5px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
    }
    .trail-label { color: var(--text-muted); font-weight: 500; }
    .anc-link {
      border: 0;
      background: transparent;
      color: var(--primary);
      cursor: pointer;
      font: inherit;
      padding: 0;
      text-decoration: underline;
    }
    .anc-sep { color: var(--text-muted); }
    .anc-current { font-weight: 600; color: var(--text-main); }

    .overdue-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background-color: var(--danger-bg);
      border: 1px solid rgba(239, 68, 68, 0.3);
      border-radius: var(--radius-sm);
      color: var(--danger);
      font-size: 12px;
      font-weight: 500;
    }
    .overdue-banner .material-symbols-outlined { font-size: 18px; }

    .details-2col-layout {
      display: grid;
      grid-template-columns: 1fr 280px;
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 768px) {
      .details-2col-layout { grid-template-columns: 1fr; }
    }

    .details-main-col { display: flex; flex-direction: column; gap: 16px; }
    .detail-main-title { font-size: 18px; font-weight: 600; margin: 0; color: var(--text-main); line-height: 1.35; }

    .detail-section { display: flex; flex-direction: column; gap: 6px; }
    .section-header-between { display: flex; align-items: center; justify-content: space-between; }
    .section-label { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; }
    .description-card {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 10px 12px;
    }
    .empty-desc { font-style: italic; }

    .add-subtask-btn {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--primary);
      padding: 2px 8px;
      border-radius: var(--radius-xs);
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
    }
    .add-subtask-btn .material-symbols-outlined { font-size: 14px; }
    .add-subtask-btn:hover { border-color: var(--primary); }

    .subtasks-list { display: flex; flex-direction: column; gap: 4px; }
    .subtask-row {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-xs);
      cursor: pointer;
      font-size: 12px;
      font-family: inherit;
      color: inherit;
      text-align: left;
      width: 100%;
      transition: background 0.1s ease;
    }
    .subtask-row:hover { border-color: var(--primary); }
    .subtask-title { flex: 1; font-weight: 500; }
    .inline-status-badge { font-size: 11px; font-weight: 500; }
    .no-subtasks-hint { font-size: 12px; font-style: italic; padding: 4px 0; }

    .comments-section { border-top: 1px solid var(--border-color); padding-top: 12px; }
    .comments-feed {
      display: flex;
      flex-direction: column;
      gap: 8px;
      max-height: 220px;
      overflow-y: auto;
    }
    .comment-card {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 8px 10px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .comment-top { display: flex; justify-content: space-between; align-items: center; font-size: 11px; }
    .comment-author-badge { display: flex; align-items: center; gap: 5px; }
    .avatar-mini {
      width: 18px;
      height: 18px;
      border-radius: 50%;
      background-color: var(--primary);
      color: #fff;
      font-size: 9px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .comment-author { font-weight: 600; color: var(--text-main); }
    .comment-time { color: var(--text-muted); font-size: 10px; }
    .comment-text { font-size: 12px; color: var(--text-main); }

    .add-comment-box {
      display: flex;
      gap: 8px;
      align-items: flex-end;
      margin-top: 6px;
    }
    .comment-textarea {
      flex: 1;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      padding: 6px 8px;
      font-size: 12px;
      font-family: inherit;
      outline: none;
      resize: vertical;
    }

    /* Details Sidebar Panel */
    .details-side-col { display: flex; flex-direction: column; gap: 12px; }
    .side-card {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .side-card-title { font-size: 11px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; margin: 0; }
    .side-prop-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; }
    .prop-k { color: var(--text-muted); font-size: 11px; flex-shrink: 0; }
    .prop-v { font-weight: 500; text-align: right; word-break: break-all; }

    .members-stack { display: flex; flex-direction: column; gap: 4px; }
    .member-stack-item {
      display: flex;
      align-items: center;
      gap: 5px;
      font-size: 11px;
      background-color: var(--bg-surface);
      padding: 3px 6px;
      border-radius: 3px;
      border: 1px solid var(--border-color);
    }
    .member-role-badge {
      font-size: 9px;
      font-weight: 600;
      padding: 1px 4px;
      border-radius: 3px;
      background-color: rgba(99,102,241,0.15);
      color: var(--primary);
    }
    .member-role-badge[data-role="R"] { background-color: var(--warning-bg); color: var(--warning); }
    .member-role-badge[data-role="O"] { background-color: rgba(14,165,233,0.15); color: #0284c7; }
    .member-role-badge[data-role="A"] { background-color: rgba(16,185,129,0.15); color: var(--success); }

    .attributes-stack { display: flex; flex-direction: column; gap: 4px; }
    .attr-stack-item { display: flex; justify-content: space-between; font-size: 11px; }

    .side-edit-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      width: 100%;
      height: 32px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.1s ease;
    }
    .side-edit-btn:hover { border-color: var(--primary); color: var(--primary); }
    .side-edit-btn .material-symbols-outlined { font-size: 16px; }

    /* Modal Form Styling */
    .modal-form { display: flex; flex-direction: column; gap: 12px; }
    .form-grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
    .form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

    @media (max-width: 640px) {
      .view-header {
        align-items: flex-start;
        flex-direction: column;
      }
      .header-right {
        width: 100%;
        flex-wrap: wrap;
      }
      .toolbar { align-items: stretch; }
      .search-field,
      .toolbar-controls { width: 100%; }
      .toolbar-controls { min-width: 0; }
      .status-tabs {
        max-width: 100%;
        overflow-x: auto;
      }
      .modal-form,
      .form-group { min-width: 0; }
      .form-grid-2,
      .form-grid-3 { grid-template-columns: minmax(0, 1fr); }
    }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .label-row { display: flex; align-items: center; justify-content: space-between; }
    .clean-label { font-size: 11px; font-weight: 500; color: var(--text-muted); }
    .req-tag {
      font-size: 10px;
      font-weight: 500;
      color: var(--danger);
      background-color: var(--danger-bg);
      padding: 1px 5px;
      border-radius: 4px;
    }
    .clean-input {
      height: 34px;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
      transition: border-color 0.15s ease;
    }
    .clean-input:focus { border-color: var(--primary); }
    .clean-input.input-error { border-color: var(--danger); background-color: var(--danger-bg); }
    .title-input { font-size: 14px; font-weight: 500; }
    .error-msg { font-size: 11px; color: var(--danger); margin-top: 2px; }

    /* Visual Selectors for Type & Priority */
    .type-chips-selector {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .type-chip-btn {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 4px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-xs);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.12s ease;
    }
    .type-chip-btn .material-symbols-outlined { font-size: 16px; color: var(--chip-color); }
    .type-chip-btn:hover { border-color: var(--chip-color); color: var(--text-main); }
    .type-chip-btn.active {
      border-color: var(--chip-color);
      background-color: var(--bg-hover);
      color: var(--text-main);
      font-weight: 600;
      box-shadow: 0 0 0 1px var(--chip-color);
    }

    .priority-chips-selector {
      display: flex;
      gap: 6px;
      background-color: var(--bg-hover);
      padding: 3px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
    }
    .prio-chip-btn {
      flex: 1;
      border: none;
      background: transparent;
      padding: 4px 8px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
      transition: all 0.1s ease;
      text-align: center;
    }
    .prio-chip-btn.active {
      background-color: var(--bg-surface);
      font-weight: 600;
      box-shadow: var(--shadow-sm);
    }
    .prio-chip-btn.prio-low.active { color: #10b981; }
    .prio-chip-btn.prio-medium.active { color: var(--text-main); }
    .prio-chip-btn.prio-high.active { color: var(--warning); }
    .prio-chip-btn.prio-critical.active { color: var(--danger); }

    .custom-fields-section {
      border-top: 1px dashed var(--border-color);
      padding-top: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .custom-fields-title { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; display: flex; align-items: center; gap: 6px; }
    .custom-fields-subhint { font-size: 11px; font-weight: 400; color: var(--text-muted); opacity: 0.8; }
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

    /* Dictionaries Settings Modal */
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
      padding: 5px 10px;
      font-size: 12px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
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
    .dict-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 8px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-xs);
      font-size: 12px;
      user-select: none;
    }
    .dict-item-info { display: flex; align-items: center; gap: 6px; }
    .dict-actions { display: flex; align-items: center; gap: 2px; }

    .dict-ico { font-size: 16px; }
    .dict-name { font-weight: 500; color: var(--text-main); }
    .sys-badge { font-size: 10px; background-color: rgba(99,102,241,0.1); color: var(--primary); padding: 1px 4px; border-radius: 3px; }
    .term-badge { font-size: 10px; background-color: rgba(16,185,129,0.1); color: var(--success); padding: 1px 4px; border-radius: 3px; }
    .mini-del-btn {
      border: none;
      background: transparent;
      color: var(--danger);
      cursor: pointer;
      padding: 2px;
      display: flex;
    }
    .mini-del-btn .material-symbols-outlined { font-size: 16px; }
    .mini-move-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      display: flex;
    }
    .mini-move-btn:disabled { opacity: 0.35; cursor: not-allowed; }
    .mini-move-btn .material-symbols-outlined { font-size: 16px; }

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
    .dict-form-field,
    .color-picker-row { display: flex; flex-direction: column; gap: 4px; }
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

    .status-dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    .font-mono { font-family: monospace; }
    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-danger { color: var(--danger); }
    .text-xs { font-size: 11px; }
    .tabular-nums { font-variant-numeric: tabular-nums; }
  `]
})
export class TasksComponent implements OnInit {
  private readonly uiI18n = inject(I18nService);
  readonly tasks = signal<Task[]>([]);
  readonly projects = signal<Project[]>([]);
  readonly statuses = signal<TaskStatus[]>([]);
  readonly taskTypes = signal<TaskType[]>([]);
  readonly usersList = signal<User[]>([]);
  readonly taskCustomFields = signal<CustomField[]>([]);

  readonly selectedTask = signal<Task | null>(null);
  readonly taskMembers = signal<TaskMember[]>([]);
  readonly taskSubtasks = signal<Task[]>([]);
  readonly taskAncestors = signal<Task[]>([]);
  readonly taskFiles = signal<TaskFile[]>([]);
  readonly comments = signal<TaskComment[]>([]);


  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  nextCursor: string | null = null;

  // View Mode: 'table' (List / Table) is now default as requested
  viewMode: 'table' | 'kanban' = 'table';
  searchQuery = '';
  selectedPriority = '';
  selectedProjectId: number | null = null;
  currentPage = 1;
  pageSize = 10;

  // Status Filter Mode: 'active' (default excludes done & cancelled), 'all', or number (specific status ID)
  statusFilterMode: 'active' | 'all' | number = 'active';


  newCommentText = '';
  draggedTask: Task | null = null;

  showExportMenu = false;

  exportTasks(format: 'xlsx' | 'csv'): void {
    this.showExportMenu = false;
    window.open(`/api/v1/reports/tasks/export?format=${format}`, '_blank');
  }

  // Dictionaries Settings Modal
  readonly isSettingsModalOpen = signal<boolean>(false);
  settingsTab: 'types' | 'statuses' = 'types';
  newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
  newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
  dictionaryDeleteTarget: { kind: 'type' | 'status'; id: number; name: string } | null = null;

  // Create Modal
  readonly isCreateModalOpen = signal<boolean>(false);
  isCreateSubmitted = false;
  createForm = {
    title: '',
    taskType: 'task',
    descriptionMarkdown: '',
    projectId: null as number | null,
    priority: 'medium',
    responsibleUserId: null as number | null,
    parentTaskId: null as number | null,
    observerUserIds: [] as number[],
    beginTime: '',
    endTime: '',
    attributes: {} as Record<string, any>
  };

  // Edit Modal
  readonly isEditModalOpen = signal<boolean>(false);
  isEditSubmitted = false;
  editingTask: Task | null = null;
  editForm = {
    title: '',
    taskType: 'task',
    descriptionMarkdown: '',
    projectId: null as number | null,
    priority: 'medium',
    responsibleUserId: null as number | null,
    parentTaskId: null as number | null,
    observerUserIds: [] as number[],
    beginTime: '',
    endTime: '',
    attributes: {} as Record<string, any>
  };

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService,
    private route: ActivatedRoute
  ) {}

  ngOnInit() {
    this.route.queryParams.subscribe(params => {
      if (params['project_id']) {
        this.selectedProjectId = Number(params['project_id']);
      }
    });

    this.loadStatuses();
    this.loadTypes();
    this.loadProjects();
    this.loadUsers();
    this.loadTaskCustomFields();
    this.loadTasks(true);
  }

  // Active users only for selectors
  activeUsers(): User[] {
    return this.usersList().filter(u => u.state !== 'P' && !u.name.toLowerCase().includes('deleted user'));
  }

  userSelectOptions(): SelectOption[] {
    return this.activeUsers().map(u => ({
      id: u.id,
      label: u.name,
      subLabel: `@${u.login}`
    }));
  }

  taskSelectOptions(): SelectOption[] {
    return this.tasks().map(t => ({
      id: t.id,
      label: `#${t.id} ${t.title}`,
      icon: 'task_alt'
    }));
  }

  getAvailableParentTaskOptions(currentTaskId: number): SelectOption[] {
    return this.tasks()
      .filter(t => t.id !== currentTaskId)
      .map(t => ({
        id: t.id,
        label: `#${t.id} ${t.title}`,
        icon: 'task_alt'
      }));
  }

  canCreateTask(): boolean {
    return this.permService.canCreate('tasks.items') || this.permService.canCreate('tasks') || this.permService.canCreate('ms_tasks');
  }

  canUpdateTask(): boolean {
    return this.permService.canUpdate('tasks.items') || this.permService.canUpdate('tasks') || this.permService.canUpdate('ms_tasks');
  }

  loadStatuses() {
    this.api.get<TaskStatus[]>('/tasks/statuses').subscribe({
      next: res => this.statuses.set(res || []),
      error: () => {}
    });
  }

  loadTypes() {
    this.api.get<TaskType[]>('/tasks/types').subscribe({
      next: res => this.taskTypes.set(res || []),
      error: () => {}
    });
  }

  loadProjects() {
    this.api.get<Project[]>('/tasks/projects').subscribe({
      next: res => this.projects.set(res || []),
      error: () => {}
    });
  }

  loadUsers() {
    this.api.get<KeysetPage<User>>('/iam/users', { limit: 100 }).subscribe({
      next: res => this.usersList.set(res?.items || []),
      error: () => {}
    });
  }

  loadTaskCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'TASK' }).subscribe({
      next: res => this.taskCustomFields.set(res || []),
      error: () => {}
    });
  }

  loadTasks(reset: boolean = false) {
    if (reset) {
      this.nextCursor = null;
    }

    let statusIdParam: number | undefined = undefined;
    let hideTerminalParam: boolean | undefined = undefined;

    if (this.statusFilterMode === 'active') {
      hideTerminalParam = true;
    } else if (this.statusFilterMode === 'all') {
      hideTerminalParam = false;
    } else if (typeof this.statusFilterMode === 'number') {
      statusIdParam = this.statusFilterMode;
    }

    this.isLoading.set(true);
    this.api.get<KeysetPage<Task>>('/tasks', {
      limit: 50,
      cursor: this.nextCursor || undefined,
      search: this.searchQuery || undefined,
      priority: this.selectedPriority || undefined,
      project_id: this.selectedProjectId || undefined,
      status_id: statusIdParam,
      hide_terminal: hideTerminalParam
    }).subscribe({
      next: res => {
        this.isLoading.set(false);
        if (reset) {
          this.tasks.set(res.items || []);
        } else {
          this.tasks.update(cur => [...cur, ...(res.items || [])]);
        }
        this.nextCursor = res.nextCursor;
        this.hasMore.set(res.hasMore);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }

  paginatedTasks(): Task[] {
    const list = this.tasks();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }


  hasActiveFilters(): boolean {
    return !!this.searchQuery || !!this.selectedPriority || this.selectedProjectId !== null || this.statusFilterMode !== 'active';
  }

  clearSearch() {
    this.searchQuery = '';
    this.loadTasks(true);
  }

  setStatusFilterMode(mode: 'active' | 'all' | number) {
    this.statusFilterMode = mode;
    this.loadTasks(true);
  }

  resetFilters() {
    this.searchQuery = '';
    this.selectedPriority = '';
    this.selectedProjectId = null;
    this.statusFilterMode = 'active';
    this.loadTasks(true);
  }

  getTasksByStatus(statusId: number): Task[] {
    return this.tasks().filter(t => t.statusId === statusId);
  }

  isFirstStatus(statusId: number): boolean {
    const list = this.statuses();
    return list.length > 0 && list[0].id === statusId;
  }

  isLastStatus(statusId: number): boolean {
    const list = this.statuses();
    return list.length > 0 && list[list.length - 1].id === statusId;
  }

  moveTaskStatus(task: Task, direction: -1 | 1) {
    const list = this.statuses();
    const currentIndex = list.findIndex(s => s.id === task.statusId);
    if (currentIndex === -1) return;

    const targetIndex = currentIndex + direction;
    if (targetIndex >= 0 && targetIndex < list.length) {
      const targetStatus = list[targetIndex];
      this.updateStatus(task.id, targetStatus.id);
    }
  }

  // =========================================================================
  // Drag & Drop: Angular CDK Handler
  // =========================================================================
  onTaskDrop(event: CdkDragDrop<Task[]>, targetStatusId: number) {
    const task = event.item.data as Task;
    if (!task) return;

    if (task.statusId === targetStatusId) {
      return;
    }

    this.executeStatusChange(task, targetStatusId);
  }

  // =========================================================================
  // Drag & Drop: HTML5 Native Fallback Handlers
  // =========================================================================
  onHtml5DragStart(event: DragEvent, task: Task) {
    this.draggedTask = task;
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', String(task.id));
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onHtml5DragOver(event: DragEvent) {
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    const col = (event.currentTarget as HTMLElement);
    if (col && !col.classList.contains('drag-over')) {
      col.classList.add('drag-over');
    }
  }

  onHtml5DragLeave(event: DragEvent) {
    const col = (event.currentTarget as HTMLElement);
    if (col) {
      col.classList.remove('drag-over');
    }
  }

  onHtml5Drop(event: DragEvent, targetStatusId: number) {
    event.preventDefault();
    const col = (event.currentTarget as HTMLElement);
    if (col) {
      col.classList.remove('drag-over');
    }

    const task = this.draggedTask;
    this.draggedTask = null;
    if (!task || task.statusId === targetStatusId) return;

    this.executeStatusChange(task, targetStatusId);
  }

  private executeStatusChange(task: Task, targetStatusId: number) {
    // Optimistic UI update
    this.tasks.update(list => list.map(t => t.id === task.id ? { ...t, statusId: targetStatusId } : t));
    if (this.selectedTask()?.id === task.id) {
      this.selectedTask.update(t => t ? { ...t, statusId: targetStatusId } : null);
    }

    // Backend update
    this.api.post(`/tasks/${task.id}/status`, { statusId: targetStatusId }).subscribe({
      next: () => {
        const sName = this.getStatusName(targetStatusId);
        this.toast.success(this.uiI18n.translate('tasks.task_moved_to_status', { id: task.id, status: sName }));
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_izmenit_status_zadachi'));
        this.loadTasks(true);
      }
    });
  }

  // =========================================================================
  // CDK Drag & Drop Handlers for Settings Modal
  // =========================================================================
  onStatusDrop(event: CdkDragDrop<TaskStatus[]>) {
    const list = [...this.statuses()];
    moveItemInArray(list, event.previousIndex, event.currentIndex);
    this.statuses.set(list);
    this.persistStatusOrder(list);
  }

  onTypeDrop(event: CdkDragDrop<TaskType[]>) {
    const list = [...this.taskTypes()];
    moveItemInArray(list, event.previousIndex, event.currentIndex);
    this.taskTypes.set(list);
    this.persistTypeOrder(list);
  }

  moveDictionaryStatus(index: number, delta: -1 | 1) {
    const list = [...this.statuses()];
    const nextIndex = index + delta;
    if (index < 0 || nextIndex < 0 || nextIndex >= list.length) return;
    moveItemInArray(list, index, nextIndex);
    this.statuses.set(list);
    this.persistStatusOrder(list);
  }

  moveDictionaryType(index: number, delta: -1 | 1) {
    const list = [...this.taskTypes()];
    const nextIndex = index + delta;
    if (index < 0 || nextIndex < 0 || nextIndex >= list.length) return;
    moveItemInArray(list, index, nextIndex);
    this.taskTypes.set(list);
    this.persistTypeOrder(list);
  }

  private persistStatusOrder(list: TaskStatus[]) {
    const orderedIds = list.map(status => status.id);
    this.api.post('/tasks/statuses/reorder', orderedIds).subscribe({
      next: () => this.toast.success(this.uiI18n.translate('tasks.poryadok_statusov_sohranen')),
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_izmeneniya_poryadka'))
    });
  }

  private persistTypeOrder(list: TaskType[]) {
    const orderedIds = list.map(t => t.id);
    this.api.post('/tasks/types/reorder', orderedIds).subscribe({
      next: () => this.toast.success(this.uiI18n.translate('tasks.poryadok_tipov_zadach_sohranen')),
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_izmeneniya_poryadka'))
    });
  }

  openTaskDetails(task: Task) {
    this.selectedTask.set(task);
    this.loadTaskFullDetails(task.id);
    this.loadComments(task.id);
  }

  loadTaskFullDetails(taskId: number) {
    this.api.get<TaskDetailResponse>(`/tasks/${taskId}`).subscribe({
      next: res => {
        if (res) {
          this.selectedTask.set(res.task);
          this.taskMembers.set(res.members || []);
          this.taskSubtasks.set(res.subtasks || []);
          this.taskAncestors.set(res.ancestors || []);
          this.taskFiles.set(res.files || []);
        }
      },
      error: () => {}
    });
  }

  onTaskFileAttached(file: TaskFile) {
    const t = this.selectedTask();
    if (!t) return;
    this.api.post(`/tasks/${t.id}/files`, { fileId: file.fileId }).subscribe({
      next: () => {
        this.taskFiles.update(list => [...list, file]);
        this.toast.success(this.uiI18n.translate('tasks.file_attached', { name: file.fileName }));
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_prikrepit_fayl'));
      }
    });
  }

  onTaskFileRemoved(file: TaskFile) {
    const t = this.selectedTask();
    if (!t) return;
    this.api.delete(`/tasks/${t.id}/files/${file.fileId}`).subscribe({
      next: () => {
        this.taskFiles.update(list => list.filter(f => f.fileId !== file.fileId));
        this.toast.success(this.uiI18n.translate('tasks.file_removed', { name: file.fileName }));
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('files.ne_udalos_udalit_fayl'));
      }
    });
  }


  loadComments(taskId: number) {
    this.api.get<TaskComment[]>(`/tasks/${taskId}/comments`).subscribe({
      next: res => this.comments.set(res || []),
      error: () => {}
    });
  }

  submitComment() {
    const task = this.selectedTask();
    if (!task || !this.newCommentText.trim()) return;

    this.api.post(`/tasks/${task.id}/comments`, { textMarkdown: this.newCommentText.trim() }).subscribe({
      next: () => {
        this.newCommentText = '';
        this.loadComments(task.id);
        this.toast.success(this.uiI18n.translate('tasks.kommentariy_dobavlen'));
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_otpravit_kommentariy'));
      }
    });
  }

  updateStatus(taskId: number, newStatusId: number) {
    this.api.post(`/tasks/${taskId}/status`, { statusId: newStatusId }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.status_zadachi_obnovlen'));
        this.tasks.update(list => list.map(t => t.id === taskId ? { ...t, statusId: newStatusId } : t));
        if (this.selectedTask()?.id === taskId) {
          this.selectedTask.update(t => t ? { ...t, statusId: newStatusId } : null);
        }
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_izmenit_status'));
      }
    });
  }

  // =========================================================================
  // Task Creation & Subtasks
  // =========================================================================
  openCreateTaskModal() {
    this.isCreateSubmitted = false;
    this.createForm = {
      title: '',
      taskType: this.taskTypes().length > 0 ? this.taskTypes()[0].code : 'task',
      descriptionMarkdown: '',
      projectId: this.selectedProjectId,
      priority: 'medium',
      responsibleUserId: null,
      parentTaskId: null,
      observerUserIds: [],
      beginTime: '',
      endTime: '',
      attributes: {}
    };
    this.isCreateModalOpen.set(true);
  }

  openAddSubtaskModal(parentTask: Task) {
    this.isCreateSubmitted = false;
    this.createForm = {
      title: '',
      taskType: this.taskTypes().length > 0 ? this.taskTypes()[0].code : 'task',
      descriptionMarkdown: '',
      projectId: parentTask.projectId || null,
      priority: parentTask.priority || 'medium',
      responsibleUserId: null,
      parentTaskId: parentTask.id,
      observerUserIds: [],
      beginTime: '',
      endTime: '',
      attributes: {}
    };
    this.isCreateModalOpen.set(true);
  }

  submitCreateTask() {
    this.isCreateSubmitted = true;
    if (!this.createForm.title.trim()) {
      this.toast.warning(this.uiI18n.translate('tasks.ukazhite_nazvanie_zadachi'));
      return;
    }

    const attrs = { ...this.createForm.attributes, task_type: this.createForm.taskType };

    const payload = {
      title: this.createForm.title.trim(),
      descriptionMarkdown: this.createForm.descriptionMarkdown?.trim() || '',
      projectId: this.createForm.projectId ? Number(this.createForm.projectId) : null,
      priority: this.createForm.priority || 'medium',
      responsibleUserId: this.createForm.responsibleUserId ? Number(this.createForm.responsibleUserId) : null,
      parentTaskId: this.createForm.parentTaskId ? Number(this.createForm.parentTaskId) : null,
      observerUserIds: this.createForm.observerUserIds,
      beginTime: this.createForm.beginTime ? new Date(this.createForm.beginTime).toISOString() : null,
      endTime: this.createForm.endTime ? new Date(this.createForm.endTime).toISOString() : null,
      attributes: attrs
    };

    this.isSubmitting.set(true);
    this.api.post<Task>('/tasks', payload).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('tasks.zadacha_uspeshno_sozdana'));
        this.loadTasks(true);
        if (this.selectedTask() && this.createForm.parentTaskId === this.selectedTask()?.id) {
          this.loadTaskFullDetails(this.selectedTask()!.id);
        }
      },
      error: err => {
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_pri_sohranenii_zadachi'));
      }
    });
  }

  // =========================================================================
  // Task Editing
  // =========================================================================
  openEditModal(task: Task) {
    this.isEditSubmitted = false;
    this.editingTask = task;

    this.api.get<TaskDetailResponse>(`/tasks/${task.id}`).subscribe({
      next: res => {
        const obsIds = (res?.members || [])
          .filter(m => (m.involveKind || m.involvementKind) === 'O')
          .map(m => m.userId);

        const respMember = (res?.members || []).find(m => (m.involveKind || m.involvementKind) === 'R');

        this.editForm = {
          title: task.title,
          taskType: (task.attributes && task.attributes['task_type']) || 'task',
          descriptionMarkdown: task.descriptionMarkdown || '',
          projectId: task.projectId || null,
          priority: task.priority || 'medium',
          responsibleUserId: respMember ? respMember.userId : null,
          parentTaskId: task.parentTaskId || null,
          observerUserIds: obsIds,
          beginTime: task.beginTime ? task.beginTime.substring(0, 16) : '',
          endTime: task.endTime ? task.endTime.substring(0, 16) : '',
          attributes: { ...(task.attributes || {}) }
        };
        this.isEditModalOpen.set(true);
      },
      error: () => {
        this.editForm = {
          title: task.title,
          taskType: (task.attributes && task.attributes['task_type']) || 'task',
          descriptionMarkdown: task.descriptionMarkdown || '',
          projectId: task.projectId || null,
          priority: task.priority || 'medium',
          responsibleUserId: null,
          parentTaskId: task.parentTaskId || null,
          observerUserIds: [],
          beginTime: task.beginTime ? task.beginTime.substring(0, 16) : '',
          endTime: task.endTime ? task.endTime.substring(0, 16) : '',
          attributes: { ...(task.attributes || {}) }
        };
        this.isEditModalOpen.set(true);
      }
    });
  }

  submitEditTask() {
    if (!this.editingTask) return;
    this.isEditSubmitted = true;
    if (!this.editForm.title.trim()) {
      this.toast.warning(this.uiI18n.translate('tasks.nazvanie_zadachi_obyazatelno'));
      return;
    }

    const attrs = { ...this.editForm.attributes, task_type: this.editForm.taskType };

    const payload = {
      title: this.editForm.title.trim(),
      descriptionMarkdown: this.editForm.descriptionMarkdown?.trim() || '',
      projectId: this.editForm.projectId ? Number(this.editForm.projectId) : null,
      priority: this.editForm.priority || 'medium',
      responsibleUserId: this.editForm.responsibleUserId ? Number(this.editForm.responsibleUserId) : null,
      parentTaskId: this.editForm.parentTaskId ? Number(this.editForm.parentTaskId) : null,
      observerUserIds: this.editForm.observerUserIds,
      beginTime: this.editForm.beginTime ? new Date(this.editForm.beginTime).toISOString() : null,
      endTime: this.editForm.endTime ? new Date(this.editForm.endTime).toISOString() : null,
      attributes: attrs
    };

    this.isSubmitting.set(true);
    this.api.patch(`/tasks/${this.editingTask.id}`, payload).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success(this.uiI18n.translate('tasks.zadacha_uspeshno_obnovlena'));
        this.loadTasks(true);
        if (this.selectedTask()?.id === this.editingTask?.id) {
          this.loadTaskFullDetails(this.editingTask!.id);
        }
      },
      error: err => {
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_pri_obnovlenii_zadachi'));
      }
    });
  }

  // =========================================================================
  // Dictionaries Management
  // =========================================================================
  openSettingsModal() {
    this.newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
    this.newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
    this.isSettingsModalOpen.set(true);
  }

  submitCreateType() {
    if (!this.newTypeForm.code.trim() || !this.newTypeForm.name.trim()) {
      this.toast.warning(this.uiI18n.translate('tasks.ukazhite_kod_i_nazvanie_tipa'));
      return;
    }

    this.api.post('/tasks/types', {
      code: this.newTypeForm.code.trim(),
      name: this.newTypeForm.name.trim(),
      icon: this.newTypeForm.icon.trim() || 'task_alt',
      color: this.newTypeForm.color,
      orderNo: (this.taskTypes().length + 1) * 10
    }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.tip_zadachi_dobavlen'));
        this.newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
        this.loadTypes();
      },
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_dobavleniya_tipa'))
    });
  }

  submitCreateStatus() {
    if (!this.newStatusForm.name.trim()) {
      this.toast.warning(this.uiI18n.translate('tasks.ukazhite_nazvanie_statusa'));
      return;
    }

    this.api.post('/tasks/statuses', {
      name: this.newStatusForm.name.trim(),
      color: this.newStatusForm.color,
      orderNo: (this.statuses().length + 1) * 10,
      isTerminal: this.newStatusForm.isTerminal
    }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.status_zadachi_dobavlen'));
        this.newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
        this.loadStatuses();
      },
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_dobavleniya_statusa'))
    });
  }

  requestDeleteDictionaryItem(kind: 'type' | 'status', id: number, name: string) {
    this.dictionaryDeleteTarget = { kind, id, name };
  }

  confirmDeleteDictionaryItem() {
    if (!this.dictionaryDeleteTarget) return;
    const target = this.dictionaryDeleteTarget;
    const endpoint = target.kind === 'type' ? `/tasks/types/${target.id}` : `/tasks/statuses/${target.id}`;
    this.api.delete(endpoint).subscribe({
      next: () => {
        this.dictionaryDeleteTarget = null;
        if (target.kind === 'type') {
          this.toast.success(this.uiI18n.translate('tasks.tip_zadachi_udalen'));
          this.loadTypes();
        } else {
          this.toast.success(this.uiI18n.translate('tasks.status_udalen'));
          this.loadStatuses();
        }
      },
      error: err => this.toast.error(err.error?.message || (target.kind === 'type'
        ? this.uiI18n.translate('tasks.oshibka_udaleniya_tipa')
        : this.uiI18n.translate('tasks.nelzya_udalit_status_privyazannyy_k_zadacham')))
    });
  }

  // =========================================================================
  // Helpers
  // =========================================================================
  getTypeObj(task: Task): TaskType | null {
    const code = (task.attributes && task.attributes['task_type']) || 'task';
    return this.taskTypes().find(ty => ty.code === code) || null;
  }

  getTypeLabel(task: Task): string {
    const obj = this.getTypeObj(task);
    return obj ? obj.name : this.uiI18n.translate('tasks.zadacha');
  }

  getTypeIcon(task: Task): string {
    const obj = this.getTypeObj(task);
    return obj ? obj.icon : 'task_alt';
  }

  getTypeColor(task: Task): string {
    const obj = this.getTypeObj(task);
    return obj ? obj.color : 'var(--primary)';
  }

  getTypeBg(task: Task): string {
    const obj = this.getTypeObj(task);
    if (!obj) return 'var(--bg-hover)';
    return `${obj.color}18`;
  }

  getProjectName(projectId: number | null | undefined): string | null {
    if (!projectId) return null;
    const p = this.projects().find(x => x.id === projectId);
    return p ? p.name : `#${projectId}`;
  }

  getStatusName(statusId: number | null | undefined): string {
    if (!statusId) return this.uiI18n.translate('tasks.novaya');
    const s = this.statuses().find(x => x.id === statusId);
    return s ? s.name : this.uiI18n.translate('tasks.v_rabote');
  }

  getStatusColor(statusId: number | null | undefined): string {
    if (!statusId) return 'var(--primary)';
    const s = this.statuses().find(x => x.id === statusId);
    return s?.color || 'var(--primary)';
  }

  getPriorityLabel(priority: string): string {
    switch (priority) {
      case 'critical':
      case 'urgent':
        return this.uiI18n.translate('tasks.kriticheskiy');
      case 'high':
        return this.uiI18n.translate('task.priority.high');
      case 'medium':
      case 'normal':
        return this.uiI18n.translate('tasks.sredniy');
      default:
        return this.uiI18n.translate('task.priority.low');
    }
  }

  isOverdue(endTime: string | null | undefined, statusId: number): boolean {
    if (!endTime) return false;
    const s = this.statuses().find(x => x.id === statusId);
    if (s && s.isTerminal) return false;
    return new Date(endTime).getTime() < Date.now();
  }

  getInvolveKindLabel(kind: string | undefined): string {
    switch (kind) {
      case 'R': return this.uiI18n.translate('task.responsible');
      case 'E': return this.uiI18n.translate('tasks.ispolnitel');
      case 'O': return this.uiI18n.translate('tasks.nablyudatel');
      case 'A': return this.uiI18n.translate('tasks.avtor');
      default: return this.uiI18n.translate('tasks.uchastnik');
    }
  }

  getInitials(name: string | undefined): string {
    if (!name) return 'U';
    const parts = name.trim().split(' ');
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.substring(0, 2).toUpperCase();
  }

  hasAttributes(attrs: any): boolean {
    if (!attrs || typeof attrs !== 'object') return false;
    const keys = Object.keys(attrs).filter(k => k !== 'task_type');
    return keys.length > 0;
  }

  formatAttributes(attrs: any): Array<{ key: string; value: string }> {
    if (!this.hasAttributes(attrs)) return [];
    return Object.entries(attrs)
      .filter(([k]) => k !== 'task_type')
      .map(([k, v]) => ({ key: k, value: String(v) }));
  }
}
