import { Component, OnDestroy, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { canonicalRecordId, recordResponseMatches, safeNumericRecordId } from '../../core/services/search-target';
import { RecordNavigationDecision } from '../../core/guards/record-navigation.guard';
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
import { AuthService } from '../../core/services/auth.service';
import { KeysetPage } from '../../core/models/common.models';
import { TranslatePipe, I18nService } from '../../core/services/i18n.service';
import { Subscription } from 'rxjs';
import { toLocalDateTime, toTaskInstant } from './task-form-value';
import { TaskDictionariesModalComponent } from './components/task-dictionaries-modal.component';
import { TaskKanbanViewComponent } from './components/task-kanban-view.component';
import { TaskTableViewComponent } from './components/task-table-view.component';

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
    UiFileUploadComponent,
    TaskDictionariesModalComponent,
    TaskKanbanViewComponent,
    TaskTableViewComponent
  ],


  template: `
    <div class="tasks-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">{{ 'nav.tasks' | t }}</h1>
          <span class="count-badge">{{ 'tasks.loaded_count' | t:{count: tasks().length} }}</span>

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
            class="btn btn-secondary compact-secondary-action"
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
              class="btn btn-secondary compact-secondary-action"
              [title]="'tasks.export_all_accessible' | t"
              (click)="showExportMenu = !showExportMenu"
            >
              <span class="material-symbols-outlined" aria-hidden="true">download</span>
              <span>{{ 'analytics.eksport' | t }}</span>
              <span class="material-symbols-outlined" style="font-size: 16px;">arrow_drop_down</span>
            </button>
            <div class="export-popover" *ngIf="showExportMenu">
              <strong class="export-scope-title">{{ 'tasks.export_all_accessible' | t }}</strong>
              <span class="export-scope-note">{{ 'tasks.export_filters_not_applied' | t }}</span>
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
        <div class="toolbar-left-row">
          <!-- Smart View Presets -->
          <div class="preset-filter-group" role="group" [attr.aria-label]="'tasks.filtr_po_statusu' | t">
            <button
              type="button"
              class="preset-btn"
              [class.active]="activePreset === 'all'"
              [attr.aria-pressed]="activePreset === 'all'"
              (click)="setPreset('all')"
            >
              <span class="material-symbols-outlined preset-icon" aria-hidden="true">dashboard</span>
              <span>{{ 'tasks.filter_preset_all' | t }}</span>
            </button>
            <button
              type="button"
              class="preset-btn"
              [class.active]="activePreset === 'my'"
              [attr.aria-pressed]="activePreset === 'my'"
              (click)="setPreset('my')"
            >
              <span class="material-symbols-outlined preset-icon" aria-hidden="true">person</span>
              <span>{{ 'tasks.filter_preset_my' | t }}</span>
            </button>
            <button
              type="button"
              class="preset-btn"
              [class.active]="activePreset === 'reported'"
              [attr.aria-pressed]="activePreset === 'reported'"
              (click)="setPreset('reported')"
            >
              <span class="material-symbols-outlined preset-icon" aria-hidden="true">assignment_ind</span>
              <span>{{ 'tasks.filter_preset_reported' | t }}</span>
            </button>
            <button
              type="button"
              class="preset-btn preset-overdue"
              [class.active]="activePreset === 'overdue'"
              [attr.aria-pressed]="activePreset === 'overdue'"
              (click)="setPreset('overdue')"
            >
              <span class="material-symbols-outlined preset-icon" aria-hidden="true">error</span>
              <span>{{ 'tasks.filter_preset_overdue' | t }}</span>
            </button>
          </div>

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
              (ngModelChange)="onTaskSearchChange($event)"
              (keydown.enter)="applyTaskSearchImmediately(); $event.preventDefault()"
            />
            <button *ngIf="searchQuery" type="button" class="clear-btn" [attr.aria-label]="'tasks.ochistit_poisk_zadach' | t" (click)="clearSearch()">
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </div>
        </div>

        <div class="toolbar-controls">
          <!-- Status Filter Tabs (Default: 'active' which excludes done & cancelled) -->
          <div class="status-tabs" role="group" [attr.aria-label]="'tasks.filtr_po_statusu' | t">
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

      <div class="request-state request-loading" *ngIf="isLoading()" role="status">
        {{ 'common.loading' | t }}
      </div>
      <div class="request-state request-error" *ngIf="listLoadError()" role="alert">
        <span>{{ (tasks().length ? 'tasks.list_load_error_stale' : 'tasks.list_load_error') | t }}</span>
        <ui-button variant="secondary" size="sm" (onClick)="retryTaskList()">{{ 'audit.retry' | t }}</ui-button>
      </div>

      <!-- ======================================================================= -->
      <!-- VIEW 1: TABLE / LIST VIEW (Default View)                                -->
      <!-- ======================================================================= -->
      <app-task-table-view
        *ngIf="viewMode === 'table'"
        [tasks]="tasks()"
        [paginatedTasks]="paginatedTasks()"
        [statuses]="statuses()"
        [projects]="projects()"
        [taskTypes]="taskTypes()"
        [canCreateTask]="canCreateTask()"
        [canUpdateTask]="canUpdateTask()"
        [hasActiveFilters]="hasActiveFilters()"
        [isLoading]="isLoading()"
        [listLoadError]="listLoadError()"
        [isOverdue]="isOverdueFn"
        [getTypeColor]="getTypeColorFn"
        [getTypeBg]="getTypeBgFn"
        [getTypeIcon]="getTypeIconFn"
        [getTypeLabel]="getTypeLabelFn"
        [getProjectName]="getProjectNameFn"
        [getStatusColor]="getStatusColorFn"
        [getDeadlineInfo]="getDeadlineInfoFn"
        (openTaskDetails)="openTaskDetails($event)"
        (openEditModal)="openEditModal($event)"
        (updatePriority)="updatePriority($event.taskId, $event.priority)"
        (updateStatus)="updateStatus($event.taskId, $event.statusId)"
        (resetFilters)="resetFilters()"
        (createTask)="openCreateTaskModal()"
      ></app-task-table-view>


      <!-- ======================================================================= -->
      <!-- VIEW 2: KANBAN BOARD WITH DRAG & DROP                                  -->
      <!-- ======================================================================= -->
      <app-task-kanban-view
        *ngIf="viewMode === 'kanban'"
        [tasks]="tasks()"
        [statuses]="statuses()"
        [projects]="projects()"
        [taskTypes]="taskTypes()"
        [canCreateTask]="canCreateTask()"
        [canUpdateTask]="canUpdateTask()"
        [hasActiveFilters]="hasActiveFilters()"
        [isLoading]="isLoading()"
        [listLoadError]="listLoadError()"
        [getDeadlineInfo]="getDeadlineInfoFn"
        [getPriorityLabel]="getPriorityLabelFn"
        [getTypeColor]="getTypeColorFn"
        [getTypeIcon]="getTypeIconFn"
        [getProjectName]="getProjectNameFn"
        [isOverdue]="isOverdueFn"
        (openTaskDetails)="openTaskDetails($event)"
        (taskStatusChange)="executeStatusChange($event.task, $event.targetStatusId)"
        (taskDragStart)="draggedTask = $event"
        (taskDragEnd)="draggedTask = null"
        (resetFilters)="resetFilters()"
        (createTask)="openCreateTaskModal()"
      ></app-task-kanban-view>

      <ui-pagination
        [totalItems]="tasks().length"
        [currentPage]="currentPage"
        [pageSize]="pageSize"
        [showPageSize]="false"
        [cursorMode]="true"
        [cursorItemsArePageLength]="true"
        [hasNextPage]="hasMore()"
        [disabled]="isLoading() || listLoadError()"
        (pageChange)="goToTaskPage($event)"
      ></ui-pagination>
    </div>

    <!-- ======================================================================= -->
    <!-- Task Details Modal (Sleek Linear/Jira 2-Pane View)                     -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="(selectedTask() !== null || routeRecordId() !== null) && !isEditModalOpen()"
      [title]="'tasks.task_number' | t:{id: detailRecordId() || ''}"
      size="lg"
      (close)="closeTaskDetails()"
    >
      <div body class="request-state request-loading" *ngIf="detailLoading()" role="status">
        {{ 'tasks.detail_loading' | t }}
      </div>

      <div body class="request-state request-error" *ngIf="detailLoadError()" role="alert">
        <span>{{ (detailNotFound() ? 'search.record_not_found' : 'tasks.detail_load_error') | t }}</span>
        <ui-button *ngIf="!detailNotFound()" variant="secondary" size="sm" (onClick)="retryTaskDetails()">{{ 'audit.retry' | t }}</ui-button>
        <ui-button *ngIf="detailNotFound()" variant="secondary" size="sm" (onClick)="closeTaskDetails()">{{ 'search.back_to_list' | t }}</ui-button>
      </div>
      <div body class="task-details-view" [attr.data-record-id]="detailRecordId()" *ngIf="!detailLoading() && !detailLoadError() && selectedTask() as t">
        <p *ngIf="!safeRecordId(t.id)" role="status">{{ 'search.record_readonly_id' | t }}</p>
        <!-- Ancestor Breadcrumbs Trail -->
        <div class="ancestor-trail" *ngIf="taskAncestors().length > 0">
          <span class="trail-label">{{ 'tasks.ierarhiya' | t }}</span>
          <ng-container *ngFor="let anc of taskAncestors()">
            <button type="button" class="anc-link" [disabled]="!safeRecordId(anc.id)" (click)="openTaskDetails(anc)">
              #{{ anc.id }} {{ anc.title }}
            </button>
            <span class="anc-sep" aria-hidden="true">›</span>
          </ng-container>
          <span class="anc-current">#{{ detailRecordId() }} {{ t.title }}</span>
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
                  *ngIf="canCreateTask() && safeRecordId(t.id)"
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
                  [disabled]="!safeRecordId(sub.id)"
                  [attr.aria-label]="'tasks.open_subtask_named' | t:{id: sub.id, title: sub.title}"
                  [class.row-overdue]="isOverdue(sub.endTime, sub.statusId)"
                  (click)="openTaskDetails(sub)"
                >
                  <span class="subtask-type" [style.color]="getTypeColor(sub)">
                    <span class="material-symbols-outlined type-icon">{{ getTypeIcon(sub) }}</span>
                  </span>
                  <span class="font-mono text-muted text-xs">#{{ sub.id }}</span>
                  <span class="subtask-title">{{ sub.title }}</span>
                  <span class="inline-status-badge status-label">
                    <span class="status-dot" [style.background-color]="getStatusColor(sub.statusId)" aria-hidden="true"></span>
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
                [canUpload]="canUpdateTask() && safeRecordId(t.id)"
                [canDelete]="canUpdateTask() && safeRecordId(t.id)"
                (fileAttached)="onTaskFileAttached($event)"
                (fileRemoved)="onTaskFileRemoved($event)"
              ></ui-file-upload>
            </div>


            <!-- Comments Feed -->
            <div class="detail-section comments-section">
              <h4 class="section-label">{{ 'tasks.comments_count' | t:{count: comments().length} }}</h4>

              <div class="request-state request-loading" *ngIf="commentsLoading()" role="status">
                {{ 'tasks.comments_loading' | t }}
              </div>
              <div class="request-state request-error" *ngIf="commentsLoadError()" role="alert">
                <span>{{ 'tasks.comments_load_error' | t }}</span>
                <ui-button variant="secondary" size="sm" (onClick)="retryComments()">{{ 'audit.retry' | t }}</ui-button>
              </div>

              <div class="comments-feed" *ngIf="!commentsLoading() && !commentsLoadError()">
                <div *ngFor="let c of comments()" class="comment-card">
                  <div class="comment-top">
                    <div class="comment-author-badge">
                      <span class="avatar-mini">{{ getInitials(c.userName || undefined) }}</span>
                      <span class="comment-author">{{ c.userName || ('tasks.removed_comment_author' | t) }} <span *ngIf="c.userLogin" class="text-muted">&#64;{{ c.userLogin }}</span></span>
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

              <div class="add-comment-box" *ngIf="canCommentTask()">
                <textarea
                  class="comment-textarea"
                  [attr.aria-label]="'tasks.comment_task_aria' | t:{id: t.id}"
                  rows="2"
                  [placeholder]="'tasks.napisat_kommentariy_k_zadache_ctrl_enter_dlya_ot' | t"
                  [(ngModel)]="commentDraft"
                  [disabled]="isCommentSubmitting()"
                  (keydown.ctrl.enter)="submitComment()"
                ></textarea>
                <ui-button variant="primary" size="sm" icon="send" [loading]="isCommentSubmitting()" (onClick)="submitComment()">
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
                  <span class="status-dot" [style.background-color]="getStatusColor(t.statusId)" aria-hidden="true"></span>
                  <select
                    class="clean-select status-select"
                    [ngModel]="t.statusId"
                    (ngModelChange)="updateStatus(t.id, $event)"
                    [disabled]="!canUpdateTask() || !safeRecordId(t.id)"
                    [attr.aria-label]="'tasks.task_status_aria' | t:{id: t.id}"
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
              <h5 class="side-card-title">{{ 'nav.custom_fields' | t }}</h5>
              <div class="attributes-stack">
                <div *ngFor="let item of formatAttributes(t.attributes)" class="attr-stack-item">
                  <span class="attr-k">{{ item.key }}:</span>
                  <span class="attr-v">{{ item.value }}</span>
                </div>
              </div>
            </div>

            <button
              *ngIf="canUpdateTask() && safeRecordId(t.id)"
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
        <ui-button variant="secondary" size="md" (onClick)="closeTaskDetails()">{{ 'audit.zakryt' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Create Task Modal (With RichText MD Editor & User Multi-Select)         -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      [title]="createForm.parentTaskId ? ('tasks.create_subtask_for' | t:{id: createForm.parentTaskId}) : ('tasks.create_new_task' | t)"
      size="lg"
      [dismissible]="!isSubmitting()"
      (close)="requestCloseCreate()"
    >
      <fieldset body class="modal-form modal-form-fieldset task-create-form" [disabled]="isSubmitting()">
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
              [options]="parentTaskOptions()"
              [selectedId]="createForm.parentTaskId"
              [ariaLabel]="'task.parent' | t"
              (selectedIdChange)="createForm.parentTaskId = $event"
              [placeholder]="'tasks.bez_roditelya_kornevaya_zadacha' | t"
              [searchPlaceholder]="'tasks.poisk_zadachi_po_id_ili_nazvaniyu' | t"
              [emptyLabel]="'tasks.without_parent' | t"
              [remoteSearch]="true"
              [loading]="parentLookupLoading()"
              [loadError]="parentLookupError()"
              [hasMore]="parentLookupHasMore()"
              (searchChange)="onParentSearch($event)"
              (loadMore)="loadMoreParents()"
              (retry)="retryParentLookup()"
            ></ui-searchable-select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'task.responsible' | t }}</span>
            </div>
            <ui-searchable-select
              [options]="responsibleUserOptions()"
              [selectedId]="createForm.responsibleUserId"
              [ariaLabel]="'task.responsible' | t"
              (selectedIdChange)="createForm.responsibleUserId = $event"
              [placeholder]="'tasks.vyberite_otvetstvennogo' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'common.not_assigned' | t"
              [remoteSearch]="true"
              [loading]="responsibleLookupLoading()"
              [loadError]="responsibleLookupError()"
              [hasMore]="responsibleLookupHasMore()"
              (searchChange)="onResponsibleSearch($event)"
              (loadMore)="loadMoreResponsibleUsers()"
              (retry)="retryResponsibleLookup()"
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
            [users]="observerUsers()"
            [selectedUserIds]="createForm.observerUserIds"
            [ariaLabel]="'tasks.nablyudateli' | t"
            (selectedUserIdsChange)="createForm.observerUserIds = $event"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_nablyudateley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t"
            [remoteSearch]="true"
            [loading]="observerLookupLoading()"
            [loadError]="observerLookupError()"
            [hasMore]="observerLookupHasMore()"
            (searchChange)="onObserverSearch($event)"
            (loadMore)="loadMoreObservers()"
            (retry)="retryObserverLookup()"
          ></ui-user-multi-select>
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
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">
            <span>{{ 'nav.custom_fields' | t }}</span>
          </h4>
          <ui-custom-fields
            [fields]="taskCustomFields()"
            [(values)]="createForm.attributes"
          ></ui-custom-fields>
        </div>

        <div class="custom-fields-empty-tip" *ngIf="taskCustomFields().length === 0">
          <span class="material-symbols-outlined tip-icon" aria-hidden="true">extension</span>
          <span class="tip-text">{{ 'tasks.nuzhny_specificheskie_polya_byudzhet_nomer_dogov' | t }} <strong>{{ 'nav.custom_fields' | t }}</strong>.</span>
        </div>
      </fieldset>
      <div footer>
        <ui-button variant="secondary" size="md" [disabled]="isSubmitting()" (onClick)="requestCloseCreate()">{{ 'common.cancel' | t }}</ui-button>
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
      [dismissible]="!isSubmitting()"
      (close)="requestCloseEdit()"
    >
      <div body class="request-state request-loading" *ngIf="editLoading()" role="status">
        {{ 'tasks.edit_loading' | t }}
      </div>
      <div body class="request-state request-error" *ngIf="editLoadError()" role="alert">
        <span>{{ 'tasks.edit_load_error' | t }}</span>
        <ui-button variant="secondary" size="sm" (onClick)="retryEditLoad()">{{ 'audit.retry' | t }}</ui-button>
      </div>
      <fieldset body class="modal-form modal-form-fieldset task-edit-form" [disabled]="isSubmitting()" *ngIf="editingTask as task">
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
              [remoteSearch]="true"
              [loading]="parentLookupLoading()"
              [loadError]="parentLookupError()"
              [hasMore]="parentLookupHasMore()"
              (searchChange)="onParentSearch($event)"
              (loadMore)="loadMoreParents()"
              (retry)="retryParentLookup()"
            ></ui-searchable-select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <span class="clean-label">{{ 'task.responsible' | t }}</span>
            </div>
            <ui-searchable-select
              [options]="responsibleUserOptions()"
              [selectedId]="editForm.responsibleUserId"
              [ariaLabel]="'task.responsible' | t"
              (selectedIdChange)="editForm.responsibleUserId = $event"
              [placeholder]="'tasks.vyberite_otvetstvennogo' | t"
              [searchPlaceholder]="'tasks.poisk_sotrudnika_po_imeni_ili_loginu' | t"
              [emptyLabel]="'common.not_assigned' | t"
              [remoteSearch]="true"
              [loading]="responsibleLookupLoading()"
              [loadError]="responsibleLookupError()"
              [hasMore]="responsibleLookupHasMore()"
              (searchChange)="onResponsibleSearch($event)"
              (loadMore)="loadMoreResponsibleUsers()"
              (retry)="retryResponsibleLookup()"
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
            [users]="observerUsers()"
            [selectedUserIds]="editForm.observerUserIds"
            [ariaLabel]="'tasks.nablyudateli' | t"
            (selectedUserIdsChange)="editForm.observerUserIds = $event"
            [placeholder]="'tasks.nazhmite_dlya_dobavleniya_nablyudateley' | t"
            [searchPlaceholder]="'tasks.poisk_sotrudnika' | t"
            [remoteSearch]="true"
            [loading]="observerLookupLoading()"
            [loadError]="observerLookupError()"
            [hasMore]="observerLookupHasMore()"
            (searchChange)="onObserverSearch($event)"
            (loadMore)="loadMoreObservers()"
            (retry)="retryObserverLookup()"
          ></ui-user-multi-select>
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
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">{{ 'nav.custom_fields' | t }}</h4>
          <ui-custom-fields
            [fields]="taskCustomFields()"
            [(values)]="editForm.attributes"
          ></ui-custom-fields>
        </div>
      </fieldset>
      <div footer>
        <ui-button variant="secondary" size="md" [disabled]="isSubmitting()" (onClick)="requestCloseEdit()">{{ editLoadError() ? ('audit.zakryt' | t) : ('common.cancel' | t) }}</ui-button>
        <ui-button *ngIf="editingTask" variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditTask()">{{ 'tasks.sohranit_izmeneniya' | t }}</ui-button>
      </div>
    </ui-modal>

    <ui-modal
      [isOpen]="isEditDiscardConfirmationOpen()"
      [title]="'tasks.discard_edit_title' | t"
      size="sm"
      (close)="cancelDiscardEdit()"
    >
      <div body class="dictionary-delete-body">
        <p>{{ 'tasks.discard_edit_message' | t }}</p>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="cancelDiscardEdit()">{{ 'common.cancel' | t }}</ui-button>
        <ui-button variant="danger" size="md" (onClick)="confirmDiscardEdit()">{{ 'tasks.discard_edit_action' | t }}</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Dictionaries Settings Modal (Delegated Component)                       -->
    <!-- ======================================================================= -->
    <app-task-dictionaries-modal
      [isOpen]="isSettingsModalOpen()"
      [taskTypes]="taskTypes()"
      [statuses]="statuses()"
      (close)="isSettingsModalOpen.set(false)"
      (createType)="handleCreateType($event)"
      (createStatus)="handleCreateStatus($event)"
      (deleteItem)="handleDeleteDictionaryItem($event)"
      (reorderTypes)="handleReorderTypes($event)"
      (reorderStatuses)="handleReorderStatuses($event)"
    ></app-task-dictionaries-modal>
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

    .export-popover {
      position: absolute;
      top: 100%;
      right: 0;
      margin-top: 4px;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: var(--shadow-md);
      z-index: 100;
      min-width: 230px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
    .export-scope-title { padding: 10px 12px 2px; color: var(--text-main); font-size: 12px; }
    .export-scope-note { padding: 0 12px 8px; color: var(--text-muted); font-size: 11px; line-height: 1.35; }

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
    .toolbar-left-row {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }
    .preset-filter-group {
      display: flex;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
    }
    .preset-btn {
      border: none;
      background: transparent;
      min-height: 28px;
      padding: 3px 9px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 5px;
      transition: all 0.1s ease;
      white-space: nowrap;
    }
    .preset-btn:hover { color: var(--text-main); }
    .preset-btn.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
      font-weight: 600;
    }
    .preset-btn .preset-icon { font-size: 15px; color: var(--text-muted); }
    .preset-btn.active .preset-icon { color: var(--primary); }
    .preset-btn.preset-overdue.active { color: var(--danger); }
    .preset-btn.preset-overdue.active .preset-icon { color: var(--danger); }
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
      white-space: nowrap;
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
      white-space: nowrap;
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
    .task-title-open,
    .kanban-title-open {
      border: 0;
      padding: 0;
      background: transparent;
      color: var(--text-main);
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .task-title-open:hover,
    .kanban-title-open:hover { color: var(--primary); text-decoration: underline; }
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

    /* Inline Priority Select */
    .inline-priority-wrapper { display: inline-flex; align-items: center; }
    .inline-priority-select {
      border: 1px solid transparent;
      border-radius: 10px;
      padding: 2px 6px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      background-color: var(--bg-hover);
      color: var(--text-muted);
      outline: none;
      transition: all 0.12s ease;
      font-family: inherit;
    }
    .inline-priority-select:hover:not(:disabled) { border-color: var(--border-color); }
    .inline-priority-select[data-priority="low"] {
      background-color: rgba(16, 185, 129, 0.12);
      color: #10b981;
    }
    .inline-priority-select[data-priority="medium"],
    .inline-priority-select[data-priority="normal"] {
      background-color: rgba(59, 130, 246, 0.12);
      color: #3b82f6;
    }
    .inline-priority-select[data-priority="high"] {
      background-color: rgba(245, 158, 11, 0.15);
      color: #d97706;
    }
    .inline-priority-select[data-priority="critical"],
    .inline-priority-select[data-priority="urgent"] {
      background-color: rgba(239, 68, 68, 0.15);
      color: #ef4444;
    }
    .inline-priority-select:disabled { cursor: default; }

    /* Inline Status Select */
    .inline-status-wrapper { display: inline-flex; align-items: center; gap: 5px; }
    .inline-status-select {
      height: 26px;
      padding: 1px 6px;
      font-size: 11px;
      font-weight: 500;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
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
      white-space: nowrap;
    }
    .deadline-pill .ico { font-size: 13px; }
    .deadline-pill.overdue {
      color: var(--danger);
      background-color: var(--danger-bg);
      border-color: rgba(239,68,68,0.3);
      font-weight: 600;
    }
    .deadline-pill.deadline-today {
      background-color: rgba(245, 158, 11, 0.15);
      border-color: rgba(245, 158, 11, 0.35);
      color: #d97706;
      font-weight: 600;
    }
    .deadline-pill.deadline-tomorrow {
      background-color: rgba(59, 130, 246, 0.12);
      border-color: rgba(59, 130, 246, 0.3);
      color: #2563eb;
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
      cursor: default;
      transition: all 0.12s ease;
      box-shadow: var(--shadow-sm);
      user-select: none;
    }
    .kanban-card.can-drag { cursor: grab; }
    .kanban-card.can-drag:active { cursor: grabbing; }
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
    .status-label { display: inline-flex; align-items: center; gap: 4px; color: var(--text-main); }
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
    .modal-form-fieldset { border: 0; margin: 0; padding: 0; min-width: 0; }
    .form-grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
    .form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

    @media (max-width: 640px) {
      .tasks-page { gap: 10px; }
      .view-header {
        align-items: flex-start;
        flex-direction: column;
      }
      .header-right {
        width: 100%;
        flex-wrap: wrap;
        gap: 6px;
      }
      .header-right > ui-button { order: -1; }
      .compact-secondary-action { padding-inline: 7px; }
      .compact-secondary-action > span:not(.material-symbols-outlined) { display: none; }
      .toolbar { padding: 7px; gap: 8px; }
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
    .kanban-empty-recovery {
      grid-column: 1 / -1;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      padding: 12px;
      color: var(--text-muted);
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
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

    .request-state {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      min-height: 48px;
      padding: 10px 14px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background: var(--bg-surface);
      color: var(--text-muted);
      font-size: 12px;
    }
    .request-error { color: var(--danger); }

    .status-dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    .font-mono { font-family: monospace; }
    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-danger { color: var(--danger); }
    .text-xs { font-size: 11px; }
    .tabular-nums { font-variant-numeric: tabular-nums; }
  `]
})
export class TasksComponent implements OnInit, OnDestroy {
  private readonly uiI18n = inject(I18nService);
  private readonly authService = inject(AuthService, { optional: true });
  private readonly recordRouter = inject(Router, { optional: true });
  activePreset: 'all' | 'my' | 'reported' | 'overdue' = 'all';
  readonly routeRecordId = signal<string | null>(null);
  readonly detailNotFound = signal(false);
  readonly safeRecordId = safeNumericRecordId;
  private recordRouteSubscription?: Subscription;
  private readonly navigationDecision = new RecordNavigationDecision();
  private createFormBaseline = '';
  readonly tasks = signal<Task[]>([]);
  readonly projects = signal<Project[]>([]);
  readonly statuses = signal<TaskStatus[]>([]);
  readonly taskTypes = signal<TaskType[]>([]);
  readonly parentTaskOptions = signal<SelectOption[]>([]);
  readonly responsibleUsers = signal<User[]>([]);
  readonly observerUsers = signal<User[]>([]);
  readonly parentLookupLoading = signal(false);
  readonly parentLookupError = signal(false);
  readonly parentLookupHasMore = signal(false);
  readonly responsibleLookupLoading = signal(false);
  readonly responsibleLookupError = signal(false);
  readonly responsibleLookupHasMore = signal(false);
  readonly observerLookupLoading = signal(false);
  readonly observerLookupError = signal(false);
  readonly observerLookupHasMore = signal(false);
  readonly taskCustomFields = signal<CustomField[]>([]);

