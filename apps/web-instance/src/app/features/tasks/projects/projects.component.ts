import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router } from '@angular/router';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { Project } from '../../../core/models/task.models';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, UiButtonComponent, UiModalComponent],
  template: `
    <div class="projects-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Проекты</h1>
          <span class="proj-count">{{ filteredProjects().length }}</span>
        </div>
        <div class="header-right">
          <ui-button
            *ngIf="canCreateProject()"
            variant="primary"
            size="md"
            icon="add"
            (onClick)="openCreateModal()"
          >
            Новый проект
          </ui-button>
        </div>
      </div>

      <!-- Toolbar -->
      <div class="toolbar">
        <div class="search-box">
          <span class="material-symbols-outlined icon">search</span>
          <input
            type="text"
            class="search-input"
            placeholder="Поиск проекта..."
            [(ngModel)]="searchQuery"
          />
          <button *ngIf="searchQuery" type="button" class="clear-btn" (click)="searchQuery = ''">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>

        <div class="segmented-control">
          <button
            type="button"
            class="segment-btn"
            [class.active]="selectedState === 'all'"
            (click)="selectedState = 'all'"
          >
            Все
          </button>
          <button
            type="button"
            class="segment-btn"
            [class.active]="selectedState === 'A'"
            (click)="selectedState = 'A'"
          >
            Активные
          </button>
          <button
            type="button"
            class="segment-btn"
            [class.active]="selectedState === 'P'"
            (click)="selectedState = 'P'"
          >
            Архив
          </button>
        </div>
      </div>

      <!-- Projects Grid -->
      <div class="projects-grid">
        <div
          *ngFor="let p of filteredProjects()"
          class="project-card"
          (click)="viewProjectTasks(p)"
        >
          <div class="card-top">
            <div class="project-icon-box">
              <span class="material-symbols-outlined">folder</span>
            </div>
            <div class="card-top-right">
              <span class="status-pill" [class.active]="p.state === 'A'">
                <span class="status-dot" [class.active]="p.state === 'A'"></span>
                {{ p.state === 'A' ? 'Активен' : 'Архив' }}
              </span>
              <button
                *ngIf="canUpdateProject()"
                type="button"
                class="edit-btn"
                title="Редактировать проект"
                (click)="$event.stopPropagation(); openEditModal(p)"
              >
                <span class="material-symbols-outlined">edit</span>
              </button>
            </div>
          </div>

          <div class="card-content">
            <h3 class="project-title">{{ p.name }}</h3>
            <p class="project-desc">{{ p.description || 'Описание проекта отсутствует' }}</p>
          </div>

          <div class="card-foot">
            <span class="foot-date tabular-nums">Создан: {{ p.createdAt | date:'dd.MM.yyyy' }}</span>
            <span class="view-tasks-link">
              Задачи →
            </span>
          </div>
        </div>

        <div *ngIf="filteredProjects().length === 0" class="empty-projects-cell">
          <span class="material-symbols-outlined icon">folder_off</span>
          <p>Проекты не найдены</p>
        </div>
      </div>
    </div>

    <!-- ======================================================================= -->
    <!-- Create Project Modal                                                    -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание нового проекта"
      size="sm"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Название проекта</label>
            <span class="req-tag">Обязательное поле</span>
          </div>
          <input
            type="text"
            class="clean-input"
            [class.input-error]="isCreateSubmitted && !createForm.name.trim()"
            [(ngModel)]="createForm.name"
            placeholder="Например: Внедрение DWH & CDC"
          />
          <span class="error-msg" *ngIf="isCreateSubmitted && !createForm.name.trim()">
            Пожалуйста, укажите название проекта
          </span>
        </div>
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Описание проекта</label>
          </div>
          <textarea
            class="clean-input clean-textarea"
            rows="3"
            [(ngModel)]="createForm.description"
            placeholder="Цели, границы и контекст проекта..."
          ></textarea>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateProject()">Создать</ui-button>
      </div>
    </ui-modal>

    <!-- ======================================================================= -->
    <!-- Edit Project Modal                                                      -->
    <!-- ======================================================================= -->
    <ui-modal
      [isOpen]="isEditModalOpen()"
      title="Редактирование проекта"
      size="sm"
      (close)="isEditModalOpen.set(false)"
    >
      <div body class="modal-form" *ngIf="editingProject as p">
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Название проекта</label>
            <span class="req-tag">Обязательное поле</span>
          </div>
          <input
            type="text"
            class="clean-input"
            [class.input-error]="isEditSubmitted && !editForm.name.trim()"
            [(ngModel)]="editForm.name"
          />
          <span class="error-msg" *ngIf="isEditSubmitted && !editForm.name.trim()">
            Название проекта не может быть пустым
          </span>
        </div>
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Статус активности</label>
          </div>
          <select class="clean-input" [(ngModel)]="editForm.state">
            <option value="A">Активен (A)</option>
            <option value="P">В архиве (P)</option>
          </select>
        </div>
        <div class="form-group">
          <div class="label-row">
            <label class="clean-label">Описание</label>
          </div>
          <textarea class="clean-input clean-textarea" rows="3" [(ngModel)]="editForm.description"></textarea>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isEditModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitEditProject()">Сохранить</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .projects-page {
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
    .header-left { display: flex; align-items: center; gap: 8px; }
    .view-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }
    .proj-count {
      font-size: 12px;
      color: var(--text-muted);
      background-color: var(--bg-hover);
      padding: 1px 7px;
      border-radius: 10px;
      font-weight: 500;
      border: 1px solid var(--border-color);
    }
    .header-right { display: flex; align-items: center; gap: 8px; }

    /* Toolbar */
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
    .search-box {
      display: flex;
      align-items: center;
      gap: 6px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 4px 8px;
      width: 280px;
      max-width: 100%;
    }
    .search-box .icon { font-size: 16px; color: var(--text-muted); }
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

    .segmented-control {
      display: flex;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-sm);
      padding: 2px;
      gap: 2px;
    }
    .segment-btn {
      border: none;
      background: transparent;
      padding: 3px 10px;
      font-size: 11px;
      font-weight: 500;
      color: var(--text-muted);
      border-radius: var(--radius-xs);
      cursor: pointer;
      transition: all 0.1s ease;
    }
    .segment-btn.active {
      background-color: var(--bg-surface);
      color: var(--text-main);
      box-shadow: var(--shadow-sm);
    }

    /* Grid */
    .projects-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: 16px;
    }

    .project-card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      cursor: pointer;
      transition: all 0.12s ease;
    }
    .project-card:hover {
      border-color: var(--primary);
      transform: translateY(-1px);
      box-shadow: var(--shadow-sm);
    }

    .card-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .project-icon-box {
      width: 32px;
      height: 32px;
      border-radius: var(--radius-sm);
      background-color: rgba(99,102,241,0.1);
      color: var(--primary);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .project-icon-box .material-symbols-outlined { font-size: 18px; }

    .card-top-right { display: flex; align-items: center; gap: 6px; }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      padding: 1px 6px;
      border-radius: 10px;
      border: 1px solid var(--border-color);
      color: var(--text-muted);
    }
    .status-pill.active { color: var(--success); border-color: rgba(16,185,129,0.3); }
    .status-dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background-color: var(--text-light);
    }
    .status-dot.active { background-color: var(--success); }

    .edit-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      border-radius: 3px;
      display: flex;
    }
    .edit-btn .material-symbols-outlined { font-size: 15px; }
    .edit-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }

    .card-content { display: flex; flex-direction: column; gap: 4px; flex: 1; }
    .project-title { font-size: 14px; font-weight: 600; color: var(--text-main); margin: 0; }
    .project-desc {
      font-size: 12px;
      color: var(--text-muted);
      line-height: 1.4;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      margin: 0;
    }

    .card-foot {
      padding-top: 8px;
      border-top: 1px solid var(--border-color);
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-size: 11px;
    }
    .foot-date { color: var(--text-muted); }
    .view-tasks-link { color: var(--primary); font-weight: 500; }

    .empty-projects-cell {
      grid-column: 1 / -1;
      padding: 40px;
      text-align: center;
      color: var(--text-muted);
    }
    .empty-projects-cell .icon { font-size: 36px; color: var(--text-light); margin-bottom: 6px; }

    /* Modals */
    .modal-form { display: flex; flex-direction: column; gap: 12px; }
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

    .tabular-nums { font-variant-numeric: tabular-nums; }
  `]
})
export class ProjectsComponent implements OnInit {
  readonly projects = signal<Project[]>([]);
  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);

  searchQuery = '';
  selectedState = 'all';

  isCreateSubmitted = false;
  isEditSubmitted = false;

  readonly isCreateModalOpen = signal<boolean>(false);
  readonly isEditModalOpen = signal<boolean>(false);

  createForm = { name: '', description: '' };
  editForm = { name: '', description: '', state: 'A' };
  editingProject: Project | null = null;

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService,
    private router: Router
  ) {}

  ngOnInit() {
    this.loadProjects();
  }

  canCreateProject(): boolean {
    return this.permService.canCreate('tasks.projects') || this.permService.canCreate('tasks');
  }

  canUpdateProject(): boolean {
    return this.permService.canUpdate('tasks.projects') || this.permService.canUpdate('tasks');
  }

  loadProjects() {
    this.isLoading.set(true);
    this.api.get<Project[]>('/tasks/projects').subscribe({
      next: res => {
        this.isLoading.set(false);
        this.projects.set(res || []);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
  }

  filteredProjects(): Project[] {
    const q = this.searchQuery.trim().toLowerCase();
    const st = this.selectedState;

    return this.projects().filter(p => {
      const matchSearch = !q || p.name.toLowerCase().includes(q) || (p.description && p.description.toLowerCase().includes(q));
      const matchState = st === 'all' || p.state === st;
      return matchSearch && matchState;
    });
  }

  openCreateModal() {
    this.isCreateSubmitted = false;
    this.createForm = { name: '', description: '' };
    this.isCreateModalOpen.set(true);
  }

  submitCreateProject() {
    this.isCreateSubmitted = true;
    if (!this.createForm.name.trim()) {
      this.toast.warning('Введите название проекта');
      return;
    }

    this.isSubmitting.set(true);
    this.api.post<Project>('/tasks/projects', {
      name: this.createForm.name.trim(),
      description: this.createForm.description.trim()
    }).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isCreateModalOpen.set(false);
        this.toast.success('Проект успешно создан');
        this.loadProjects();
      },
      error: err => {
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || 'Ошибка при сохранении проекта');
      }
    });
  }

  openEditModal(p: Project) {
    this.isEditSubmitted = false;
    this.editingProject = p;
    this.editForm = {
      name: p.name,
      description: p.description || '',
      state: p.state
    };
    this.isEditModalOpen.set(true);
  }

  submitEditProject() {
    if (!this.editingProject) return;
    this.isEditSubmitted = true;
    if (!this.editForm.name.trim()) {
      this.toast.warning('Название проекта обязательно');
      return;
    }

    this.isSubmitting.set(true);
    this.api.patch(`/tasks/projects/${this.editingProject.id}`, {
      name: this.editForm.name.trim(),
      description: this.editForm.description.trim(),
      state: this.editForm.state
    }).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.isEditModalOpen.set(false);
        this.toast.success('Проект обновлен');
        this.loadProjects();
      },
      error: err => {
        this.isSubmitting.set(false);
        this.toast.error(err.error?.message || 'Ошибка при обновлении проекта');
      }
    });
  }

  viewProjectTasks(project: Project) {
    this.router.navigate(['/tasks'], { queryParams: { project_id: project.id } });
  }
}
