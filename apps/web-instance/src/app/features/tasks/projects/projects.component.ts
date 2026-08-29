import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule, Router } from '@angular/router';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiButtonComponent } from '../../../shared/ui/ui-button.component';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiPaginationComponent } from '../../../shared/ui/ui-pagination.component';
import { Project, ProjectTaskStats } from '../../../core/models/task.models';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, UiButtonComponent, UiModalComponent, UiPaginationComponent],

  template: `
    <div class="projects-page">
      <!-- Header -->
      <div class="view-header">
        <div class="header-left">
          <h1 class="view-title">Проекты</h1>
          <span class="proj-count">{{ filteredProjects().length }}</span>

          <!-- View Mode Switcher -->
          <div class="view-switcher">
            <button
              type="button"
              class="view-btn"
              [class.active]="viewMode === 'list'"
              (click)="viewMode = 'list'"
              title="Список / Таблица"
            >
              <span class="material-symbols-outlined">table_rows</span>
              <span>Список</span>
            </button>
            <button
              type="button"
              class="view-btn"
              [class.active]="viewMode === 'cards'"
              (click)="viewMode = 'cards'"
              title="Карточки"
            >
              <span class="material-symbols-outlined">grid_view</span>
              <span>Карточки</span>
            </button>
          </div>
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
            placeholder="Поиск по названию или описанию..."
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

      <!-- ======================================================================= -->
      <!-- VIEW 1: TABLE / LIST VIEW (Default)                                     -->
      <!-- ======================================================================= -->
      <div class="table-card" *ngIf="viewMode === 'list'">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 60px;">ID</th>
                <th>Проект</th>
                <th style="width: 110px;">Статус</th>
                <th style="width: 220px;">Прогресс задач</th>
                <th style="width: 120px;">Создан</th>
                <th class="text-right" style="width: 140px;">Действия</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let p of paginatedProjects()" class="project-row" (click)="viewProjectTasks(p)">
                <td class="tabular-nums font-mono text-muted">#{{ p.id }}</td>
                <td>
                  <div class="project-title-cell">
                    <span class="material-symbols-outlined folder-icon">folder</span>
                    <div class="project-info-group">
                      <span class="project-name">{{ p.name }}</span>
                      <span *ngIf="p.description" class="project-desc-line">{{ p.description }}</span>
                    </div>
                  </div>
                </td>
                <td>
                  <span class="status-pill" [class.active]="p.state === 'A'">
                    <span class="status-dot" [class.active]="p.state === 'A'"></span>
                    {{ p.state === 'A' ? 'Активен' : 'Архив' }}
                  </span>
                </td>
                <td>
                  <div class="progress-cell">
                    <div class="progress-labels">
                      <span class="progress-count tabular-nums">
                        {{ getProjectDoneCount(p.id) }} / {{ getProjectTotalCount(p.id) }} готово
                      </span>
                      <span class="progress-percent tabular-nums">
                        {{ getProjectPercent(p.id) }}%
                      </span>
                    </div>
                    <div class="progress-bar-bg">
                      <div
                        class="progress-bar-fill"
                        [style.width.%]="getProjectPercent(p.id)"
                        [class.complete]="getProjectPercent(p.id) === 100 && getProjectTotalCount(p.id) > 0"
                      ></div>
                    </div>
                  </div>
                </td>
                <td>
                  <span class="tabular-nums text-muted text-xs">
                    {{ p.createdAt | date:'dd.MM.yyyy' }}
                  </span>
                </td>
                <td class="text-right" (click)="$event.stopPropagation()">
                  <div class="row-action-btns">
                    <button
                      type="button"
                      class="action-link-btn"
                      title="Перейти к задачам проекта"
                      (click)="viewProjectTasks(p)"
                    >
                      <span class="material-symbols-outlined">task_alt</span>
                      Задачи
                    </button>
                    <button
                      *ngIf="canUpdateProject()"
                      type="button"
                      class="icon-ghost-btn"
                      title="Редактировать проект"
                      (click)="openEditModal(p)"
                    >
                      <span class="material-symbols-outlined">edit</span>
                    </button>
                  </div>
                </td>
              </tr>

              <tr *ngIf="filteredProjects().length === 0 && !isLoading()">
                <td colspan="6" class="empty-state-cell">
                  <span class="material-symbols-outlined empty-icon">folder_off</span>
                  <p>Проекты не найдены</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <ui-pagination
          [totalItems]="filteredProjects().length"
          [currentPage]="currentPage"
          [pageSize]="pageSize"
          (pageChange)="currentPage = $event"
          (pageSizeChange)="pageSize = $event; currentPage = 1"
        ></ui-pagination>
      </div>

      <!-- ======================================================================= -->
      <!-- VIEW 2: CARDS GRID VIEW                                                 -->
      <!-- ======================================================================= -->
      <div class="cards-view-wrapper" *ngIf="viewMode === 'cards'">
        <div class="projects-grid">
          <div
            *ngFor="let p of paginatedProjects()"
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

            <div class="card-progress">
              <div class="progress-labels">
                <span class="progress-count tabular-nums">
                  {{ getProjectDoneCount(p.id) }} / {{ getProjectTotalCount(p.id) }} выполнено
                </span>
                <span class="progress-percent tabular-nums">
                  {{ getProjectPercent(p.id) }}%
                </span>
              </div>
              <div class="progress-bar-bg">
                <div
                  class="progress-bar-fill"
                  [style.width.%]="getProjectPercent(p.id)"
                  [class.complete]="getProjectPercent(p.id) === 100 && getProjectTotalCount(p.id) > 0"
                ></div>
              </div>
            </div>

            <div class="card-foot">
              <span class="foot-date tabular-nums">Создан: {{ p.createdAt | date:'dd.MM.yyyy' }}</span>
              <span class="view-tasks-link">
                Задачи ({{ getProjectTotalCount(p.id) }}) →
              </span>
            </div>
          </div>

          <div *ngIf="filteredProjects().length === 0 && !isLoading()" class="empty-projects-cell">
            <span class="material-symbols-outlined empty-icon">folder_off</span>
            <p>Проекты не найдены</p>
          </div>
        </div>

        <ui-pagination
          [totalItems]="filteredProjects().length"
          [currentPage]="currentPage"
          [pageSize]="pageSize"
          (pageChange)="currentPage = $event"
          (pageSizeChange)="pageSize = $event; currentPage = 1"
        ></ui-pagination>
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
        <ui-button variant="primary" size="md" [loading]="isSubmitting()" (onClick)="submitCreateProject()">Создать проект</ui-button>
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
    .header-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
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

    /* Table Styles */
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
    .project-row {
      cursor: pointer;
      transition: background 0.1s ease;
    }
    .project-row:hover { background-color: var(--bg-hover); }
    .project-row:last-child td { border-bottom: none; }

    .project-title-cell {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .folder-icon { font-size: 20px; color: var(--warning); flex-shrink: 0; }
    .project-info-group { display: flex; flex-direction: column; gap: 2px; }
    .project-name { font-weight: 600; color: var(--text-main); }
    .project-desc-line {
      font-size: 11px;
      color: var(--text-muted);
      max-width: 400px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .status-pill {
      font-size: 11px;
      font-weight: 500;
      padding: 2px 7px;
      border-radius: 10px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      background-color: var(--bg-hover);
      color: var(--text-muted);
    }
    .status-pill.active { background-color: rgba(16,185,129,0.1); color: var(--success); }
    .status-dot { width: 5px; height: 5px; border-radius: 50%; background-color: var(--text-muted); }
    .status-dot.active { background-color: var(--success); }

    /* Progress Cell */
    .progress-cell { display: flex; flex-direction: column; gap: 4px; }
    .progress-labels { display: flex; justify-content: space-between; font-size: 11px; }
    .progress-count { color: var(--text-muted); font-size: 10px; }
    .progress-percent { font-weight: 600; color: var(--text-main); font-size: 10px; }
    .progress-bar-bg {
      height: 5px;
      background-color: var(--bg-hover);
      border-radius: 3px;
      overflow: hidden;
      border: 1px solid var(--border-color);
    }
    .progress-bar-fill {
      height: 100%;
      background-color: var(--primary);
      border-radius: 2px;
      transition: width 0.3s ease;
    }
    .progress-bar-fill.complete { background-color: var(--success); }

    .row-action-btns { display: inline-flex; align-items: center; gap: 6px; }
    .action-link-btn {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 8px;
      border-radius: var(--radius-xs);
      border: 1px solid var(--border-color);
      background-color: var(--bg-hover);
      color: var(--text-main);
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
    }
    .action-link-btn:hover { border-color: var(--primary); color: var(--primary); }
    .action-link-btn .material-symbols-outlined { font-size: 14px; }

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
    .icon-ghost-btn .material-symbols-outlined { font-size: 16px; }

    .empty-state-cell {
      padding: 40px;
      text-align: center;
      color: var(--text-muted);
    }
    .empty-icon { font-size: 36px; color: var(--text-light); margin-bottom: 6px; }

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
      background-color: rgba(245, 158, 11, 0.12);
      color: var(--warning);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .project-icon-box .material-symbols-outlined { font-size: 18px; }
    .card-top-right { display: flex; align-items: center; gap: 6px; }

    .edit-btn {
      border: none;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      padding: 3px;
      border-radius: 4px;
      display: flex;
    }
    .edit-btn:hover { color: var(--text-main); background-color: var(--bg-hover); }
    .edit-btn .material-symbols-outlined { font-size: 15px; }

    .card-content { display: flex; flex-direction: column; gap: 4px; }
    .project-title { font-size: 14px; font-weight: 600; margin: 0; color: var(--text-main); }
    .project-desc {
      font-size: 12px;
      color: var(--text-muted);
      margin: 0;
      line-height: 1.4;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .card-progress { display: flex; flex-direction: column; gap: 4px; padding-top: 4px; }

    .card-foot {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding-top: 8px;
      border-top: 1px solid var(--border-color);
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

    /* Modal Form */
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
    }
    .clean-input:focus { border-color: var(--primary); }
    .clean-input.input-error { border-color: var(--danger); background-color: var(--danger-bg); }
    .error-msg { font-size: 11px; color: var(--danger); margin-top: 2px; }

    .clean-textarea { height: auto; padding: 6px 8px; resize: vertical; font-family: inherit; }

    .tabular-nums { font-variant-numeric: tabular-nums; }
    .font-mono { font-family: monospace; }
    .text-right { text-align: right; }
    .text-muted { color: var(--text-muted); }
    .text-xs { font-size: 11px; }
  `]
})
export class ProjectsComponent implements OnInit {
  readonly projects = signal<Project[]>([]);
  readonly projectStats = signal<Record<number, ProjectTaskStats>>({});
  readonly isLoading = signal<boolean>(false);
  readonly isSubmitting = signal<boolean>(false);

  viewMode: 'list' | 'cards' = 'list';
  searchQuery = '';
  selectedState = 'all';
  currentPage = 1;
  pageSize = 10;


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
    this.loadStats();
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

  loadStats() {
    this.api.get<ProjectTaskStats[]>('/tasks/projects/stats').subscribe({
      next: res => {
        const map: Record<number, ProjectTaskStats> = {};
        for (const s of res || []) {
          map[s.projectId] = s;
        }
        this.projectStats.set(map);
      },
      error: () => {}
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

  paginatedProjects(): Project[] {
    const list = this.filteredProjects();
    const start = (this.currentPage - 1) * this.pageSize;
    return list.slice(start, start + this.pageSize);
  }


  getProjectTotalCount(projectId: number): number {
    return this.projectStats()[projectId]?.totalTasks || 0;
  }

  getProjectDoneCount(projectId: number): number {
    return this.projectStats()[projectId]?.doneTasks || 0;
  }

  getProjectPercent(projectId: number): number {
    const total = this.getProjectTotalCount(projectId);
    if (total === 0) return 0;
    const done = this.getProjectDoneCount(projectId);
    return Math.round((done / total) * 100);
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
        this.loadStats();
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
        this.loadStats();
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
