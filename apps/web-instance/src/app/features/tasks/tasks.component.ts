import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../shared/ui/ui-custom-fields.component';
import { Task, Project, TaskStatus, TaskComment } from '../../core/models/task.models';
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
          <!-- Status Quick Switcher -->
          <div class="status-tabs">
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

      <!-- Tasks Table Card -->
      <div class="table-card">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 70px;">ID</th>
                <th>Задача</th>
                <th>Проект</th>
                <th>Приоритет</th>
                <th>Статус</th>
                <th>Создана</th>
                <th class="text-right" style="width: 80px;"></th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let t of tasks()" class="task-row" (click)="selectTask(t)">
                <td class="tabular-nums font-mono text-muted">#{{ t.id }}</td>
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
                <td>
                  <span
                    class="status-pill"
                    [style.color]="getStatusColor(t.statusId)"
                    [style.border-color]="getStatusColor(t.statusId)"
                  >
                    <span class="status-dot" [style.background-color]="getStatusColor(t.statusId)"></span>
                    {{ getStatusName(t.statusId) }}
                  </span>
                </td>
                <td class="tabular-nums text-muted">{{ t.createdAt | date:'dd.MM.yyyy' }}</td>
                <td class="text-right actions-cell" (click)="$event.stopPropagation()">
                  <button
                    type="button"
                    class="icon-ghost-btn"
                    title="Просмотреть детали"
                    (click)="selectTask(t)"
                  >
                    <span class="material-symbols-outlined">visibility</span>
                  </button>
                </td>
              </tr>
              <tr *ngIf="tasks().length === 0 && !isLoading()">
                <td colspan="7" class="empty-state-cell">
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
          <h2 class="detail-title">{{ t.title }}</h2>
          <div class="detail-status-changer">
            <label class="status-change-label">Статус:</label>
            <select
              class="clean-select status-select"
              [ngModel]="t.statusId"
              (ngModelChange)="updateStatus(t.id, $event)"
              [disabled]="!canUpdateTask()"
            >
              <option *ngFor="let s of statuses()" [ngValue]="s.id">{{ s.name }}</option>
            </select>
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
          <div class="meta-card">
            <span class="meta-k">Создана</span>
            <span class="meta-v tabular-nums">{{ t.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
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
              <div class="comment-text">{{ c.commentMarkdown }}</div>
            </div>
            <div *ngIf="comments().length === 0" class="no-comments-hint">
              Комментариев пока нет. Напишите первый комментарий!
            </div>
          </div>

          <div class="add-comment-box">
            <textarea
              class="comment-textarea"
              rows="2"
              placeholder="Написать комментарий к задаче..."
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
        <div class="form-group">
          <label class="clean-label">Название задачи <span class="req">*</span></label>
          <input
            type="text"
            class="clean-input"
            [(ngModel)]="createForm.title"
            placeholder="Краткая формулировка задачи"
          />
        </div>

        <div class="form-grid-2">
          <div class="form-group">
            <label class="clean-label">Проект</label>
            <select class="clean-input" [(ngModel)]="createForm.projectId">
              <option [ngValue]="null">Без проекта</option>
              <option *ngFor="let p of projects()" [ngValue]="p.id">{{ p.name }}</option>
            </select>
          </div>

          <div class="form-group">
            <label class="clean-label">Приоритет</label>
            <select class="clean-input" [(ngModel)]="createForm.priority">
              <option value="low">Низкий (low)</option>
              <option value="medium">Средний (medium)</option>
              <option value="high">Высокий (high)</option>
              <option value="critical">Критический (critical)</option>
            </select>
          </div>
        </div>

        <div class="form-grid-2">
          <div class="form-group">
            <label class="clean-label">Ответственный (I-T1)</label>
            <select class="clean-input" [(ngModel)]="createForm.responsibleUserId">
              <option [ngValue]="null">Не назначен</option>
              <option *ngFor="let u of usersList()" [ngValue]="u.id">{{ u.name }} (&#64;{{ u.login }})</option>
            </select>
          </div>

          <div class="form-group">
            <label class="clean-label">Родительская задача (ID)</label>
            <input
              type="number"
              class="clean-input font-mono"
              [(ngModel)]="createForm.parentTaskId"
              placeholder="ID задачи (опционально)"
            />
          </div>
        </div>

        <div class="form-group">
          <label class="clean-label">Подробное описание (Markdown)</label>
          <textarea
            class="clean-input clean-textarea"
            rows="3"
            [(ngModel)]="createForm.descriptionMarkdown"
            placeholder="Детали задачи, контекст и критерии готовности..."
          ></textarea>
        </div>

        <!-- Custom Dynamic Fields -->
        <div class="custom-fields-section" *ngIf="taskCustomFields().length > 0">
          <h4 class="custom-fields-title">Дополнительные поля</h4>
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
      gap: 8px;
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

    /* Status Pills */
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 11px;
      font-weight: 500;
      padding: 1px 6px;
      border-radius: 10px;
      border: 1px solid;
    }
    .status-dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
    }

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
    .icon-ghost-btn .material-symbols-outlined { font-size: 18px; }

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

    /* Task Details Slide/Modal */
    .task-details-view {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .detail-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      border-bottom: 1px solid var(--border-color);
      padding-bottom: 12px;
    }
    .detail-title { font-size: 16px; font-weight: 600; margin: 0; color: var(--text-main); }
    .detail-status-changer { display: flex; align-items: center; gap: 8px; }
    .status-change-label { font-size: 12px; color: var(--text-muted); }
    .status-select { font-weight: 500; }

    .detail-meta-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
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
    .comment-top {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
    }
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
    .form-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .clean-label { font-size: 11px; font-weight: 500; color: var(--text-muted); }
    .clean-input {
      height: 32px;
      padding: 4px 8px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }
    .clean-input:focus { border-color: var(--primary); }
    .clean-textarea { height: auto; padding: 6px 8px; resize: vertical; font-family: inherit; }

    .custom-fields-section {
      border-top: 1px dashed var(--border-color);
      padding-top: 10px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .custom-fields-title { font-size: 12px; font-weight: 600; color: var(--text-muted); margin: 0; }

    .req { color: var(--danger); }
    .font-mono { font-family: monospace; }
    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
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
  readonly comments = signal<TaskComment[]>([]);

  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  nextCursor: string | null = null;

  searchQuery = '';
  selectedPriority = '';
  selectedProjectId: number | null = null;
  selectedStatusId: number | null = null;
  newCommentText = '';

  readonly isCreateModalOpen = signal<boolean>(false);
  createForm = {
    title: '',
    descriptionMarkdown: '',
    projectId: null as number | null,
    priority: 'medium',
    responsibleUserId: null as number | null,
    parentTaskId: null as number | null,
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
    this.api.get<TaskStatus[]>('/tasks/statuses').subscribe(res => {
      this.statuses.set(res || []);
    });
  }

  loadProjects() {
    this.api.get<Project[]>('/tasks/projects').subscribe(res => {
      this.projects.set(res || []);
    });
  }

  loadUsers() {
    this.api.get<KeysetPage<User>>('/iam/users', { limit: 100 }).subscribe(res => {
      this.usersList.set(res?.items || []);
    });
  }

  loadTaskCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'TASK' }).subscribe(res => {
      this.taskCustomFields.set(res || []);
    });
  }

  loadTasks(reset: boolean = false) {
    if (reset) {
      this.nextCursor = null;
    }

    this.isLoading.set(true);
    this.api.get<KeysetPage<Task>>('/tasks', {
      limit: 20,
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

  selectTask(task: Task) {
    this.selectedTask.set(task);
    this.loadComments(task.id);
  }

  loadComments(taskId: number) {
    this.api.get<TaskComment[]>(`/tasks/${taskId}/comments`).subscribe(res => {
      this.comments.set(res || []);
    });
  }

  submitComment() {
    const task = this.selectedTask();
    if (!task || !this.newCommentText.trim()) return;

    this.api.post(`/tasks/${task.id}/comments`, { commentMarkdown: this.newCommentText.trim() }).subscribe(() => {
      this.newCommentText = '';
      this.loadComments(task.id);
      this.toast.success('Комментарий добавлен');
    });
  }

  updateStatus(taskId: number, newStatusId: number) {
    this.api.post(`/tasks/${taskId}/status`, { statusId: newStatusId }).subscribe({
      next: () => {
        this.toast.success('Статус задачи обновлен');
        if (this.selectedTask()?.id === taskId) {
          this.selectedTask.update(t => t ? { ...t, statusId: newStatusId } : null);
        }
        this.loadTasks(true);
      }
    });
  }

  openCreateTaskModal() {
    this.createForm = {
      title: '',
      descriptionMarkdown: '',
      projectId: this.selectedProjectId,
      priority: 'medium',
      responsibleUserId: null,
      parentTaskId: null,
      attributes: {}
    };
    this.isCreateModalOpen.set(true);
  }

  submitCreateTask() {
    if (!this.createForm.title.trim()) {
      this.toast.warning('Укажите название задачи');
      return;
    }

    this.isSubmitting.set(true);
    this.api.post('/tasks', this.createForm).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success('Задача успешно создана');
        this.loadTasks(true);
      },
      error: () => {
        this.isSubmitting.set(false);
      }
    });
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

  hasAttributes(attrs: any): boolean {
    return attrs && typeof attrs === 'object' && Object.keys(attrs).length > 0;
  }

  formatAttributes(attrs: any): Array<{ key: string; value: string }> {
    if (!this.hasAttributes(attrs)) return [];
    return Object.entries(attrs).map(([k, v]) => ({ key: k, value: String(v) }));
  }
}
