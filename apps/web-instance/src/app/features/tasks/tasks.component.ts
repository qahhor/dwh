import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { UiButtonComponent } from '../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../shared/ui/ui-modal.component';
import { UiCustomFieldsComponent } from '../../shared/ui/ui-custom-fields.component';
import { Task, Project, TaskStatus, TaskComment } from '../../core/models/task.models';
import { CustomField } from '../../core/models/custom-field.models';
import { KeysetPage } from '../../core/models/common.models';

@Component({
  selector: 'app-tasks',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    UiButtonComponent,
    UiBadgeComponent,
    UiModalComponent,
    UiCustomFieldsComponent
  ],
  template: `
    <div class="tasks-container">
      <div class="page-header">
        <div>
          <h2 class="page-title">Задачи</h2>
          <p class="page-subtitle">Трекинг задач, иерархические структуры и командная работа</p>
        </div>
        <ui-button
          *ngIf="permService.canCreate('ms_tasks')"
          variant="primary"
          icon="add_task"
          (onClick)="openCreateTaskModal()"
        >
          Новая задача
        </ui-button>
      </div>

      <!-- Filters Toolbar -->
      <div class="card toolbar-card">
        <div class="search-box">
          <span class="material-symbols-outlined">search</span>
          <input
            type="text"
            class="toolbar-input"
            placeholder="Поиск по названию или описанию..."
            [(ngModel)]="searchQuery"
            (keyup.enter)="loadTasks(true)"
          />
        </div>

        <div class="filter-box">
          <select class="toolbar-select" [(ngModel)]="selectedPriority" (change)="loadTasks(true)">
            <option value="">Все приоритеты</option>
            <option value="urgent">Срочный</option>
            <option value="high">Высокий</option>
            <option value="normal">Обычный</option>
            <option value="low">Низкий</option>
          </select>
          <ui-button variant="secondary" size="md" (onClick)="loadTasks(true)">Применить</ui-button>
        </div>
      </div>

      <!-- Tasks Grid -->
      <div class="card table-card">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 70px;">ID</th>
                <th>Название задачи</th>
                <th>Приоритет</th>
                <th>Статус</th>
                <th>Создана</th>
                <th class="text-right">Действие</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let t of tasks()" class="task-row" (click)="selectTask(t)">
                <td class="tabular-nums font-mono text-muted">#{{ t.id }}</td>
                <td>
                  <div class="task-title-box">
                    <span class="task-title font-medium">{{ t.title }}</span>
                    <span *ngIf="t.parentTaskId" class="parent-badge">Родитель: #{{ t.parentTaskId }}</span>
                  </div>
                </td>
                <td>
                  <ui-badge [variant]="t.priority">
                    {{ getPriorityLabel(t.priority) }}
                  </ui-badge>
                </td>
                <td>
                  <ui-badge variant="info">{{ getStatusName(t.statusId) }}</ui-badge>
                </td>
                <td class="tabular-nums text-muted">{{ t.createdAt | date:'dd.MM.yyyy' }}</td>
                <td class="text-right actions-cell" (click)="$event.stopPropagation()">
                  <ui-button variant="ghost" size="sm" icon="visibility" (onClick)="selectTask(t)"></ui-button>
                </td>
              </tr>
              <tr *ngIf="tasks().length === 0 && !isLoading()">
                <td colspan="6" class="empty-cell">Задачи не найдены</td>
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

    <!-- Task Details Slideover / Modal -->
    <ui-modal
      [isOpen]="selectedTask() !== null"
      [title]="'Задача #' + selectedTask()?.id"
      size="lg"
      (close)="selectedTask.set(null)"
    >
      <div body class="task-detail-body" *ngIf="selectedTask() as t">
        <h3 class="detail-task-title">{{ t.title }}</h3>
        
        <div class="detail-meta-grid">
          <div class="meta-item">
            <span class="meta-label">Приоритет:</span>
            <ui-badge [variant]="t.priority">{{ getPriorityLabel(t.priority) }}</ui-badge>
          </div>
          <div class="meta-item">
            <span class="meta-label">Статус:</span>
            <ui-badge variant="info">{{ getStatusName(t.statusId) }}</ui-badge>
          </div>
        </div>

        <div class="detail-description" *ngIf="t.descriptionMarkdown">
          <div class="desc-label">Описание:</div>
          <div class="desc-text">{{ t.descriptionMarkdown }}</div>
        </div>

        <!-- Comments Feed -->
        <div class="comments-section">
          <h4 class="comments-title">Комментарии ({{ comments().length }})</h4>
          <div class="comments-list">
            <div *ngFor="let c of comments()" class="comment-item">
              <div class="comment-header">
                <span class="comment-author">{{ c.userName }} (&#64;{{ c.userLogin }})</span>
                <span class="comment-time tabular-nums">{{ c.createdAt | date:'dd.MM.yyyy HH:mm' }}</span>
              </div>
              <div class="comment-text">{{ c.commentMarkdown }}</div>
            </div>
            <div *ngIf="comments().length === 0" class="no-comments">Комментариев пока нет</div>
          </div>

          <div class="add-comment-box">
            <textarea
              class="comment-input"
              rows="2"
              placeholder="Написать комментарий..."
              [(ngModel)]="newCommentText"
            ></textarea>
            <ui-button variant="primary" size="sm" icon="send" (onClick)="submitComment()">Отправить</ui-button>
          </div>
        </div>
      </div>
    </ui-modal>

    <!-- Create Task Modal -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание новой задачи"
      size="lg"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <div class="form-group">
          <label class="form-label">Название задачи <span class="req">*</span></label>
          <input type="text" class="form-input" [(ngModel)]="createForm.title" placeholder="Краткое описание сути задачи" />
        </div>

        <div class="form-row">
          <div class="form-group flex-1">
            <label class="form-label">Приоритет</label>
            <select class="form-input" [(ngModel)]="createForm.priority">
              <option value="low">Низкий</option>
              <option value="normal">Обычный</option>
              <option value="high">Высокий</option>
              <option value="urgent">Срочный</option>
            </select>
          </div>
          <div class="form-group flex-1">
            <label class="form-label">Родительская задача (ID)</label>
            <input type="number" class="form-input" [(ngModel)]="createForm.parentTaskId" placeholder="ID задачи (опционально)" />
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">Подробное описание (Markdown)</label>
          <textarea class="form-input" rows="4" [(ngModel)]="createForm.descriptionMarkdown" placeholder="Детали задачи, критерии приёмки..."></textarea>
        </div>

        <!-- Custom Fields for Task -->
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
    .tasks-container {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 1400px;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .page-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
    }

    .page-subtitle {
      font-size: 12px;
      color: var(--text-muted);
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
    }

    .toolbar-card {
      padding: 12px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }

    .search-box {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      max-width: 420px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 4px 10px;
      color: var(--text-muted);
    }

    .toolbar-input {
      border: none;
      background: transparent;
      outline: none;
      font-size: 13px;
      font-family: inherit;
      color: var(--text-main);
      width: 100%;
    }

    .filter-box {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .toolbar-select {
      height: 34px;
      padding: 4px 10px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      outline: none;
    }

    .table-card {
      padding: 0;
      overflow: hidden;
    }

    .table-wrapper {
      overflow-x: auto;
    }

    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }

    .data-table th {
      text-align: left;
      padding: 10px 14px;
      font-weight: 600;
      color: var(--text-muted);
      border-bottom: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      font-size: 12px;
    }

    .data-table td {
      padding: 10px 14px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
    }

    .task-row {
      cursor: pointer;
      transition: background-color 0.1s ease;
    }
    .task-row:hover {
      background-color: var(--bg-hover);
    }

    .task-title-box {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .parent-badge {
      font-size: 10px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
      padding: 1px 5px;
      border-radius: var(--radius-sm);
      border: 1px solid var(--border-color);
    }

    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .font-mono { font-family: monospace; }
    .font-medium { font-weight: 500; }

    .load-more-bar {
      padding: 12px;
      display: flex;
      justify-content: center;
      background-color: var(--bg-hover);
      border-top: 1px solid var(--border-color);
    }

    .empty-cell {
      text-align: center;
      color: var(--text-muted);
      padding: 32px !important;
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .form-row {
      display: flex;
      gap: 14px;
    }

    .flex-1 { flex: 1; }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .form-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-main);
    }

    .req { color: var(--danger); }

    .form-input {
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 13px;
      font-family: inherit;
      outline: none;
    }

    .detail-task-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 12px;
    }

    .detail-meta-grid {
      display: flex;
      gap: 16px;
      margin-bottom: 16px;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--border-color);
    }

    .meta-item {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
    }

    .meta-label {
      color: var(--text-muted);
    }

    .detail-description {
      margin-bottom: 20px;
    }

    .desc-label {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-muted);
      margin-bottom: 4px;
    }

    .desc-text {
      font-size: 13px;
      color: var(--text-main);
      line-height: 1.5;
      background-color: var(--bg-hover);
      padding: 10px 14px;
      border-radius: var(--radius-md);
      white-space: pre-wrap;
    }

    .comments-section {
      border-top: 1px solid var(--border-color);
      padding-top: 16px;
    }

    .comments-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 10px;
    }

    .comments-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-bottom: 12px;
      max-height: 240px;
      overflow-y: auto;
    }

    .comment-item {
      background-color: var(--bg-hover);
      padding: 8px 12px;
      border-radius: var(--radius-md);
    }

    .comment-header {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
      margin-bottom: 4px;
    }

    .comment-author {
      font-weight: 600;
      color: var(--text-main);
    }

    .comment-time {
      color: var(--text-muted);
    }

    .comment-text {
      font-size: 12px;
      color: var(--text-main);
    }

    .no-comments {
      font-size: 12px;
      color: var(--text-muted);
      text-align: center;
      padding: 12px;
    }

    .add-comment-box {
      display: flex;
      gap: 8px;
      align-items: flex-end;
    }

    .comment-input {
      flex: 1;
      padding: 6px 10px;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      background-color: var(--bg-surface);
      color: var(--text-main);
      font-size: 12px;
      font-family: inherit;
      outline: none;
      resize: vertical;
    }

    .custom-fields-section {
      margin-top: 8px;
      padding-top: 12px;
      border-top: 1px dashed var(--border-color);
    }

    .custom-fields-title {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-main);
      margin-bottom: 8px;
    }
  `]
})
export class TasksComponent implements OnInit {
  readonly tasks = signal<Task[]>([]);
  readonly taskCustomFields = signal<CustomField[]>([]);
  readonly selectedTask = signal<Task | null>(null);
  readonly comments = signal<TaskComment[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  nextCursor: string | null = null;

  searchQuery = '';
  selectedPriority = '';
  newCommentText = '';

  readonly isCreateModalOpen = signal<boolean>(false);
  createForm: any = {
    title: '',
    descriptionMarkdown: '',
    priority: 'normal',
    parentTaskId: null,
    attributes: {}
  };

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadTasks(true);
    this.loadTaskCustomFields();
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
      priority: this.selectedPriority || undefined
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

  loadTaskCustomFields() {
    this.api.get<CustomField[]>('/custom-fields', { entity_type: 'TASK' }).subscribe(res => {
      this.taskCustomFields.set(res || []);
    });
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

  openCreateTaskModal() {
    this.createForm = {
      title: '',
      descriptionMarkdown: '',
      priority: 'normal',
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

  getPriorityLabel(priority: string): string {
    switch (priority) {
      case 'urgent': return 'Срочный';
      case 'high': return 'Высокий';
      case 'normal': return 'Обычный';
      default: return 'Низкий';
    }
  }

  getStatusName(statusId: number): string {
    return 'В работе';
  }
}