  readonly selectedTask = signal<Task | null>(null);
  readonly taskMembers = signal<TaskMember[]>([]);
  readonly taskSubtasks = signal<Task[]>([]);
  readonly taskAncestors = signal<Task[]>([]);
  readonly taskFiles = signal<TaskFile[]>([]);
  readonly comments = signal<TaskComment[]>([]);

  readonly isLoading = signal<boolean>(false);
  readonly listLoadError = signal<boolean>(false);
  readonly detailLoading = signal<boolean>(false);
  readonly detailLoadError = signal<boolean>(false);
  readonly commentsLoading = signal<boolean>(false);
  readonly commentsLoadError = signal<boolean>(false);
  readonly editLoading = signal<boolean>(false);
  readonly editLoadError = signal<boolean>(false);
  readonly isCommentSubmitting = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  nextCursor: string | null = null;

  private destroyed = false;
  private listRequestId = 0;
  private detailRequestId = 0;
  private detailContextId = 0;
  private commentsRequestId = 0;
  private editRequestId = 0;
  private commentPostRequestId = 0;
  private listRequest?: Subscription;
  private detailRequest?: Subscription;
  private commentsRequest?: Subscription;
  private editRequest?: Subscription;
  private editSaveRequest?: Subscription;
  private commentPostRequest?: Subscription;
  private routeSubscription?: Subscription;
  private taskPageCursors: Array<string | null> = [null];
  private parentLookupRequest?: Subscription;
  private responsibleLookupRequest?: Subscription;
  private observerLookupRequest?: Subscription;
  private parentLookupRequestId = 0;
  private responsibleLookupRequestId = 0;
  private observerLookupRequestId = 0;
  private parentSearchTimer?: ReturnType<typeof setTimeout>;
  private responsibleSearchTimer?: ReturnType<typeof setTimeout>;
  private observerSearchTimer?: ReturnType<typeof setTimeout>;
  private taskSearchTimer?: ReturnType<typeof setTimeout>;
  private parentLookupQuery = '';
  private responsibleLookupQuery = '';
  private observerLookupQuery = '';
  private parentLookupCursor: string | null = null;
  private responsibleLookupCursor: string | null = null;
  private observerLookupCursor: string | null = null;
  private parentLastReset = true;
  private responsibleLastReset = true;
  private observerLastReset = true;
  private lastListAttempt: { page: number; cursor: string | null; reset: boolean } | null = null;
  private readonly retainedParentOptions = new Map<number, SelectOption>();
  private readonly retainedUsers = new Map<number, User>();

