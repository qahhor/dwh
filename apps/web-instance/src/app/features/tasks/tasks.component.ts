import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../shared/ui/ui-custom-fields.component';
import { Task, Project, TaskStatus, TaskComment, TaskMember, TaskDetailResponse } from '../../core/models/task.models';
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
    UiCustomFieldsComponent
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
                <th style="width: 100px;">Тип</th>
                <th>Задача</th>
                <th>Проект</th>
                <th>Приоритет</th>
                <th>Статус</th>
                <th>Срок</th>
                <th class="text-right" style="width: 110px;">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let t of tasks()" class="task-row" (click)="openTaskDetails(t)">
                <td class="tabular-nums font-mono text-muted">#{{ t.id }}</td>
                <td>
                  <span class="task-type-badge" [attr.data-type]="getTaskType(t)">
                    <span class="material-symbols-outlined type-icon">{{ getTaskTypeIcon(t) }}</span>
                    {{ getTaskTypeLabel(t) }}
                  </span>
                </td>
                <td>
                  <div class="task-title-cell">
                    <span class="task-title">{{ t.title }}</span>
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
                    <span class="material-symbols-outlined ico">event</span>
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
              (click)="openTaskDetails(task)"
            >
              <!-- Card Top -->
              <div class="card-top-row">
                <span class="task-type-badge-mini" [attr.data-type]="getTaskType(task)">
                  <span class="material-symbols-outlined mini-ico">{{ getTaskTypeIcon(task) }}</span>
                  <span class="task-id font-mono">#{{ task.id }}</span>
                </span>
                <span class="priority-pill" [attr.data-priority]="task.priority">
                  {{ getPriorityLabel(task.priority) }}
                </span>
              </div>

              <!-- Card Title -->
              <h4 class="card-title">{{ task.title }}</h4>

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
                  <span class="material-symbols-outlined ico">event</span>
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
    <!-- Task Details Modal                                                      -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="selectedTask() !== null"
      [title]="'Задача #' + selectedTask()?.id"
      size="lg"
      (close)="selectedTask.set(null)"
    >
      <div body class="task-details-view" *ngIf="selectedTask() as t">
        <div class="detail-header-row">
          <div class="detail-title-group">
            <span class="task-type-badge" [attr.data-type]="getTaskType(t)">
              <span class="material-symbols-outlined type-icon">{{ getTaskTypeIcon(t) }}</span>
              {{ getTaskTypeLabel(t) }}
            </span>
            <h2 class="detail-title">{{ t.title }}</h2>
          </div>

          <div class="detail-actions-group">
            <button
              *ngIf="canUpdateTask()"
              type="button"
              class="edit-action-btn"
              (click)="openEditModal(t)"
            >
              <span class="material-symbols-outlined">edit</span>
              Редактировать
            </button>
            <div class="detail-status-changer">
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
        </div>

        <div class="detail-meta-cards">
          <div class="meta-card">
            <span class="meta-k">Приоритет</span>
            <span class="priority-pill" [attr.data-priority]="t.priority">
              {{ getPriorityLabel(t.priority) }}
            </span>
          </div>
          <div class="meta-card">
            <span class="meta-k">Проект</span>
            <span class="meta-v">{{ getProjectName(t.projectId) || 'Без проекта' }}</span>
          </div>
          <div class="meta-card" *ngIf="t.parentTaskId">
            <span class="meta-k">Родитель</span>
            <span class="meta-v font-mono">#{{ t.parentTaskId }}</span>
          </div>
          <div class="meta-card" *ngIf="t.endTime">
            <span class="meta-k">Дедлайн (Срок)</span>
            <span class="meta-v tabular-nums" [class.text-danger]="isOverdue(t.endTime, t.statusId)">
              {{ t.endTime | date:'dd.MM.yyyy HH:mm' }}
            </span>
          </div>
          <div class="meta-card">
            <span class="meta-k">Создана</span>
            <span class="meta-v tabular-nums">{{ t.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
          </div>
        </div>

        <!-- Task Members (Responsible, Executors, Observers) -->
        <div class="detail-section" *ngIf="taskMembers().length > 0">
          <h4 class="section-label">Участники задачи</h4>
          <div class="members-grid">
            <div *ngFor="let m of taskMembers()" class="member-chip">
              <span class="member-role-badge" [attr.data-role]="m.involveKind">
                {{ getInvolveKindLabel(m.involveKind) }}
              </span>
              <span class="member-name">{{ m.userName }}</span>
              <span class="member-login text-muted">&#64;{{ m.userLogin }}</span>
            </div>
          </div>
        </div>

        <!-- Description -->
        <div class="detail-section" *ngIf="t.descriptionMarkdown">
          <h4 class="section-label">Описание задачи</h4>
          <div class="description-box">{{ t.descriptionMarkdown }}</div>
        </div>

        <!-- Dynamic Attributes -->
        <div class="detail-section" *ngIf="hasAttributes(t.attributes)">
          <h4 class="section-label">Дополнительные поля</h4>
          <div class="attributes-grid">
            <div *ngFor="let item of formatAttributes(t.attributes)" class="attr-pill">
              <span class="attr-k">{{ item.key }}:</span>
              <span class="attr-v">{{ item.value }}</span>
            </div>
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
            <div *ngIf="comments().length === 0" class="no-comments-hint">
              Комментариев пока нет. Напишите первый комментарий!
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
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="selectedTask.set(null)">Закрыть</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Create Task Modal                                                       -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание новой задачи"
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
            class="clean-input"
            [class.input-error]="isCreateSubmitted && !createForm.title.trim()"
            [(ngModel)]="createForm.title"
            placeholder="Краткая и ясная формулировка задачи..."
          />
          <span class="error-msg" *ngIf="isCreateSubmitted && !createForm.title.trim()">
            Пожалуйста, укажите название задачи
          </span>
        </div>

        <div class="form-grid-3">
          <!-- Task Type Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Тип задачи</label>
            </div>
            <select class="clean-input" [(ngModel)]="createForm.taskType">
              <option value="task">📋 Задача (Task)</option>
              <option value="bug">🐛 Ошибка (Bug)</option>
              <option value="feature">⚡ Улучшение (Feature)</option>
              <option value="research">🔍 Исследование (Research)</option>
            </select>
          </div>

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

          <!-- Priority Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Приоритет</label>
            </div>
            <select class="clean-input" [(ngModel)]="createForm.priority">
              <option value="low">Низкий (low)</option>
              <option value="medium">Средний (medium)</option>
              <option value="high">Высокий (high)</option>
              <option value="critical">Критический (critical)</option>
            </select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (I-T1) -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Ответственный сотрудник (I-T1)</label>
            </div>
            <select class="clean-input" [(ngModel)]="createForm.responsibleUserId">
              <option [ngValue]="null">Не назначен</option>
              <option *ngFor="let u of usersList()" [ngValue]="u.id">{{ u.name }} (&#64;{{ u.login }})</option>
            </select>
          </div>

          <!-- Parent Task Dropdown -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Родительская задача</label>
            </div>
            <select class="clean-input" [(ngModel)]="createForm.parentTaskId">
              <option [ngValue]="null">Без родителя (корневая задача)</option>
              <option *ngFor="let pt of tasks()" [ngValue]="pt.id">#{{ pt.id }} — {{ pt.title }}</option>
            </select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Deadlines: Begin & End -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Дата начала</label>
            </div>
            <input type="datetime-local" class="clean-input font-mono" [(ngModel)]="createForm.beginTime" />
          </div>

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
    <!-- Edit Task Modal                                                         -->
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
            class="clean-input"
            [class.input-error]="isEditSubmitted && !editForm.title.trim()"
            [(ngModel)]="editForm.title"
          />
          <span class="error-msg" *ngIf="isEditSubmitted && !editForm.title.trim()">
            Название задачи не может быть пустым
          </span>
        </div>

        <div class="form-grid-3">
          <!-- Task Type Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Тип задачи</label>
            </div>
            <select class="clean-input" [(ngModel)]="editForm.taskType">
              <option value="task">📋 Задача (Task)</option>
              <option value="bug">🐛 Ошибка (Bug)</option>
              <option value="feature">⚡ Улучшение (Feature)</option>
              <option value="research">🔍 Исследование (Research)</option>
            </select>
          </div>

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

          <!-- Priority Selector -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Приоритет</label>
            </div>
            <select class="clean-input" [(ngModel)]="editForm.priority">
              <option value="low">Низкий (low)</option>
              <option value="medium">Средний (medium)</option>
              <option value="high">Высокий (high)</option>
              <option value="critical">Критический (critical)</option>
            </select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Responsible User (I-T1) -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Ответственный сотрудник (I-T1)</label>
            </div>
            <select class="clean-input" [(ngModel)]="editForm.responsibleUserId">
              <option [ngValue]="null">Не назначен</option>
              <option *ngFor="let u of usersList()" [ngValue]="u.id">{{ u.name }} (&#64;{{ u.login }})</option>
            </select>
          </div>

          <!-- Parent Task Dropdown -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Родительская задача</label>
            </div>
            <select class="clean-input" [(ngModel)]="editForm.parentTaskId">
              <option [ngValue]="null">Без родителя (корневая задача)</option>
              <option
                *ngFor="let pt of getAvailableParentTasks(task.id)"
                [ngValue]="pt.id"
              >
                #{{ pt.id }} — {{ pt.title }}
              </option>
            </select>
          </div>
        </div>

        <div class="form-grid-2">
          <!-- Deadlines: Begin & End -->
          <div class="form-group">
            <div class="label-row">
              <label class="clean-label">Дата начала</label>
            </div>
            <input type="datetime-local" class="clean-input font-mono" [(ngModel)]="editForm.beginTime" />
          </div>

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
  `,
  styles: [`
    .tasks-page {
      display: flex;
      flex-direction: column;
      gap: 16px;
      width: 100%;
    }

    /* Minimal Header */
    .view-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
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

    .header-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }

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

    .task-type-badge {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      font-size: 11px;
      font-weight: 500;
      padding: 1px 6px;
      border-radius: 4px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }
    .task-type-badge .type-icon { font-size: 13px; }
    .task-type-badge[data-type="bug"] { color: var(--danger); background-color: var(--danger-bg); }
    .task-type-badge[data-type="feature"] { color: var(--warning); background-color: var(--warning-bg); }
    .task-type-badge[data-type="research"] { color: var(--primary); background-color: rgba(99,102,241,0.1); }

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
      font-weight: 500;
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

    /* ========================================================================= */
    /* KANBAN BOARD STYLES                                                       */
    /* ========================================================================= */
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
      color: var(--text-muted);
    }
    .task-type-badge-mini .mini-ico { font-size: 13px; }
    .task-type-badge-mini[data-type="bug"] { color: var(--danger); }
    .task-type-badge-mini[data-type="feature"] { color: var(--warning); }
    .task-type-badge-mini[data-type="research"] { color: var(--primary); }

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

    /* Task Details Slide/Modal */
    .task-details-view { display: flex; flex-direction: column; gap: 16px; }
    .detail-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      border-bottom: 1px solid var(--border-color);
      padding-bottom: 12px;
    }
    .detail-title-group { display: flex; flex-direction: column; gap: 4px; }
    .detail-title { font-size: 16px; font-weight: 600; margin: 0; color: var(--text-main); }
    .detail-actions-group { display: flex; align-items: center; gap: 8px; }
    .edit-action-btn {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-main);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
    }
    .edit-action-btn .material-symbols-outlined { font-size: 15px; }
    .edit-action-btn:hover { border-color: var(--primary); }

    .detail-meta-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
      gap: 10px;
    }
    .meta-card {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 8px 10px;
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .meta-k { font-size: 10px; text-transform: uppercase; color: var(--text-muted); font-weight: 600; }
    .meta-v { font-size: 12px; font-weight: 500; color: var(--text-main); }

    .members-grid { display: flex; flex-wrap: wrap; gap: 6px; }
    .member-chip {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 3px 8px;
      font-size: 12px;
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

    .detail-section { display: flex; flex-direction: column; gap: 6px; }
    .section-label { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; }
    .description-box {
      background-color: var(--bg-hover);
      border-radius: var(--radius-sm);
      padding: 10px 12px;
      font-size: 13px;
      line-height: 1.5;
      white-space: pre-wrap;
    }

    .attributes-grid { display: flex; flex-wrap: wrap; gap: 6px; }
    .attr-pill {
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-xs);
      padding: 3px 8px;
      font-size: 11px;
      display: inline-flex;
      gap: 4px;
    }
    .attr-k { color: var(--text-muted); }
    .attr-v { color: var(--text-main); font-weight: 500; }

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
    .no-comments-hint { font-size: 12px; color: var(--text-muted); padding: 8px 0; }

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

    /* Modal Form */
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
      height: 32px;
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
    .error-msg { font-size: 11px; color: var(--danger); margin-top: 2px; }

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
  readonly usersList = signal<User[]>([]);
  readonly taskCustomFields = signal<CustomField[]>([]);
  readonly selectedTask = signal<Task | null>(null);
  readonly taskMembers = signal<TaskMember[]>([]);
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
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadStatuses();
    this.loadProjects();
    this.loadUsers();
    this.loadTaskCustomFields();
    this.loadTasks(true);
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
  // Task Creation
  // =========================================================================
  openCreateTaskModal() {
    this.isCreateSubmitted = false;
    this.createForm = {
      title: '',
      taskType: 'task',
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
    this.api.post('/tasks', payload).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success('Задача успешно создана');
        this.loadTasks(true);
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

    // Load task members to populate observers
    this.api.get<TaskDetailResponse>(`/tasks/${task.id}`).subscribe({
      next: res => {
        const obsIds = (res?.members || [])
          .filter(m => m.involveKind === 'O' || m.involvementKind === 'O')
          .map(m => m.userId);

        const respMember = (res?.members || []).find(m => m.involveKind === 'R' || m.involvementKind === 'R');

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

  getAvailableParentTasks(currentTaskId: number): Task[] {
    return this.tasks().filter(t => t.id !== currentTaskId);
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
  // Helpers
  // =========================================================================
  getTaskType(task: Task): string {
    return (task.attributes && task.attributes['task_type']) || 'task';
  }

  getTaskTypeLabel(task: Task): string {
    const type = this.getTaskType(task);
    switch (type) {
      case 'bug': return 'Ошибка';
      case 'feature': return 'Улучшение';
      case 'research': return 'Исследование';
      default: return 'Задача';
    }
  }

  getTaskTypeIcon(task: Task): string {
    const type = this.getTaskType(task);
    switch (type) {
      case 'bug': return 'bug_report';
      case 'feature': return 'bolt';
      case 'research': return 'science';
      default: return 'task_alt';
    }
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
    if (s && s.isTerminal) return false; // Done or cancelled is not overdue
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
