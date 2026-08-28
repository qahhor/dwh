import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { Project, ProjectMember } from '../../../core/models/task.models';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [CommonModule, FormsModule, UiButtonComponent, UiBadgeComponent, UiModalComponent],
  template: `
    <div class="projects-container">
      <div class="page-header">
        <div>
          <h2 class="page-title">Проекты</h2>
          <p class="page-subtitle">Командные пространства и структуры проектов</p>
        </div>
        <ui-button
          *ngIf="permService.canCreate('ms_tasks')"
          variant="primary"
          icon="create_new_folder"
          (onClick)="openCreateModal()"
        >
          Создать проект
        </ui-button>
      </div>

      <!-- Projects Grid -->
      <div class="projects-grid">
        <div *ngFor="let p of projects()" class="card project-card" (click)="openProjectDetails(p)">
          <div class="project-header">
            <div class="project-icon-box">
              <span class="material-symbols-outlined">folder</span>
            </div>
            <ui-badge [variant]="p.state === 'A' ? 'active' : 'passive'" [dot]="true">
              {{ p.state === 'A' ? 'Активен' : 'Архивирован' }}
            </ui-badge>
          </div>

          <h3 class="project-name">{{ p.name }}</h3>
          <p class="project-desc">{{ p.description || 'Описание проекта не указано' }}</p>

          <div class="project-footer">
            <span class="project-date text-muted tabular-nums">Создан: {{ p.createdAt | date:'dd.MM.yyyy' }}</span>
          </div>
        </div>

        <div *ngIf="projects().length === 0" class="empty-projects">
          Проектов пока нет
        </div>
      </div>
    </div>

    <!-- Create Project Modal -->
    <ui-modal
      [isOpen]="isCreateModalOpen()"
      title="Создание нового проекта"
      size="sm"
      (close)="isCreateModalOpen.set(false)"
    >
      <div body class="modal-form">
        <div class="form-group">
          <label class="form-label">Название проекта <span class="req">*</span></label>
          <input type="text" class="form-input" [(ngModel)]="createForm.name" placeholder="Например: Внедрение DWH" />
        </div>
        <div class="form-group">
          <label class="form-label">Описание</label>
          <textarea class="form-input" rows="3" [(ngModel)]="createForm.description" placeholder="Цели и задачи проекта..."></textarea>
        </div>
      </div>
      <div footer>
        <ui-button variant="secondary" size="md" (onClick)="isCreateModalOpen.set(false)">Отмена</ui-button>
        <ui-button variant="primary" size="md" (onClick)="submitCreateProject()">Создать</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .projects-container {
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

    .projects-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: 16px;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 18px;
    }

    .project-card {
      display: flex;
      flex-direction: column;
      gap: 10px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .project-card:hover {
      border-color: var(--primary);
      transform: translateY(-2px);
      box-shadow: var(--shadow-sm);
    }

    .project-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .project-icon-box {
      width: 36px;
      height: 36px;
      border-radius: var(--radius-md);
      background-color: var(--warning-bg);
      color: var(--warning);
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .project-name {
      font-size: 15px;
      font-weight: 600;
      color: var(--text-main);
    }

    .project-desc {
      font-size: 13px;
      color: var(--text-muted);
      line-height: 1.4;
      flex: 1;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .project-footer {
      padding-top: 10px;
      border-top: 1px solid var(--border-color);
      display: flex;
      justify-content: space-between;
      font-size: 11px;
    }

    .empty-projects {
      grid-column: 1 / -1;
      padding: 40px;
      text-align: center;
      color: var(--text-muted);
    }

    .modal-form {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

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
      outline: none;
    }
  `]
})
export class ProjectsComponent implements OnInit {
  readonly projects = signal<Project[]>([]);
  readonly isCreateModalOpen = signal<boolean>(false);
  createForm = { name: '', description: '' };

  constructor(
    public permService: PermissionService,
    private api: ApiService,
    private toast: ToastService
  ) {}

  ngOnInit() {
    this.loadProjects();
  }

  loadProjects() {
    this.api.get<Project[]>('/tasks/projects').subscribe(res => {
      this.projects.set(res || []);
    });
  }

  openCreateModal() {
    this.createForm = { name: '', description: '' };
    this.isCreateModalOpen.set(true);
  }

  submitCreateProject() {
    if (!this.createForm.name.trim()) return;

    this.api.post('/tasks/projects', this.createForm).subscribe(() => {
      this.isCreateModalOpen.set(false);
      this.toast.success('Проект успешно создан');
      this.loadProjects();
    });
  }

  openProjectDetails(project: Project) {
    // Project details modal
  }
}