  // View Mode: 'table' (List / Table) is now default as requested
  viewMode: 'table' | 'kanban' = 'table';
  searchQuery = '';
  selectedPriority = '';
  selectedProjectId: number | null = null;
  currentPage = 1;
  readonly pageSize = 50;

  // Status Filter Mode: 'active' (default excludes done & cancelled), 'all', or number (specific status ID)
  statusFilterMode: 'active' | 'all' | number = 'active';


  private readonly commentDrafts = new Map<number, string>();
  draggedTask: Task | null = null;

  readonly getDeadlineInfoFn = (endTime: string | null | undefined, statusId: number) => this.getDeadlineInfo(endTime, statusId);
  readonly getPriorityLabelFn = (priority: string) => this.getPriorityLabel(priority);
  readonly getTypeColorFn = (task: Task) => this.getTypeColor(task);
  readonly getTypeIconFn = (task: Task) => this.getTypeIcon(task);
  readonly getProjectNameFn = (projectId: number | null | undefined) => this.getProjectName(projectId);
  readonly isOverdueFn = (endTime: string | null | undefined, statusId: number) => this.isOverdue(endTime, statusId);
  readonly getTypeLabelFn = (task: Task) => this.getTypeLabel(task);
  readonly getTypeBgFn = (task: Task) => this.getTypeBg(task);
  readonly getStatusColorFn = (statusId: number | null | undefined) => this.getStatusColor(statusId);

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
  readonly isEditDiscardConfirmationOpen = signal<boolean>(false);
  isEditSubmitted = false;
  editingTask: Task | null = null;
  private editTargetId: number | null = null;
  private editReturnTask: Task | null = null;
  private editFormBaseline = '';
  private editAssignmentBaseline: { parentTaskId: number | null; responsibleUserId: number | null; observerUserIds: number[] } | null = null;
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
    this.routeSubscription = this.route.queryParams.subscribe(params => {
      if (params['project_id']) {
        this.selectedProjectId = Number(params['project_id']);
      }
    });

