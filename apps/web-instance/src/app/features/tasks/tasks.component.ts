import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../shared/ui/ui-custom-fields.component';
import { UiSearchableSelectComponent, SelectOption } from '../../shared/ui/ui-searchable-select.component';
import { Task, Project, TaskStatus, TaskType, TaskComment, TaskMember, TaskDetailResponse } from '../../core/models/task.models';
import { CustomField } from '../../core/models/custom-field.models';
import { User } from '../../core/models/auth.models';
import { KeysetPage } from '../../core/models/common.models';

@Component({
  selector: 'app-tasks',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiModalComponent,
    UiCustomFieldsComponent,
    UiSearchableSelectComponent
  ],
  template: `
    <div class="tasks-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Задачи</h1>
          <span class="task-count">{{ tasks().length }}</span>

          <!-- View Mode Switcher -->
          <div class="view-switcher">
            <button
              type="button"
              class="view-btn"
              [class.active]="viewMode === 'table'"
              (click)="viewMode = 'table'"
              title="Табличный вид"
            >
              <span class="material-symbols-outlined">table_rows</span>
              <span>Таблица</span>
            </button>
            <button
              type="button"
              class="view-btn"
              [class.active]="viewMode === 'kanban'"
              (click)="viewMode = 'kanban'"
              title="Канбан-доска"
            >
              <span class="material-symbols-outlined">view_kanban</span>
              <span>Канбан</span>
            </button>
          </div>
        </div>

        <div class="header-right">
          <!-- Dictionary Settings Button -->
          <button
            type="button"
            class="settings-btn"
            title="Управление статусами и типами задач"
            (click)="openSettingsModal()"
          >
            <span class="material-symbols-outlined">tune</span>
            <span>Справочники</span>
          </button>

          <!-- Create Task Button -->
          <ui-button
            *ngIf="canCreateTask()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateTaskModal()"
          >
            Новая задача
          </ui-button>
        </div>
      </div>

      <!-- Linear-Style Toolbar -->
      <div class="toolbar">
        <div class="search-field">
          <span class="material-symbols-outlined search-icon">search</span>
          <input
            type="text"
            class="search-input"
            placeholder="Поиск по названию или описанию..."
            [(ngModel)]="searchQuery"
            (keyup.enter)="loadTasks(true)"
          />
          <button *ngIf="searchQuery" type="button" class="clear-btn" (click)="clearSearch()">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>

        <div class="toolbar-controls">
          <!-- Status Quick Switcher (in Table view) -->
          <div class="status-tabs" *ngIf="viewMode === 'table'">
            <button
              type="button"
              class="status-tab"
              [class.active]="selectedStatusId === null"
              (click)="setStatusFilter(null)"
            >
              Все
            </button>
            <button
              *ngFor="let s of statuses()"
              type="button"
              class="status-tab"
              [class.active]="selectedStatusId === s.id"
              (click)="setStatusFilter(s.id)"
            >
              <span class="status-tab-dot" [style.background-color]="s.color || 'var(--primary)'"></span>
              {{ s.name }}
            </button>
          </div>

          <!-- Project Filter -->
          <select
            class="clean-select"
            [(ngModel)]="selectedProjectId"
            (change)="loadTasks(true)"
          >
            <option [ngValue]="null">Все проекты</option>
            <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
          </select>

          <!-- Priority Filter -->
          <select
            class="clean-select"
            [(ngModel)]="selectedPriority"
            (change)="loadTasks(true)"
          >
            <option value="">Все приоритеты</option>
            <option value="critical">Критический</option>
            <option value="high">Высокий</option>
            <option value="medium">Средний</option>
            <option value="low">Низкий</option>
          </select>

          <button
            *ngIf="hasActiveFilters()"
            type="button"
            class="reset-filters-btn"
            (click)="resetFilters()"
            title="Сбросить все фильтры"
          >
            <span class="material-symbols-outlined">filter_alt_off</span>
          </button>
        </div>
      </div>

      <!-- ======================================================================= -->
      <!-- VIEW 1: TABLE VIEW                                                      -->
      <!-- ======================================================================= -->
      <div class="table-card" *ngIf="viewMode === 'table'">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 60px;">ID</th>
                <th style="width: 120px;">Тип</th>
                <th>Задача</th>
                <th>Проект</th>
                <th>Приоритет</th>
                <th>Статус</th>
                <th>Срок</th>
                <th class="text-right" style="width: 110px;">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr
                *ngFor="let t of tasks()"
                class="task-row"
                [class.row-overdue]="isOverdue(t.endTime, t.statusId)"
                (click)="openTaskDetails(t)"
              >
                <td class="tabular-nums font-mono" [class.text-danger]="isOverdue(t.endTime, t.statusId)" [class.text-muted]="!isOverdue(t.endTime, t.statusId)">
                  #{{ t.id }}
                </td>
                <td>
                  <span class="task-type-badge" [style.color]="getTypeColor(t)" [style.background-color]="getTypeBg(t)">
                    <span class="material-symbols-outlined type-icon">{{ getTypeIcon(t) }}</span>
                    {{ getTypeLabel(t) }}
                  </span>
                </td>
                <td>
                  <div class="task-title-cell">
                    <span class="task-title" [class.title-overdue]="isOverdue(t.endTime, t.statusId)">
                      {{ t.title }}
                    </span>
                    <span *ngIf="isOverdue(t.endTime, t.statusId)" class="overdue-tag">
                      Просрочено
                    </span>
                    <span *ngIf="t.parentTaskId" class="parent-chip font-mono" title="Родительская задача">
                      ↳ подзадача #{{ t.parentTaskId }}
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
                      title="Нажмите для смены статуса"
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
                    [title]="'Срок: ' + (t.endTime | date:'dd.MM.yyyy HH:mm')"
                  >
                    <span class="material-symbols-outlined ico">
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
                      title="Редактировать задачу"
                      (click)="openEditModal(t)"
                    >
                      <span class="material-symbols-outlined">edit</span>
                    </button>
                    <button
                      type="button"
                      class="icon-ghost-btn"
                      title="Просмотреть детали"
                      (click)="openTaskDetails(t)"
                    >
                      <span class="material-symbols-outlined">visibility</span>
                    </button>
                  </div>
                </td>
              </tr>
              <tr *ngIf="tasks().length === 0 && !isLoading()">
                <td colspan="8" class="empty-state-cell">
                  <span class="material-symbols-outlined icon">task</span>
                  <p>Задачи не найдены</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="load-more-bar" *ngIf="hasMore()">
          <ui-button variant="secondary" size="md" [loading]="isLoading()" (onClick)="loadTasks(false)">
            Загрузить ещё
          </ui-button>
        </div>
      </div>

      <!-- ======================================================================= -->
      <!-- VIEW 2: KANBAN BOARD VIEW                                               -->
      <!-- ======================================================================= -->
      <div class="kanban-board" *ngIf="viewMode === 'kanban'">
        <div
          *ngFor="let status of statuses()"
          class="kanban-column"
          [style.border-top-color]="status.color || 'var(--primary)'"
        >
          <!-- Column Header -->
          <div class="column-header">
            <div class="column-title-group">
              <span class="status-dot" [style.background-color]="status.color || 'var(--primary)'"></span>
              <h3 class="column-title">{{ status.name }}</h3>
            </div>
            <span class="column-badge">{{ getTasksByStatus(status.id).length }}</span>
          </div>

          <!-- Column Tasks -->
          <div class="column-tasks">
            <div
              *ngFor="let task of getTasksByStatus(status.id)"
              class="kanban-card"
              [class.card-overdue]="isOverdue(task.endTime, task.statusId)"
              (click)="openTaskDetails(task)"
            >
              <!-- Card Top -->
              <div class="card-top-row">
                <span class="task-type-badge-mini" [style.color]="getTypeColor(task)">
                  <span class="material-symbols-outlined mini-ico">{{ getTypeIcon(task) }}</span>
                  <span class="task-id font-mono">#{{ task.id }}</span>
                </span>
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
                <span *ngIf="!task.endTime" class="text-muted text-xs">Без срока</span>

                <!-- Quick Move Buttons -->
                <div class="kanban-move-actions" *ngIf="canUpdateTask()">
                  <button
                    type="button"
                    class="move-btn"
                    title="Переместить назад"
                    [disabled]="isFirstStatus(status.id)"
                    (click)="moveTaskStatus(task, -1)"
                  >
                    <span class="material-symbols-outlined">chevron_left</span>
                  </button>
                  <button
                    type="button"
                    class="move-btn"
                    title="Переместить вперед"
                    [disabled]="isLastStatus(status.id)"
                    (click)="moveTaskStatus(task, 1)"
                  >
                    <span class="material-symbols-outlined">chevron_right</span>
                  </button>
                </div>
              </div>
            </div>

            <div *ngIf="getTasksByStatus(status.id).length === 0" class="kanban-empty-col">
              Нет задач
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
      [title]="'Задача #' + selectedTask()?.id"
      size="lg"
      (close)="selectedTask.set(null)"
    >
      <div body class="task-details-view" *ngIf="selectedTask() as t">
        <!-- Ancestor Breadcrumbs Trail -->
        <div class="ancestor-trail" *ngIf="taskAncestors().length > 0">
          <span class="trail-label">Иерархия:</span>
          <ng-container *ngFor="let anc of taskAncestors()">
            <span class="anc-link" (click)="openTaskDetails(anc)">
              #{{ anc.id }} {{ anc.title }}
            </span>
            <span class="anc-sep">›</span>
          </ng-container>
          <span class="anc-current">#{{ t.id }} {{ t.title }}</span>
        </div>

        <!-- Overdue Notice Banner -->
        <div class="overdue-banner" *ngIf="isOverdue(t.endTime, t.statusId)">
          <span class="material-symbols-outlined">error</span>
          <span>Внимание: Срок сдачи этой задачи истёк {{ t.endTime | date:'dd.MM.yyyy HH:mm' }}</span>
        </div>

        <div class="details-2col-layout">
          <!-- Left Column (Main) -->
          <div class="details-main-col">
            <div class="detail-header-group">
              <h2 class="detail-main-title">{{ t.title }}</h2>
            </div>

            <!-- Description -->
            <div class="detail-section">
              <h4 class="section-label">Описание</h4>
              <div class="description-box" *ngIf="t.descriptionMarkdown">
                {{ t.descriptionMarkdown }}
              </div>
              <div class="description-box empty-desc text-muted" *ngIf="!t.descriptionMarkdown">
                Описание отсутствует. Нажмите «Редактировать», чтобы добавить детали.
              </div>
            </div>

            <!-- Subtasks Section -->
            <div class="detail-section">
              <div class="section-header-between">
                <h4 class="section-label">Подзадачи ({{ taskSubtasks().length }})</h4>
                <button
                  *ngIf="canCreateTask()"
                  type="button"
                  class="add-subtask-btn"
                  (click)="openAddSubtaskModal(t)"
                >
                  <span class="material-symbols-outlined">add</span>
                  Добавить подзадачу
                </button>
              </div>

              <div class="subtasks-list" *ngIf="taskSubtasks().length > 0">
                <div
                  *ngFor="let sub of taskSubtasks()"
                  class="subtask-row"
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
                </div>
              </div>
              <div *ngIf="taskSubtasks().length === 0" class="no-subtasks-hint text-muted">
                У этой задачи пока нет подзадач.
              </div>
            </div>

            <!-- Comments Feed -->
            <div class="detail-section comments-section">
              <h4 class="section-label">Комментарии ({{ comments().length }})</h4>
              <div class="comments-feed">
                <div *ngFor="let c of comments()" class="comment-card">
                  <div class="comment-top">
                    <span class="comment-author">{{ c.userName }} <span class="text-muted">&#64;{{ c.userLogin }}</span></span>
                    <span class="comment-time tabular-nums">{{ c.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
                  </div>
                  <div class="comment-text">{{ c.textMarkdown || c.commentMarkdown }}</div>
                </div>
                <div *ngIf="comments().length === 0" class="no-comments-hint text-muted">
                  Комментариев пока нет.
                </div>
              </div>

              <div class="add-comment-box">
                <textarea
                  class="comment-textarea"
                  rows="2"
                  placeholder="Написать комментарий к задаче... (Ctrl + Enter для отправки)"
                  [(ngModel)]="newCommentText"
                  (keydown.ctrl.enter)="submitComment()"
                ></textarea>
                <ui-button variant="primary" size="sm" icon="send" (onClick)="submitComment()">
                  Отправить
                </ui-button>
              </div>
            </div>
          </div>

          <!-- Right Column (Properties Sidebar) -->
          <div class="details-side-col">
            <div class="side-card">
              <div class="side-prop-row">
                <span class="prop-k">Статус</span>
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
                <span class="prop-k">Тип задачи</span>
                <div class="prop-v">
                  <span class="task-type-badge" [style.color]="getTypeColor(t)" [style.background-color]="getTypeBg(t)">
                    <span class="material-symbols-outlined type-icon">{{ getTypeIcon(t) }}</span>
                    {{ getTypeLabel(t) }}
                  </span>
                </div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">Приоритет</span>
                <div class="prop-v">
                  <span class="priority-pill" [attr.data-priority]="t.priority">
                    {{ getPriorityLabel(t.priority) }}
                  </span>
                </div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">Проект</span>
                <div class="prop-v">{{ getProjectName(t.projectId) || 'Без проекта' }}</div>
              </div>

              <div class="side-prop-row" *ngIf="t.parentTaskId">
                <span class="prop-k">Родитель</span>
                <div class="prop-v font-mono text-xs">#{{ t.parentTaskId }}</div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">Дедлайн</span>
                <div class="prop-v" [class.text-danger]="isOverdue(t.endTime, t.statusId)">
                  {{ t.endTime ? (t.endTime | date:'dd.MM.yyyy HH:mm') : 'Не установлен' }}
                </div>
              </div>

              <div class="side-prop-row" *ngIf="t.beginTime">
                <span class="prop-k">Дата начала</span>
                <div class="prop-v">{{ t.beginTime | date:'dd.MM.yyyy HH:mm' }}</div>
              </div>

              <div class="side-prop-row">
                <span class="prop-k">Создана</span>
                <div class="prop-v text-muted">{{ t.createdAt | date:'dd.MM.yyyy HH:mm' }}</div>
              </div>
            </div>

            <!-- Members Card -->
            <div class="side-card" *ngIf="taskMembers().length > 0">
              <h5 class="side-card-title">Участники</h5>
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
              <h5 class="side-card-title">Доп. поля</h5>
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
              Редактировать задачу
            </button>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="selectedTask.set(null)">Закрыть</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Create Task Modal (Enhanced UI/UX)                                     -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      [title]="createForm.parentTaskId ? 'Создание подзадачи к #' + createForm.parentTaskId : 'Создание новой задачи'"
      size="lg"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <!-- Title Input (Required) -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Название задачи</label>
            <span class="req-tag">Обязательное поле</span>
          </div>
          <input
            type="text"
            class="clean-input title-input"
            [class.input-error]="isCreateSubmitted && !createForm.title.trim()"
            [(ngModel)]="createForm.title"
            placeholder="Краткая и ясная формулировка задачи..."
          />
          <span class="error-msg" *ngIf="isCreateSubmitted && !createForm.title.trim()">
            Пожалуйста, укажите название задачи
          </span>
        </div>

        <!-- Visual Type Selector Chips -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Тип задачи</label>
          </div>
          <div class="type-chips-selector">
            <button
              *ngFor="let ty of taskTypes()"
              type="button"
              class="type-chip-btn"
              [class.active]="createForm.taskType === ty.code"
              (click)="createForm.taskType = ty.code"
              [style.--chip-color]="ty.color"
            >
              <span class="material-symbols-outlined">{{ ty.icon }}</span>
              <span>{{ ty.name }}</span>
            </button>
          </div>
        </div>

        <!-- Visual Priority Selector Pills -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Приоритет</label>
          </div>
          <div class="priority-chips-selector">
            <button
              type="button"
              class="prio-chip-btn prio-low"
              [class.active]="createForm.priority === 'low'"
              (click)="createForm.priority = 'low'"
            >
              Низкий
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-medium"
              [class.active]="createForm.priority === 'medium'"
              (click)="createForm.priority = 'medium'"
            >
              Средний
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-high"
              [class.active]="createForm.priority === 'high'"
              (click)="createForm.priority = 'high'"
            >
              Высокий
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-critical"
              [class.active]="createForm.priority === 'critical'"
              (click)="createForm.priority = 'critical'"
            >
              Критический
            </button>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Project Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Проект</label>
            </div>
            <select class="clean-input" [(ngModel)]="createForm.projectId">
              <option [ngValue]="null">Без проекта</option>
              <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
            </select>
          </div>

          <!-- Parent Task (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Родительская задача</label>
            </div>
            <ui-searchable-select
              [options]="taskSelectOptions()"
              [selectedId]="createForm.parentTaskId"
              (selectedIdChange)="createForm.parentTaskId = $event"
              placeholder="Без родителя (корневая задача)"
              searchPlaceholder="Поиск задачи по ID или названию..."
              emptyLabel="Без родителя (корневая задача)"
            ></ui-searchable-select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Ответственный сотрудник (I-T1)</label>
            </div>
            <ui-searchable-select
              [options]="userSelectOptions()"
              [selectedId]="createForm.responsibleUserId"
              (selectedIdChange)="createForm.responsibleUserId = $event"
              placeholder="Выберите ответственного..."
              searchPlaceholder="Поиск сотрудника по имени или логину..."
              emptyLabel="Не назначен"
            ></ui-searchable-select>
          </div>

          <!-- Deadlines: End Date / Deadline -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Срок сдачи (Дедлайн)</label>
            </div>
            <input type="datetime-local" class="clean-input font-mono" [(ngModel)]="createForm.endTime" />
          </div>
        </div>

        <!-- Observers Multi-Select -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Наблюдатели (получают уведомления)</label>
          </div>
          <div class="users-chips-box">
            <label
              *ngFor="let u of usersList()"
              class="user-chip-toggle"
              [class.active]="isObserverSelectedInCreate(u.id)"
            >
              <input
                type="checkbox"
                [checked]="isObserverSelectedInCreate(u.id)"
                (change)="toggleObserverInCreate(u.id)"
              />
              <span>{{ u.name }}</span>
            </label>
          </div>
        </div>

        <!-- Description -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Подробное описание (Markdown)</label>
          </div>
          <textarea
            class="clean-input clean-textarea"
            rows="3"
            [(ngModel)]="createForm.descriptionMarkdown"
            placeholder="Контекст, требования, ссылки и критерии готовности задачи..."
          ></textarea>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">Дополнительные настраиваемые поля</h4>
          <ui-custom-fields
            [fields]="taskCustomFields()"
            [(values)]="createForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateTask()">Создать задачу</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Edit Task Modal (Enhanced UI/UX)                                       -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактирование задачи"
      size="lg"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-form" *ngIf="editingTask as task">
        <!-- Title Input (Required) -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Название задачи</label>
            <span class="req-tag">Обязательное поле</span>
          </div>
          <input
            type="text"
            class="clean-input title-input"
            [class.input-error]="isEditSubmitted && !editForm.title.trim()"
            [(ngModel)]="editForm.title"
          />
          <span class="error-msg" *ngIf="isEditSubmitted && !editForm.title.trim()">
            Название задачи не может быть пустым
          </span>
        </div>

        <!-- Visual Type Selector Chips -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Тип задачи</label>
          </div>
          <div class="type-chips-selector">
            <button
              *ngFor="let ty of taskTypes()"
              type="button"
              class="type-chip-btn"
              [class.active]="editForm.taskType === ty.code"
              (click)="editForm.taskType = ty.code"
              [style.--chip-color]="ty.color"
            >
              <span class="material-symbols-outlined">{{ ty.icon }}</span>
              <span>{{ ty.name }}</span>
            </button>
          </div>
        </div>

        <!-- Visual Priority Selector Pills -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Приоритет</label>
          </div>
          <div class="priority-chips-selector">
            <button
              type="button"
              class="prio-chip-btn prio-low"
              [class.active]="editForm.priority === 'low'"
              (click)="editForm.priority = 'low'"
            >
              Низкий
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-medium"
              [class.active]="editForm.priority === 'medium'"
              (click)="editForm.priority = 'medium'"
            >
              Средний
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-high"
              [class.active]="editForm.priority === 'high'"
              (click)="editForm.priority = 'high'"
            >
              Высокий
            </button>
            <button
              type="button"
              class="prio-chip-btn prio-critical"
              [class.active]="editForm.priority === 'critical'"
              (click)="editForm.priority = 'critical'"
            >
              Критический
            </button>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Project Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Проект</label>
            </div>
            <select class="clean-input" [(ngModel)]="editForm.projectId">
              <option [ngValue]="null">Без проекта</option>
              <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
            </select>
          </div>

          <!-- Parent Task (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Родительская задача</label>
            </div>
            <ui-searchable-select
              [options]="getAvailableParentTaskOptions(task.id)"
              [selectedId]="editForm.parentTaskId"
              (selectedIdChange)="editForm.parentTaskId = $event"
              placeholder="Без родителя (корневая задача)"
              searchPlaceholder="Поиск задачи по ID или названию..."
              emptyLabel="Без родителя (корневая задача)"
            ></ui-searchable-select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (Searchable Select) -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Ответственный сотрудник (I-T1)</label>
            </div>
            <ui-searchable-select
              [options]="userSelectOptions()"
              [selectedId]="editForm.responsibleUserId"
              (selectedIdChange)="editForm.responsibleUserId = $event"
              placeholder="Выберите ответственного..."
              searchPlaceholder="Поиск сотрудника по имени или логину..."
              emptyLabel="Не назначен"
            ></ui-searchable-select>
          </div>

          <!-- Deadlines: End Date / Deadline -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Срок сдачи (Дедлайн)</label>
            </div>
            <input type="datetime-local" class="clean-input font-mono" [(ngModel)]="editForm.endTime" />
          </div>
        </div>

        <!-- Observers Multi-Select -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Наблюдатели (получают уведомления)</label>
          </div>
          <div class="users-chips-box">
            <label
              *ngFor="let u of usersList()"
              class="user-chip-toggle"
              [class.active]="isObserverSelectedInEdit(u.id)"
            >
              <input
                type="checkbox"
                [checked]="isObserverSelectedInEdit(u.id)"
                (change)="toggleObserverInEdit(u.id)"
              />
              <span>{{ u.name }}</span>
            </label>
          </div>
        </div>

        <!-- Description -->
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Подробное описание (Markdown)</label>
          </div>
          <textarea
            class="clean-input clean-textarea"
            rows="4"
            [(ngModel)]="editForm.descriptionMarkdown"
          ></textarea>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">Дополнительные настраиваемые поля</h4>
          <ui-custom-fields
            [fields]="taskCustomFields()"
            [(values)]="editForm.attributes"
          ></ui-custom-fields>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditTask()">Сохранить изменения</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Dictionaries Settings Modal (With Reordering Support)                   -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isSettingsModalOpen()"
      title="Настройка справочников задач"
      size="md"
      (close)="isSettingsModalOpen.set(false)"
    >
      <div body class="settings-modal-content">
        <!-- Settings Tabs -->
        <div class="settings-tabs">
          <button
            type="button"
            class="tab-btn"
            [class.active]="settingsTab === 'types'"
            (click)="settingsTab = 'types'"
          >
            Типы задач ({{ taskTypes().length }})
          </button>
          <button
            type="button"
            class="tab-btn"
            [class.active]="settingsTab === 'statuses'"
            (click)="settingsTab = 'statuses'"
          >
            Статусы задач ({{ statuses().length }})
          </button>
        </div>

        <!-- TAB 1: Task Types -->
        <div class="tab-pane" *ngIf="settingsTab === 'types'">
          <div class="dict-list">
            <div *ngFor="let ty of taskTypes(); let i = index; let first = first; let last = last" class="dict-row">
              <div class="dict-item-info">
                <!-- Reorder buttons -->
                <div class="reorder-btns">
                  <button type="button" class="reorder-btn" [disabled]="first" (click)="moveType(i, -1)">▲</button>
                  <button type="button" class="reorder-btn" [disabled]="last" (click)="moveType(i, 1)">▼</button>
                </div>
                <span class="material-symbols-outlined dict-ico" [style.color]="ty.color">{{ ty.icon }}</span>
                <span class="dict-name">{{ ty.name }}</span>
                <span class="font-mono text-muted text-xs">({{ ty.code }})</span>
                <span *ngIf="ty.isSystem" class="sys-badge">Системный</span>
              </div>
              <div class="dict-actions" *ngIf="!ty.isSystem">
                <button type="button" class="mini-del-btn" title="Удалить" (click)="deleteTaskType(ty.id)">
                  <span class="material-symbols-outlined">delete</span>
                </button>
              </div>
            </div>
          </div>

          <!-- Add New Type Form -->
          <div class="add-dict-box">
            <h5 class="add-dict-title">Добавить новый тип задачи</h5>
            <div class="form-grid-3">
              <input type="text" class="clean-input" placeholder="Код (lat), e.g. doc" [(ngModel)]="newTypeForm.code" />
              <input type="text" class="clean-input" placeholder="Название, e.g. Документ" [(ngModel)]="newTypeForm.name" />
              <input type="color" class="clean-input color-picker" [(ngModel)]="newTypeForm.color" title="Цвет" />
            </div>
            <div class="add-dict-actions">
              <ui-button variant="secondary" size="sm" icon="add" (onClick)="submitCreateType()">
                Добавить тип
              </ui-button>
            </div>
          </div>
        </div>

        <!-- TAB 2: Task Statuses -->
        <div class="tab-pane" *ngIf="settingsTab === 'statuses'">
          <div class="dict-list">
            <div *ngFor="let s of statuses(); let i = index; let first = first; let last = last" class="dict-row">
              <div class="dict-item-info">
                <!-- Reorder buttons -->
                <div class="reorder-btns">
                  <button type="button" class="reorder-btn" [disabled]="first" (click)="moveStatus(i, -1)">▲</button>
                  <button type="button" class="reorder-btn" [disabled]="last" (click)="moveStatus(i, 1)">▼</button>
                </div>
                <span class="status-dot" [style.background-color]="s.color"></span>
                <span class="dict-name">{{ s.name }}</span>
                <span *ngIf="s.isTerminal" class="term-badge">Завершающий</span>
                <span *ngIf="s.pcode" class="sys-badge">Базовый</span>
              </div>
              <div class="dict-actions" *ngIf="!s.pcode">
                <button type="button" class="mini-del-btn" title="Удалить" (click)="deleteTaskStatus(s.id)">
                  <span class="material-symbols-outlined">delete</span>
                </button>
              </div>
            </div>
          </div>

          <!-- Add New Status Form -->
          <div class="add-dict-box">
            <h5 class="add-dict-title">Добавить новый статус</h5>
            <div class="form-grid-3">
              <input type="text" class="clean-input" placeholder="Название статуса..." [(ngModel)]="newStatusForm.name" />
              <input type="color" class="clean-input color-picker" [(ngModel)]="newStatusForm.color" title="Цвет статуса" />
              <label class="terminal-toggle-label">
                <input type="checkbox" [(ngModel)]="newStatusForm.isTerminal" />
                <span>Завершающий</span>
              </label>
            </div>
            <div class="add-dict-actions">
              <ui-button variant="secondary" size="sm" icon="add" (onClick)="submitCreateStatus()">
                Добавить статус
              </ui-button>
            </div>
          </div>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isSettingsModalOpen.set(false)">Закрыть</ui-button>
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
      width: 5px;
      height: 5px;
      border-radius: 50%;
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
    .title-overdue {
      color: var(--text-main);
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

    /* Kanban */
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

    .column-tasks {
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-height: 100px;
    }

    .kanban-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      cursor: pointer;
      transition: all 0.12s ease;
      box-shadow: var(--shadow-sm);
    }
    .kanban-card:hover {
      border-color: var(--primary);
      transform: translateY(-1px);
      box-shadow: var(--shadow-md);
    }
    .kanban-card.card-overdue {
      border-color: rgba(239, 68, 68, 0.4);
      background-color: rgba(239, 68, 68, 0.02);
    }

    .card-top-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
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
      padding: 24px 0;
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
    .anc-link { color: var(--primary); cursor: pointer; text-decoration: underline; }
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
    .description-box {
      background-color: var(--bg-hover);
      border-radius: var(--radius-sm);
      padding: 10px 12px;
      font-size: 13px;
      line-height: 1.5;
      white-space: pre-wrap;
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
      max-height: 200px;
      overflow-y: auto;
    }
    .comment-card {
      background-color: var(--bg-hover);
      border-radius: var(--radius-sm);
      padding: 8px 10px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .comment-top { display: flex; justify-content: space-between; font-size: 11px; }
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

    .clean-textarea { height: auto; padding: 6px 8px; resize: vertical; font-family: inherit; }

    .users-chips-box {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      max-height: 100px;
      overflow-y: auto;
      padding: 4px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-hover);
    }
    .user-chip-toggle {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 3px;
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      cursor: pointer;
    }
    .user-chip-toggle.active {
      border-color: var(--primary);
      background-color: rgba(99,102,241,0.1);
      color: var(--primary);
      font-weight: 500;
    }

    .custom-fields-section {
      border-top: 1px dashed var(--border-color);
      padding-top: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .custom-fields-title { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; }

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
    }
    .dict-item-info { display: flex; align-items: center; gap: 6px; }
    .reorder-btns { display: flex; flex-direction: column; gap: 1px; }
    .reorder-btn {
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-muted);
      font-size: 8px;
      width: 16px;
      height: 12px;
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      border-radius: 2px;
      padding: 0;
    }
    .reorder-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
    .reorder-btn:disabled { opacity: 0.2; cursor: not-allowed; }

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
    .color-picker { width: 100%; padding: 2px; cursor: pointer; }
    .terminal-toggle-label {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      color: var(--text-main);
      cursor: pointer;
    }
    .add-dict-actions { display: flex; justify-content: flex-end; }

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
  readonly comments = signal<TaskComment[]>([]);

  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  nextCursor: string | null = null;

  viewMode: 'table' | 'kanban' = 'table';
  searchQuery = '';
  selectedPriority = '';
  selectedProjectId: number | null = null;
  selectedStatusId: number | null = null;
  newCommentText = '';

  // Dictionaries Settings Modal
  readonly isSettingsModalOpen = signal<boolean>(false);
  settingsTab: 'types' | 'statuses' = 'types';
  newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
  newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };

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

  userSelectOptions(): SelectOption[] {
    return this.usersList().map(u => ({
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

    this.isLoading.set(true);
    this.api.get<KeysetPage<Task>>('/tasks', {
      limit: 50,
      cursor: this.nextCursor || undefined,
      search: this.searchQuery || undefined,
      priority: this.selectedPriority || undefined,
      project_id: this.selectedProjectId || undefined,
      status_id: this.selectedStatusId || undefined
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

  hasActiveFilters(): boolean {
    return !!this.searchQuery || !!this.selectedPriority || this.selectedProjectId !== null || this.selectedStatusId !== null;
  }

  clearSearch() {
    this.searchQuery = '';
    this.loadTasks(true);
  }

  setStatusFilter(statusId: number | null) {
    this.selectedStatusId = statusId;
    this.loadTasks(true);
  }

  resetFilters() {
    this.searchQuery = '';
    this.selectedPriority = '';
    this.selectedProjectId = null;
    this.selectedStatusId = null;
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
        }
      },
      error: () => {}
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
        this.toast.success('Комментарий добавлен');
      },
      error: err => {
        this.toast.error(err.error?.message || 'Не удалось отправить комментарий');
      }
    });
  }

  updateStatus(taskId: number, newStatusId: number) {
    this.api.post(`/tasks/${taskId}/status`, { statusId: newStatusId }).subscribe({
      next: () => {
        this.toast.success('Статус задачи обновлен');
        this.tasks.update(list => list.map(t => t.id === taskId ? { ...t, statusId: newStatusId } : t));
        if (this.selectedTask()?.id === taskId) {
          this.selectedTask.update(t => t ? { ...t, statusId: newStatusId } : null);
        }
      },
      error: err => {
        this.toast.error(err.error?.message || 'Не удалось изменить статус');
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

  isObserverSelectedInCreate(userId: number): boolean {
    return this.createForm.observerUserIds.includes(userId);
  }

  toggleObserverInCreate(userId: number) {
    const idx = this.createForm.observerUserIds.indexOf(userId);
    if (idx >= 0) {
      this.createForm.observerUserIds.splice(idx, 1);
    } else {
      this.createForm.observerUserIds.push(userId);
    }
  }

  submitCreateTask() {
    this.isCreateSubmitted = true;
    if (!this.createForm.title.trim()) {
      this.toast.warning('Укажите название задачи');
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
        this.toast.success('Задача успешно создана');
        this.loadTasks(true);
        if (this.selectedTask() && this.createForm.parentTaskId === this.selectedTask()?.id) {
          this.loadTaskFullDetails(this.selectedTask()!.id);
        }
      },
      error: err => {
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || 'Ошибка при сохранении задачи');
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

  isObserverSelectedInEdit(userId: number): boolean {
    return this.editForm.observerUserIds.includes(userId);
  }

  toggleObserverInEdit(userId: number) {
    const idx = this.editForm.observerUserIds.indexOf(userId);
    if (idx >= 0) {
      this.editForm.observerUserIds.splice(idx, 1);
    } else {
      this.editForm.observerUserIds.push(userId);
    }
  }

  submitEditTask() {
    if (!this.editingTask) return;
    this.isEditSubmitted = true;
    if (!this.editForm.title.trim()) {
      this.toast.warning('Название задачи обязательно');
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
        this.toast.success('Задача успешно обновлена');
        this.loadTasks(true);
        if (this.selectedTask()?.id === this.editingTask?.id) {
          this.loadTaskFullDetails(this.editingTask!.id);
        }
      },
      error: err => {
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || 'Ошибка при обновлении задачи');
      }
    });
  }

  // =========================================================================
  // Dictionaries Management (With Move Up / Move Down Reordering)
  // =========================================================================
  openSettingsModal() {
    this.newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
    this.newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
    this.isSettingsModalOpen.set(true);
  }

  moveType(index: number, direction: -1 | 1) {
    const list = [...this.taskTypes()];
    const targetIdx = index + direction;
    if (targetIdx < 0 || targetIdx >= list.length) return;

    const temp = list[index];
    list[index] = list[targetIdx];
    list[targetIdx] = temp;

    this.taskTypes.set(list);
    const orderedIds = list.map(t => t.id);

    this.api.post('/tasks/types/reorder', orderedIds).subscribe({
      next: () => this.toast.success('Порядок типов задач сохранен'),
      error: err => this.toast.error(err.error?.message || 'Ошибка изменения порядка')
    });
  }

  moveStatus(index: number, direction: -1 | 1) {
    const list = [...this.statuses()];
    const targetIdx = index + direction;
    if (targetIdx < 0 || targetIdx >= list.length) return;

    const temp = list[index];
    list[index] = list[targetIdx];
    list[targetIdx] = temp;

    this.statuses.set(list);
    const orderedIds = list.map(s => s.id);

    this.api.post('/tasks/statuses/reorder', orderedIds).subscribe({
      next: () => this.toast.success('Порядок статусов сохранен'),
      error: err => this.toast.error(err.error?.message || 'Ошибка изменения порядка')
    });
  }

  submitCreateType() {
    if (!this.newTypeForm.code.trim() || !this.newTypeForm.name.trim()) {
      this.toast.warning('Укажите код и название типа');
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
        this.toast.success('Тип задачи добавлен');
        this.newTypeForm = { code: '', name: '', icon: 'task_alt', color: '#6366f1' };
        this.loadTypes();
      },
      error: err => this.toast.error(err.error?.message || 'Ошибка добавления типа')
    });
  }

  deleteTaskType(id: number) {
    if (!confirm('Удалить этот тип задачи?')) return;
    this.api.delete(`/tasks/types/${id}`).subscribe({
      next: () => {
        this.toast.success('Тип задачи удален');
        this.loadTypes();
      },
      error: err => this.toast.error(err.error?.message || 'Ошибка удаления типа')
    });
  }

  submitCreateStatus() {
    if (!this.newStatusForm.name.trim()) {
      this.toast.warning('Укажите название статуса');
      return;
    }

    this.api.post('/tasks/statuses', {
      name: this.newStatusForm.name.trim(),
      color: this.newStatusForm.color,
      orderNo: (this.statuses().length + 1) * 10,
      isTerminal: this.newStatusForm.isTerminal
    }).subscribe({
      next: () => {
        this.toast.success('Статус задачи добавлен');
        this.newStatusForm = { name: '', color: '#3b82f6', isTerminal: false };
        this.loadStatuses();
      },
      error: err => this.toast.error(err.error?.message || 'Ошибка добавления статуса')
    });
  }

  deleteTaskStatus(id: number) {
    if (!confirm('Удалить этот статус?')) return;
    this.api.delete(`/tasks/statuses/${id}`).subscribe({
      next: () => {
        this.toast.success('Статус удален');
        this.loadStatuses();
      },
      error: err => this.toast.error(err.error?.message || 'Нельзя удалить статус, привязанный к задачам')
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
    return obj ? obj.name : 'Задача';
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
    if (!statusId) return 'Новая';
    const s = this.statuses().find(x => x.id === statusId);
    return s ? s.name : 'В работе';
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
        return 'Критический';
      case 'high':
        return 'Высокий';
      case 'medium':
      case 'normal':
        return 'Средний';
      default:
        return 'Низкий';
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
      case 'R': return 'Ответственный';
      case 'E': return 'Исполнитель';
      case 'O': return 'Наблюдатель';
      case 'A': return 'Автор';
      default: return 'Участник';
    }
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