    this.loadStatuses();
    this.loadTypes();
    this.loadProjects();
    this.loadTaskCustomFields();
    this.loadTasks(true);
    this.recordRouteSubscription = this.route.paramMap?.subscribe(params => {
      this.clearTaskDetails();
      const id = params.get('id');
      this.routeRecordId.set(id);
      if (id === null) return;
      if (!canonicalRecordId(id)) {
        this.detailNotFound.set(true);
        this.detailLoadError.set(true);
        return;
      }
      this.loadTaskFullDetails(id);
      this.loadComments(id);
    });
  }

  ngOnDestroy() {
    this.navigationDecision.settle(false);
    this.destroyed = true;
    this.listRequestId++;
    this.detailRequestId++;
    this.commentsRequestId++;
    this.editRequestId++;
    this.commentPostRequestId++;
    this.parentLookupRequestId++;
    this.responsibleLookupRequestId++;
    this.observerLookupRequestId++;
    clearTimeout(this.parentSearchTimer);
    clearTimeout(this.responsibleSearchTimer);
    clearTimeout(this.observerSearchTimer);
    clearTimeout(this.taskSearchTimer);
    this.routeSubscription?.unsubscribe();
    this.recordRouteSubscription?.unsubscribe();
    this.listRequest?.unsubscribe();
    this.detailRequest?.unsubscribe();
    this.commentsRequest?.unsubscribe();
    this.editRequest?.unsubscribe();
    this.editSaveRequest?.unsubscribe();
    this.commentPostRequest?.unsubscribe();
    this.parentLookupRequest?.unsubscribe();
    this.responsibleLookupRequest?.unsubscribe();
    this.observerLookupRequest?.unsubscribe();
  }

  get commentDraft(): string {
    const taskId = this.selectedTask()?.id;
    return taskId == null ? '' : this.commentDrafts.get(taskId) || '';
  }

  set commentDraft(value: string) {
    const taskId = this.selectedTask()?.id;
    if (taskId != null) this.commentDrafts.set(taskId, value);
  }

  responsibleUserOptions(): SelectOption[] {
    return this.responsibleUsers().map(user => ({
      id: user.id,
      label: user.name,
      subLabel: `@${user.login}`
    }));
  }

  getAvailableParentTaskOptions(currentTaskId: number): SelectOption[] {
    return this.parentTaskOptions().filter(option => Number(option.id) !== currentTaskId);
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

  onParentSearch(query: string) {
    this.parentLookupQuery = query.trim();
    clearTimeout(this.parentSearchTimer);
    this.parentLookupRequestId++;
    this.parentLookupRequest?.unsubscribe();
    this.parentLookupCursor = null;
    this.parentLookupHasMore.set(false);
    this.parentLookupLoading.set(true);
    this.parentLookupError.set(false);
    this.parentSearchTimer = setTimeout(() => this.loadParentTasks(true), 300);
  }

  loadMoreParents() {
    if (this.parentLookupCursor && !this.parentLookupLoading()) this.loadParentTasks(false);
  }

  retryParentLookup() {
    this.loadParentTasks(this.parentLastReset);
  }

  onResponsibleSearch(query: string) {
    this.responsibleLookupQuery = query.trim();
    clearTimeout(this.responsibleSearchTimer);
    this.responsibleLookupRequestId++;
    this.responsibleLookupRequest?.unsubscribe();
    this.responsibleLookupCursor = null;
    this.responsibleLookupHasMore.set(false);
    this.responsibleLookupLoading.set(true);
    this.responsibleLookupError.set(false);
    this.responsibleSearchTimer = setTimeout(() => this.loadResponsibleUsers(true), 300);
  }

  loadMoreResponsibleUsers() {
    if (this.responsibleLookupCursor && !this.responsibleLookupLoading()) this.loadResponsibleUsers(false);
  }

  retryResponsibleLookup() {
    this.loadResponsibleUsers(this.responsibleLastReset);
  }

  onObserverSearch(query: string) {
    this.observerLookupQuery = query.trim();
    clearTimeout(this.observerSearchTimer);
    this.observerLookupRequestId++;
    this.observerLookupRequest?.unsubscribe();
    this.observerLookupCursor = null;
    this.observerLookupHasMore.set(false);
    this.observerLookupLoading.set(true);
    this.observerLookupError.set(false);
    this.observerSearchTimer = setTimeout(() => this.loadObserverUsers(true), 300);
  }

  loadMoreObservers() {
    if (this.observerLookupCursor && !this.observerLookupLoading()) this.loadObserverUsers(false);
  }

  retryObserverLookup() {
    this.loadObserverUsers(this.observerLastReset);
  }

  private loadParentTasks(reset: boolean) {
    this.parentLastReset = reset;
    if (reset) this.parentLookupCursor = null;
    const requestId = ++this.parentLookupRequestId;
    this.parentLookupRequest?.unsubscribe();
    this.parentLookupLoading.set(true);
    this.parentLookupError.set(false);
    this.parentLookupRequest = this.api.get<KeysetPage<Task>>('/tasks', {
      limit: 50,
      cursor: this.parentLookupCursor || undefined,
      search: this.parentLookupQuery || undefined
    }).subscribe({
      next: page => {
        if (this.destroyed || requestId !== this.parentLookupRequestId) return;
        const incoming = (page.items || []).map(item => {
          const option = { id: item.id, label: `#${item.id} ${item.title}`, icon: 'task_alt' };
          this.retainedParentOptions.set(item.id, option);
          return option;
        });
        const selectedId = this.isEditModalOpen() ? this.editForm.parentTaskId : this.createForm.parentTaskId;
        const retained = selectedId == null ? [] : [this.retainedParentOptions.get(selectedId)].filter((item): item is SelectOption => !!item);
        this.parentTaskOptions.set(this.mergeOptions(reset ? retained : this.parentTaskOptions(), incoming));
        this.parentLookupCursor = page.nextCursor;
        this.parentLookupHasMore.set(page.hasMore);
        this.parentLookupLoading.set(false);
      },
      error: () => {
        if (this.destroyed || requestId !== this.parentLookupRequestId) return;
        this.parentLookupLoading.set(false);
        this.parentLookupError.set(true);
      }
    });
  }

  private loadResponsibleUsers(reset: boolean) {
    this.responsibleLastReset = reset;
    if (reset) this.responsibleLookupCursor = null;
    const requestId = ++this.responsibleLookupRequestId;
    this.responsibleLookupRequest?.unsubscribe();
    this.responsibleLookupLoading.set(true);
    this.responsibleLookupError.set(false);
    this.responsibleLookupRequest = this.api.get<KeysetPage<User>>('/iam/users', {
      limit: 50,
      cursor: this.responsibleLookupCursor || undefined,
      search: this.responsibleLookupQuery || undefined,
      state: 'A'
    }).subscribe({
      next: page => {
        if (this.destroyed || requestId !== this.responsibleLookupRequestId) return;
        const selectedId = this.isEditModalOpen() ? this.editForm.responsibleUserId : this.createForm.responsibleUserId;
        this.responsibleUsers.set(this.mergeUserResults(reset ? [] : this.responsibleUsers(), page.items || [], selectedId == null ? [] : [selectedId]));
        this.responsibleLookupCursor = page.nextCursor;
        this.responsibleLookupHasMore.set(page.hasMore);
        this.responsibleLookupLoading.set(false);
      },
      error: () => {
        if (this.destroyed || requestId !== this.responsibleLookupRequestId) return;
        this.responsibleLookupLoading.set(false);
        this.responsibleLookupError.set(true);
      }
    });
  }

  private loadObserverUsers(reset: boolean) {
    this.observerLastReset = reset;
    if (reset) this.observerLookupCursor = null;
    const requestId = ++this.observerLookupRequestId;
    this.observerLookupRequest?.unsubscribe();
    this.observerLookupLoading.set(true);
    this.observerLookupError.set(false);
    this.observerLookupRequest = this.api.get<KeysetPage<User>>('/iam/users', {
      limit: 50,
      cursor: this.observerLookupCursor || undefined,
      search: this.observerLookupQuery || undefined,
      state: 'A'
    }).subscribe({
      next: page => {
        if (this.destroyed || requestId !== this.observerLookupRequestId) return;
        const selectedIds = this.isEditModalOpen() ? this.editForm.observerUserIds : this.createForm.observerUserIds;
        this.observerUsers.set(this.mergeUserResults(reset ? [] : this.observerUsers(), page.items || [], selectedIds));
        this.observerLookupCursor = page.nextCursor;
        this.observerLookupHasMore.set(page.hasMore);
        this.observerLookupLoading.set(false);
      },
      error: () => {
        if (this.destroyed || requestId !== this.observerLookupRequestId) return;
        this.observerLookupLoading.set(false);
        this.observerLookupError.set(true);
      }
    });
  }

  private mergeOptions(existing: SelectOption[], incoming: SelectOption[]): SelectOption[] {
    const merged = new Map<string, SelectOption>();
    [...existing, ...incoming].forEach(option => merged.set(String(option.id), option));
    return [...merged.values()];
  }

  private mergeUserResults(existing: User[], incoming: User[], selectedIds: number[]): User[] {
    incoming.forEach(user => this.retainedUsers.set(user.id, user));
    const selected = selectedIds.map(id => this.retainedUsers.get(id)).filter((user): user is User => !!user);
    const merged = new Map<number, User>();
    [...existing, ...incoming, ...selected].forEach(user => merged.set(user.id, user));
    return [...merged.values()];
  }

  private retainTaskMember(member: TaskMember) {
    const existing = this.retainedUsers.get(member.userId);
    this.retainedUsers.set(member.userId, {
      id: member.userId,
      name: member.userName,
      login: member.userLogin,
      email: member.userEmail || existing?.email || '',
      state: 'A',
      language: existing?.language || 'ru',
      timezone: existing?.timezone || 'UTC',
      attributes: existing?.attributes || {},
      is2faEnabled: existing?.is2faEnabled || false,
      forcePasswordChange: existing?.forcePasswordChange || false,
      createdAt: existing?.createdAt || '',
      modifiedAt: existing?.modifiedAt || ''
    });
  }

  loadTaskCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'TASK' }).subscribe({
      next: res => this.taskCustomFields.set(res || []),
      error: () => {}
    });
  }

  loadTasks(reset: boolean = false) {
    clearTimeout(this.taskSearchTimer);
    const page = reset ? 1 : this.currentPage;
    const cursor = reset ? null : (this.taskPageCursors[page - 1] ?? null);
    this.requestTaskPage(page, cursor, reset);
  }

  onTaskSearchChange(query: string) {
    this.searchQuery = query;
    clearTimeout(this.taskSearchTimer);
    this.cancelListRequestForFilterChange();
    this.taskSearchTimer = setTimeout(() => this.loadTasks(true), 350);
  }

  applyTaskSearchImmediately() {
    clearTimeout(this.taskSearchTimer);
    this.loadTasks(true);
  }

  private cancelListRequestForFilterChange() {
    this.listRequestId++;
    this.listRequest?.unsubscribe();
    this.isLoading.set(true);
    this.listLoadError.set(false);
  }

  private requestTaskPage(targetPage: number, cursor: string | null, reset: boolean) {
    this.lastListAttempt = { page: targetPage, cursor, reset };

    let statusIdParam: number | undefined = undefined;
    let hideTerminalParam: boolean | undefined = undefined;

    if (this.statusFilterMode === 'active') {
      hideTerminalParam = true;
    } else if (this.statusFilterMode === 'all') {
      hideTerminalParam = false;
    } else if (typeof this.statusFilterMode === 'number') {
      statusIdParam = this.statusFilterMode;
    }

    let assignedUserIdParam: number | undefined = undefined;
    let reporterIdParam: number | undefined = undefined;
    let overdueParam: boolean | undefined = undefined;

    const currentUserId = this.authService?.currentUser()?.id;
    if (this.activePreset === 'my' && currentUserId) {
      assignedUserIdParam = currentUserId;
    } else if (this.activePreset === 'reported' && currentUserId) {
      reporterIdParam = currentUserId;
    } else if (this.activePreset === 'overdue') {
      overdueParam = true;
    }

    const requestId = ++this.listRequestId;
    this.listRequest?.unsubscribe();
    this.isLoading.set(true);
    this.listLoadError.set(false);
    this.listRequest = this.api.get<KeysetPage<Task>>('/tasks', {
      limit: 50,
      cursor: cursor || undefined,
      search: this.searchQuery || undefined,
      priority: this.selectedPriority || undefined,
      project_id: this.selectedProjectId || undefined,
      status_id: statusIdParam,
      hide_terminal: hideTerminalParam,
      assigned_user_id: assignedUserIdParam,
      reporter_id: reporterIdParam,
      overdue: overdueParam
    }).subscribe({
      next: res => {
        if (this.destroyed || requestId !== this.listRequestId) return;
        this.isLoading.set(false);
        if (reset) this.taskPageCursors = [null];
        this.taskPageCursors[targetPage - 1] = cursor;
        this.currentPage = targetPage;
        this.tasks.set(res.items || []);
        this.nextCursor = res.nextCursor;
        this.hasMore.set(res.hasMore);
      },
      error: () => {
        if (this.destroyed || requestId !== this.listRequestId) return;
        this.isLoading.set(false);
        this.listLoadError.set(true);
      }
    });
  }

  retryTaskList() {
    const attempt = this.lastListAttempt;
    if (!attempt || this.isLoading()) return;
    this.requestTaskPage(attempt.page, attempt.cursor, attempt.reset);
  }

  goToTaskPage(page: number) {
    if (this.isLoading() || this.listLoadError() || page === this.currentPage || page < 1 || Math.abs(page - this.currentPage) !== 1) return;
    let cursor: string | null;
    if (page > this.currentPage) {
      if (!this.hasMore() || !this.nextCursor) return;
      cursor = this.nextCursor;
    } else if (this.taskPageCursors[page - 1] === undefined) {
      return;
    } else {
      cursor = this.taskPageCursors[page - 1];
    }
    this.requestTaskPage(page, cursor, false);
  }

  paginatedTasks(): Task[] {
    return this.tasks();
  }

  hasActiveFilters(): boolean {
    return !!this.searchQuery || !!this.selectedPriority || this.selectedProjectId !== null || this.statusFilterMode !== 'active' || this.activePreset !== 'all';
  }

  clearSearch() {
    clearTimeout(this.taskSearchTimer);
    this.cancelListRequestForFilterChange();
    this.searchQuery = '';
    this.loadTasks(true);
  }

  setPreset(preset: 'all' | 'my' | 'reported' | 'overdue') {
    if (this.activePreset === preset) return;
    this.activePreset = preset;
    this.loadTasks(true);
  }

  updatePriority(taskId: number, newPriority: string) {
    if (!safeNumericRecordId(taskId) || !newPriority) return;
    this.api.patch(`/tasks/${taskId}`, { priority: newPriority }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.priority_updated'));
        this.tasks.update(list => list.map(t => t.id === taskId ? { ...t, priority: newPriority } : t));
        if (this.selectedTask()?.id === taskId) {
          this.selectedTask.update(t => t ? { ...t, priority: newPriority } : null);
        }
      },
      error: err => {
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_izmenit_prioritet'));
      }
    });
  }

  setStatusFilterMode(mode: 'active' | 'all' | number) {
    this.statusFilterMode = mode;
    this.loadTasks(true);
  }

  resetFilters() {
    clearTimeout(this.taskSearchTimer);
    this.cancelListRequestForFilterChange();
    this.searchQuery = '';
    this.selectedPriority = '';
    this.selectedProjectId = null;
    this.statusFilterMode = 'active';
    this.activePreset = 'all';
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
    if (!this.canUpdateTask()) return;
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
    if (!this.canUpdateTask()) return;
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
    if (!this.canUpdateTask()) {
      event.preventDefault();
      return;
    }
    this.draggedTask = task;
    if (event.dataTransfer) {
      event.dataTransfer.setData('text/plain', String(task.id));
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onHtml5DragOver(event: DragEvent) {
    if (!this.canUpdateTask()) return;
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
    if (!this.canUpdateTask()) return;
    const col = (event.currentTarget as HTMLElement);
    if (col) {
      col.classList.remove('drag-over');
    }
  }

  onHtml5Drop(event: DragEvent, targetStatusId: number) {
    if (!this.canUpdateTask()) return;
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

  executeStatusChange(task: Task, targetStatusId: number) {
    if (!safeNumericRecordId(task.id) || !safeNumericRecordId(targetStatusId)) return;
    // Optimistic UI update
    this.applyStatusToVisibleTasks(task.id, targetStatusId);
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
    if (!safeNumericRecordId(task.id)) return;
    if (this.isEditModalOpen()) return;
    if (this.routeRecordId() !== null && this.routeRecordId() !== String(task.id)) {
      this.recordRouter?.navigate(['/tasks/items', String(task.id)], { queryParamsHandling: 'preserve' });
      return;
    }
    this.cancelDetailRequests();
    this.detailContextId++;
    this.selectedTask.set(task);
    this.taskMembers.set([]);
    this.taskSubtasks.set([]);
    this.taskAncestors.set([]);
    this.taskFiles.set([]);
    this.comments.set([]);
    this.loadTaskFullDetails(task.id);
    this.loadComments(task.id);
  }

  onTaskContainerClick(event: MouseEvent, task: Task) {
    const target = event.target as HTMLElement | null;
    if (target?.closest('button, select, input, textarea, a, [role="button"], [role="option"]')) return;
    this.openTaskDetails(task);
  }

  detailRecordId(): string | null {
    return this.routeRecordId() ?? (this.selectedTask() ? String(this.selectedTask()!.id) : null);
  }

  loadTaskFullDetails(taskId: number | string) {
    const requestId = ++this.detailRequestId;
    this.detailRequest?.unsubscribe();
    this.detailLoading.set(true);
    this.detailLoadError.set(false);
    this.detailNotFound.set(false);
    this.detailRequest = this.api.get<TaskDetailResponse>(`/tasks/${taskId}`, undefined, { notifyError: false }).subscribe({
      next: res => {
        if (this.destroyed || requestId !== this.detailRequestId || this.detailRecordId() !== String(taskId)) return;
        this.detailLoading.set(false);
        if (recordResponseMatches(res?.task?.id, String(taskId))) {
          this.selectedTask.set(res.task);
          this.taskMembers.set(res.members || []);
          this.taskSubtasks.set(res.subtasks || []);
          this.taskAncestors.set(res.ancestors || []);
          this.taskFiles.set(res.files || []);
        } else {
          this.detailLoadError.set(true);
        }
      },
      error: error => {
        if (this.destroyed || requestId !== this.detailRequestId || this.detailRecordId() !== String(taskId)) return;
        this.detailLoading.set(false);
        this.detailLoadError.set(true);
        this.detailNotFound.set(error?.status === 404 || error?.status === 403);
      }
    });
  }

  retryTaskDetails() {
    const taskId = this.detailRecordId();
    if (taskId != null) this.loadTaskFullDetails(taskId);
  }

  closeTaskDetails(returnToList = true) {
    if (returnToList && this.routeRecordId() !== null) {
      this.recordRouter?.navigate(['/tasks/items'], { queryParamsHandling: 'preserve' });
      return;
    }
    this.clearTaskDetails();
  }

  private clearTaskDetails() {
    this.cancelDetailRequests();
    this.detailContextId++;
    this.selectedTask.set(null);
    this.taskMembers.set([]);
    this.taskSubtasks.set([]);
    this.taskAncestors.set([]);
    this.taskFiles.set([]);
    this.comments.set([]);
    this.detailLoading.set(false);
    this.detailLoadError.set(false);
    this.detailNotFound.set(false);
    this.commentsLoading.set(false);
    this.commentsLoadError.set(false);
  }

  private cancelDetailRequests() {
    this.detailRequestId++;
    this.commentsRequestId++;
    this.detailRequest?.unsubscribe();
    this.commentsRequest?.unsubscribe();
  }

  onTaskFileAttached(file: TaskFile) {
    const t = this.selectedTask();
    if (!t || !safeNumericRecordId(t.id)) return;
    const detailContextId = this.detailContextId;
    this.api.post(`/tasks/${t.id}/files`, { fileId: file.fileId }).subscribe({
      next: () => {
        if (this.destroyed || detailContextId !== this.detailContextId || this.selectedTask()?.id !== t.id) return;
        this.taskFiles.update(list => [...list, file]);
        this.toast.success(this.uiI18n.translate('tasks.file_attached', { name: file.fileName }));
      },
      error: err => {
        if (this.destroyed || detailContextId !== this.detailContextId || this.selectedTask()?.id !== t.id) return;
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_prikrepit_fayl'));
      }
    });
  }

  onTaskFileRemoved(file: TaskFile) {
    const t = this.selectedTask();
    if (!t || !safeNumericRecordId(t.id)) return;
    const detailContextId = this.detailContextId;
    this.api.delete(`/tasks/${t.id}/files/${file.fileId}`).subscribe({
      next: () => {
        if (this.destroyed || detailContextId !== this.detailContextId || this.selectedTask()?.id !== t.id) return;
        this.taskFiles.update(list => list.filter(f => f.fileId !== file.fileId));
        this.toast.success(this.uiI18n.translate('tasks.file_removed', { name: file.fileName }));
      },
      error: err => {
        if (this.destroyed || detailContextId !== this.detailContextId || this.selectedTask()?.id !== t.id) return;
        this.toast.error(err.error?.message || this.uiI18n.translate('files.ne_udalos_udalit_fayl'));
      }
    });
  }


  loadComments(taskId: number | string) {
    const requestId = ++this.commentsRequestId;
    this.commentsRequest?.unsubscribe();
    this.commentsLoading.set(true);
    this.commentsLoadError.set(false);
    this.commentsRequest = this.api.get<TaskComment[]>(`/tasks/${taskId}/comments`, undefined, { notifyError: false }).subscribe({
      next: res => {
        if (this.destroyed || requestId !== this.commentsRequestId || this.detailRecordId() !== String(taskId)) return;
        this.commentsLoading.set(false);
        this.comments.set((res || []).filter(comment => recordResponseMatches(comment.taskId, String(taskId))));
      },
      error: () => {
        if (this.destroyed || requestId !== this.commentsRequestId || this.detailRecordId() !== String(taskId)) return;
        this.commentsLoading.set(false);
        this.commentsLoadError.set(true);
      }
    });
  }

  retryComments() {
    const taskId = this.detailRecordId();
    if (taskId != null) this.loadComments(taskId);
  }

  canCommentTask(): boolean {
    return this.permService.canCreate('tasks.comments') &&
      (this.routeRecordId() === null || safeNumericRecordId(this.selectedTask()?.id));
  }

  submitComment() {
    const task = this.selectedTask();
    const text = this.commentDraft.trim();
    if (!task || !safeNumericRecordId(task.id) || !text || !this.canCommentTask() || this.isCommentSubmitting()) return;

    const requestId = ++this.commentPostRequestId;
    this.isCommentSubmitting.set(true);
    this.commentPostRequest = this.api.post(`/tasks/${task.id}/comments`, { textMarkdown: text }).subscribe({
      next: () => {
        if (this.destroyed || requestId !== this.commentPostRequestId) return;
        this.isCommentSubmitting.set(false);
        this.commentDrafts.delete(task.id);
        if (this.selectedTask()?.id === task.id) this.loadComments(task.id);
        this.toast.success(this.uiI18n.translate('tasks.kommentariy_dobavlen'));
      },
      error: err => {
        if (this.destroyed || requestId !== this.commentPostRequestId) return;
        this.isCommentSubmitting.set(false);
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.ne_udalos_otpravit_kommentariy'));
      }
    });
  }

  updateStatus(taskId: number, newStatusId: number) {
    if (!safeNumericRecordId(taskId) || !safeNumericRecordId(newStatusId)) return;
    this.api.post(`/tasks/${taskId}/status`, { statusId: newStatusId }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.status_zadachi_obnovlen'));
        this.applyStatusToVisibleTasks(taskId, newStatusId);
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
    this.createFormBaseline = JSON.stringify(this.createForm);
    this.isCreateModalOpen.set(true);
  }

  openAddSubtaskModal(parentTask: Task) {
    if (!safeNumericRecordId(parentTask.id)) return;
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
    const parentOption = { id: parentTask.id, label: `#${parentTask.id} ${parentTask.title}`, icon: 'task_alt' };
    this.retainedParentOptions.set(parentTask.id, parentOption);
    this.parentTaskOptions.set(this.mergeOptions(this.parentTaskOptions(), [parentOption]));
    this.createFormBaseline = JSON.stringify(this.createForm);
    this.isCreateModalOpen.set(true);
  }

  requestCloseCreate() {
    if (this.isSubmitting()) return;
    this.isCreateModalOpen.set(false);
  }

  submitCreateTask() {
    if (this.isSubmitting()) return;
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
      beginTime: toTaskInstant(this.createForm.beginTime),
      endTime: toTaskInstant(this.createForm.endTime),
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
    if (!safeNumericRecordId(task.id)) return;
    if (this.isSubmitting() || this.isEditModalOpen()) return;
    this.isEditSubmitted = false;
    this.isEditDiscardConfirmationOpen.set(false);
    this.editReturnTask = this.selectedTask()?.id === task.id ? this.selectedTask() : null;
    if (this.editReturnTask) this.closeTaskDetails(false);
    this.editTargetId = task.id;
    this.editingTask = null;
    this.editFormBaseline = '';
    this.editAssignmentBaseline = null;
    this.isEditModalOpen.set(true);
    this.loadEditDetails(task.id);
  }

  private loadEditDetails(taskId: number) {
    const requestId = ++this.editRequestId;
    this.editRequest?.unsubscribe();
    this.editLoading.set(true);
    this.editLoadError.set(false);
    this.editingTask = null;

    this.editRequest = this.api.get<TaskDetailResponse>(`/tasks/${taskId}`).subscribe({
      next: res => {
        if (this.destroyed || requestId !== this.editRequestId || this.editTargetId !== taskId || !this.isEditModalOpen()) return;
        if (!res?.task || res.task.id !== taskId || !Array.isArray(res.members)) {
          this.editLoading.set(false);
          this.editLoadError.set(true);
          return;
        }
        const freshTask = res.task;
        const obsIds = res.members
          .filter(m => (m.involveKind || m.involvementKind) === 'O')
          .map(m => m.userId);

        const respMember = res.members.find(m => (m.involveKind || m.involvementKind) === 'R');
        res.members.forEach(member => this.retainTaskMember(member));
        const parent = (res.ancestors || []).find(ancestor => ancestor.id === freshTask.parentTaskId);
        if (parent) {
          const option = { id: parent.id, label: `#${parent.id} ${parent.title}`, icon: 'task_alt' };
          this.retainedParentOptions.set(parent.id, option);
          this.parentTaskOptions.set(this.mergeOptions(this.parentTaskOptions(), [option]));
        }

        this.editingTask = freshTask;
        this.editForm = {
          title: freshTask.title,
          taskType: (freshTask.attributes && freshTask.attributes['task_type']) || 'task',
          descriptionMarkdown: freshTask.descriptionMarkdown || '',
          projectId: freshTask.projectId ?? null,
          priority: freshTask.priority || 'medium',
          responsibleUserId: respMember ? respMember.userId : null,
          parentTaskId: freshTask.parentTaskId ?? null,
          observerUserIds: obsIds,
          beginTime: toLocalDateTime(freshTask.beginTime),
          endTime: toLocalDateTime(freshTask.endTime),
          attributes: { ...(freshTask.attributes || {}) }
        };
        this.editFormBaseline = this.serializeEditForm();
        this.editAssignmentBaseline = {
          parentTaskId: this.editForm.parentTaskId,
          responsibleUserId: this.editForm.responsibleUserId,
          observerUserIds: [...this.editForm.observerUserIds]
        };
        this.responsibleUsers.set(this.mergeUserResults(this.responsibleUsers(), [], this.editForm.responsibleUserId == null ? [] : [this.editForm.responsibleUserId]));
        this.observerUsers.set(this.mergeUserResults(this.observerUsers(), [], this.editForm.observerUserIds));
        this.editLoading.set(false);
      },
      error: () => {
        if (this.destroyed || requestId !== this.editRequestId || this.editTargetId !== taskId || !this.isEditModalOpen()) return;
        this.editLoading.set(false);
        this.editLoadError.set(true);
      }
    });
  }

  private applyStatusToVisibleTasks(taskId: number, statusId: number) {
    const targetStatus = this.statuses().find(status => status.id === statusId);
    const leavesCurrentFilter =
      (this.statusFilterMode === 'active' && targetStatus?.isTerminal === true) ||
      (typeof this.statusFilterMode === 'number' && this.statusFilterMode !== statusId);
    this.tasks.update(list => leavesCurrentFilter
      ? list.filter(task => task.id !== taskId)
      : list.map(task => task.id === taskId ? { ...task, statusId } : task));
  }

  retryEditLoad() {
    if (this.editTargetId != null && !this.isSubmitting()) this.loadEditDetails(this.editTargetId);
  }

  requestCloseEdit() {
    if (this.isSubmitting()) return;
    if (this.editingTask && this.editFormBaseline !== this.serializeEditForm()) {
      this.isEditDiscardConfirmationOpen.set(true);
      return;
    }
    this.closeEditModal(true);
  }

  confirmDiscardEdit() {
    if (this.isSubmitting()) return;
    if (this.navigationDecision.pending) {
      this.isCreateModalOpen.set(false);
      this.closeEditModal(false);
      this.navigationDecision.settle(true);
      return;
    }
    this.isEditDiscardConfirmationOpen.set(false);
    this.closeEditModal(true);
  }

  cancelDiscardEdit() {
    this.isEditDiscardConfirmationOpen.set(false);
    this.navigationDecision.settle(false);
  }

  canLeaveRecordPage() {
    if (this.isSubmitting() || this.isCommentSubmitting()) return false;
    const dirtyEdit = this.isEditModalOpen() && this.editingTask && this.editFormBaseline !== this.serializeEditForm();
    const dirtyCreate = this.isCreateModalOpen() && this.createFormBaseline !== JSON.stringify(this.createForm);
    if (dirtyEdit || dirtyCreate || this.commentDraft.trim()) {
      return this.navigationDecision.request(
        () => this.isEditDiscardConfirmationOpen.set(true),
        () => this.isEditDiscardConfirmationOpen.set(false));
    }
    if (this.isEditModalOpen()) this.closeEditModal(false);
    this.isCreateModalOpen.set(false);
    return true;
  }

  private closeEditModal(returnToDetails: boolean) {
    const returnTask = returnToDetails ? this.editReturnTask : null;
    this.editRequestId++;
    this.editRequest?.unsubscribe();
    this.isEditModalOpen.set(false);
    this.isEditDiscardConfirmationOpen.set(false);
    this.editLoading.set(false);
    this.editLoadError.set(false);
    this.editingTask = null;
    this.editTargetId = null;
    this.editReturnTask = null;
    this.editFormBaseline = '';
    this.editAssignmentBaseline = null;
    if (returnTask) this.openTaskDetails(returnTask);
  }

  private serializeEditForm(): string {
    return JSON.stringify(this.editForm);
  }

  private sameIdSet(left: number[], right: number[]): boolean {
    if (left.length !== right.length) return false;
    const rightIds = new Set(right);
    return left.every(id => rightIds.has(id));
  }

  submitEditTask() {
    if (!this.editingTask || this.isSubmitting() || this.editLoading() || this.editLoadError()) return;
    this.isEditSubmitted = true;
    if (!this.editForm.title.trim()) {
      this.toast.warning(this.uiI18n.translate('tasks.nazvanie_zadachi_obyazatelno'));
      return;
    }

    const attrs = { ...this.editForm.attributes, task_type: this.editForm.taskType };

    const payload: Record<string, unknown> = {
      title: this.editForm.title.trim(),
      descriptionMarkdown: this.editForm.descriptionMarkdown?.trim() || '',
      projectId: this.editForm.projectId ? Number(this.editForm.projectId) : null,
      priority: this.editForm.priority || 'medium',
      beginTime: toTaskInstant(this.editForm.beginTime, this.editingTask.beginTime),
      endTime: toTaskInstant(this.editForm.endTime, this.editingTask.endTime),
      attributes: attrs
    };
    const currentAssignments = {
      parentTaskId: this.editForm.parentTaskId == null ? null : Number(this.editForm.parentTaskId),
      responsibleUserId: this.editForm.responsibleUserId == null ? null : Number(this.editForm.responsibleUserId),
      observerUserIds: [...this.editForm.observerUserIds]
    };
    if (!this.editAssignmentBaseline || currentAssignments.parentTaskId !== this.editAssignmentBaseline.parentTaskId) {
      payload['parentTaskId'] = currentAssignments.parentTaskId;
    }
    if (!this.editAssignmentBaseline || currentAssignments.responsibleUserId !== this.editAssignmentBaseline.responsibleUserId) {
      payload['responsibleUserId'] = currentAssignments.responsibleUserId;
    }
    if (!this.editAssignmentBaseline || !this.sameIdSet(currentAssignments.observerUserIds, this.editAssignmentBaseline.observerUserIds)) {
      payload['observerUserIds'] = currentAssignments.observerUserIds;
    }

    const editedTask = this.editingTask;
    const returnTask = this.editReturnTask;
    this.isSubmitting.set(true);
    this.editSaveRequest = this.api.patch(`/tasks/${editedTask.id}`, payload).subscribe({
      next: () => {
        if (this.destroyed || this.editingTask?.id !== editedTask.id) return;
        this.isSubmitting.set(false);
        this.closeEditModal(false);
        this.toast.success(this.uiI18n.translate('tasks.zadacha_uspeshno_obnovlena'));
        this.loadTasks(true);
        if (returnTask) this.openTaskDetails({ ...returnTask, id: editedTask.id });
      },
      error: err => {
        if (this.destroyed || this.editingTask?.id !== editedTask.id) return;
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_pri_obnovlenii_zadachi'));
      }
    });
  }

  // =========================================================================
  // Dictionaries Management
  // =========================================================================
  // =========================================================================
  // Dictionaries Management (Delegated to TaskDictionariesModalComponent)
  // =========================================================================
  openSettingsModal() {
    this.isSettingsModalOpen.set(true);
  }

  handleCreateType(event: { code: string; name: string; icon: string; color: string }) {
    this.api.post('/tasks/types', {
      code: event.code,
      name: event.name,
      icon: event.icon,
      color: event.color,
      orderNo: (this.taskTypes().length + 1) * 10
    }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.tip_zadachi_dobavlen'));
        this.loadTypes();
      },
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_dobavleniya_tipa'))
    });
  }

  handleCreateStatus(event: { name: string; color: string; isTerminal: boolean }) {
    this.api.post('/tasks/statuses', {
      name: event.name,
      color: event.color,
      orderNo: (this.statuses().length + 1) * 10,
      isTerminal: event.isTerminal
    }).subscribe({
      next: () => {
        this.toast.success(this.uiI18n.translate('tasks.status_zadachi_dobavlen'));
        this.loadStatuses();
      },
      error: err => this.toast.error(err.error?.message || this.uiI18n.translate('tasks.oshibka_dobavleniya_statusa'))
    });
  }

  handleDeleteDictionaryItem(target: { kind: 'type' | 'status'; id: number; name: string }) {
    const endpoint = target.kind === 'type' ? `/tasks/types/${target.id}` : `/tasks/statuses/${target.id}`;
    this.api.delete(endpoint).subscribe({
      next: () => {
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

  handleReorderTypes(list: TaskType[]) {
    this.taskTypes.set(list);
    this.persistTypeOrder(list);
  }

  handleReorderStatuses(list: TaskStatus[]) {
    this.statuses.set(list);
    this.persistStatusOrder(list);
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

  getDeadlineInfo(endTime: string | null | undefined, statusId: number): {
    state: 'none' | 'overdue' | 'today' | 'tomorrow' | 'upcoming';
    label: string;
    detail?: string;
  } {
    if (!endTime) return { state: 'none', label: '—' };
    const s = this.statuses().find(x => x.id === statusId);
    if (s && s.isTerminal) {
      const d = new Date(endTime);
      const day = String(d.getDate()).padStart(2, '0');
      const month = String(d.getMonth() + 1).padStart(2, '0');
      return { state: 'upcoming', label: `${day}.${month}.${d.getFullYear()}` };
    }

    const targetDate = new Date(endTime);
    const now = new Date();
    const diffMs = targetDate.getTime() - now.getTime();

    if (diffMs < 0) {
      const overdueDays = Math.max(1, Math.floor(Math.abs(diffMs) / (1000 * 60 * 60 * 24)));
      const day = String(targetDate.getDate()).padStart(2, '0');
      const month = String(targetDate.getMonth() + 1).padStart(2, '0');
      return {
        state: 'overdue',
        label: this.uiI18n.translate('tasks.deadline_overdue_days', { days: overdueDays }),
        detail: `${day}.${month}.${targetDate.getFullYear()}`
      };
    }

    const isToday = targetDate.getFullYear() === now.getFullYear() &&
                    targetDate.getMonth() === now.getMonth() &&
                    targetDate.getDate() === now.getDate();
    if (isToday) {
      const hours = String(targetDate.getHours()).padStart(2, '0');
      const mins = String(targetDate.getMinutes()).padStart(2, '0');
      return {
        state: 'today',
        label: `${this.uiI18n.translate('tasks.deadline_today')}, ${hours}:${mins}`
      };
    }

    const tomorrow = new Date(now);
    tomorrow.setDate(now.getDate() + 1);
    const isTomorrow = targetDate.getFullYear() === tomorrow.getFullYear() &&
                      targetDate.getMonth() === tomorrow.getMonth() &&
                      targetDate.getDate() === tomorrow.getDate();
    if (isTomorrow) {
      return {
        state: 'tomorrow',
        label: this.uiI18n.translate('tasks.deadline_tomorrow')
      };
    }

    const day = String(targetDate.getDate()).padStart(2, '0');
    const month = String(targetDate.getMonth() + 1).padStart(2, '0');
    return {
      state: 'upcoming',
      label: `${day}.${month}.${targetDate.getFullYear()}`
    };
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
    const fields = this.taskCustomFields();
    return Object.entries(attrs)
      .filter(([k]) => k !== 'task_type')
      .map(([k, v]) => {
        const field = fields.find(f => f.code === k);
        const keyLabel = field ? field.name : k;
        let valueStr = String(v ?? '');
        if (field?.fieldType === 'boolean') {
          valueStr = v === true || v === 'true'
            ? this.uiI18n.translate('common.yes')
            : this.uiI18n.translate('common.no');
        } else if (field?.fieldType === 'user_ref') {
          const user = this.responsibleUsers().find(u => u.id === Number(v))
            || this.observerUsers().find(u => u.id === Number(v));
          if (user) {
            valueStr = user.name || user.login;
          }
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
