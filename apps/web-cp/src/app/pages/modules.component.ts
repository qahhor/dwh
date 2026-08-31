import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CpApiService } from '../core/cp-api.service';
import { UiModalComponent } from '../shared/ui/ui-modal.component';

@Component({
  selector: 'cp-modules',
  standalone: true,
  imports: [CommonModule, FormsModule, UiModalComponent],
  template: `
    <div class="view-header">
      <div class="header-left">
        <h1 class="view-title">Модерация пользовательских модулей</h1>
        <span class="count-badge">{{ modules().length }} заявок</span>
      </div>
      <div class="header-right">
        <button type="button" class="btn btn-secondary" (click)="loadModules()">
          <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          <span>Обновить</span>
        </button>
      </div>
    </div>

    <!-- Toolbar Filters -->
    <div class="toolbar">
      <div class="status-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          class="status-tab"
          [class.active]="filter() === 'ALL'"
          (click)="filter.set('ALL')"
        >
          <span>Все ({{ modules().length }})</span>
        </button>
        <button
          type="button"
          role="tab"
          class="status-tab"
          [class.active]="filter() === 'PENDING_APPROVAL'"
          (click)="filter.set('PENDING_APPROVAL')"
        >
          <span class="status-tab-dot" style="background: var(--warning);"></span>
          <span>На рассмотрении ({{ countPending() }})</span>
        </button>
        <button
          type="button"
          role="tab"
          class="status-tab"
          [class.active]="filter() === 'APPROVED'"
          (click)="filter.set('APPROVED')"
        >
          <span class="status-tab-dot" style="background: var(--success);"></span>
          <span>Одобренные ({{ countApproved() }})</span>
        </button>
        <button
          type="button"
          role="tab"
          class="status-tab"
          [class.active]="filter() === 'REJECTED'"
          (click)="filter.set('REJECTED')"
        >
          <span class="status-tab-dot" style="background: var(--danger);"></span>
          <span>Отклоненные</span>
        </button>
      </div>
    </div>

    <!-- Table of Module Submissions -->
    <div class="table-card">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Клиент / Инстанс</th>
              <th>Модуль</th>
              <th>Версия</th>
              <th>Точка входа (Microfrontend)</th>
              <th>Статус</th>
              <th>Дата подачи</th>
              <th style="text-align: right;">Модерация</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let m of filteredModules()">
              <td>
                <span class="badge badge-neutral mono">{{ m.clientCode }}</span>
                <span class="text-xs text-muted" style="margin-left: 6px;">#{{ m.instanceId }}</span>
              </td>
              <td>
                <div class="cell-entity">
                  <span class="material-symbols-outlined entity-icon" aria-hidden="true">{{ m.icon || 'extension' }}</span>
                  <div>
                    <div class="entity-title font-medium">{{ m.name }}</div>
                    <div class="entity-sub text-muted font-mono">{{ m.moduleCode }}</div>
                  </div>
                </div>
              </td>
              <td><span class="mono">{{ m.version }}</span></td>
              <td><a [href]="m.entrypointUrl" target="_blank" rel="noopener noreferrer" class="link mono text-xs">{{ m.entrypointUrl }}</a></td>
              <td>
                <span class="badge badge-warning" *ngIf="m.status === 'PENDING_APPROVAL'">На модерации</span>
                <span class="badge badge-active" *ngIf="m.status === 'APPROVED'">Одобрен</span>
                <span class="badge badge-danger" *ngIf="m.status === 'REJECTED'" [title]="m.moderationNotes || ''">Отклонен</span>
              </td>
              <td class="text-muted text-xs">{{ m.createdAt | date:'dd.MM.yyyy HH:mm' }}</td>
              <td style="text-align: right;">
                <div class="table-actions-right" *ngIf="m.status === 'PENDING_APPROVAL'">
                  <button type="button" class="btn btn-primary btn-sm" (click)="openDecisionModal(m, 'approve')">
                    <span class="material-symbols-outlined" aria-hidden="true">check</span>
                    <span>Одобрить</span>
                  </button>
                  <button type="button" class="btn btn-danger btn-sm" (click)="openDecisionModal(m, 'reject')">
                    <span class="material-symbols-outlined" aria-hidden="true">close</span>
                    <span>Отклонить</span>
                  </button>
                </div>
                <div *ngIf="m.status !== 'PENDING_APPROVAL'" class="text-xs text-muted">
                  {{ m.reviewedBy ? 'Проверил: ' + m.reviewedBy : 'Завершено' }}
                </div>
              </td>
            </tr>
            <tr *ngIf="filteredModules().length === 0">
              <td colspan="7" class="text-center py-6 text-muted">
                Заявок на модерацию в данной категории нет
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Moderation Decision Modal -->
    <ui-modal
      *ngIf="isDecisionModalOpen()"
      [title]="decisionAction === 'approve' ? 'Подтверждение одобрения модуля' : 'Отклонение модуля'"
      ariaLabel="Решение по модулю"
      (close)="isDecisionModalOpen.set(false)"
    >
      <div class="form-group" style="margin-bottom: 12px;">
        <p class="text-sm">
          Модуль: <strong>{{ selectedModule()?.name }}</strong> ({{ selectedModule()?.moduleCode }}) для клиента <strong>{{ selectedModule()?.clientCode }}</strong>.
        </p>
      </div>

      <div class="form-group">
        <label class="form-label" for="decision-notes">Примечание модератора</label>
        <textarea
          id="decision-notes"
          class="form-input"
          rows="3"
          [(ngModel)]="decisionNotes"
          placeholder="Укажите комментарий или причину отклонения..."
        ></textarea>
      </div>

      <div modal-footer class="modal-footer-btns">
        <button type="button" class="btn btn-secondary" (click)="isDecisionModalOpen.set(false)">Отмена</button>
        <button
          type="button"
          class="btn"
          [class.btn-primary]="decisionAction === 'approve'"
          [class.btn-danger]="decisionAction === 'reject'"
          (click)="submitDecision()"
        >
          {{ decisionAction === 'approve' ? 'Подтвердить и Одобрить' : 'Отклонить заявку' }}
        </button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .cell-entity {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .entity-icon {
      font-size: 22px;
      color: var(--primary);
    }
    .entity-title { font-size: 13px; color: var(--text-main); }
    .entity-sub { font-size: 11px; }
    .link { color: var(--primary); text-decoration: none; }
    .link:hover { text-decoration: underline; }
  `]
})
export class ModulesComponent implements OnInit {
  private api = inject(CpApiService);

  readonly modules = signal<any[]>([]);
  readonly filter = signal<'ALL' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'>('ALL');

  readonly isDecisionModalOpen = signal<boolean>(false);
  readonly selectedModule = signal<any>(null);
  decisionAction: 'approve' | 'reject' = 'approve';
  decisionNotes = '';

  ngOnInit() {
    this.loadModules();
  }

  async loadModules() {
    try {
      const list = await this.api.modules();
      this.modules.set(list || []);
    } catch {
      this.modules.set([]);
    }
  }

  filteredModules() {
    const f = this.filter();
    if (f === 'ALL') return this.modules();
    return this.modules().filter(m => m.status === f);
  }

  countPending(): number {
    return this.modules().filter(m => m.status === 'PENDING_APPROVAL').length;
  }

  countApproved(): number {
    return this.modules().filter(m => m.status === 'APPROVED').length;
  }

  openDecisionModal(m: any, action: 'approve' | 'reject') {
    this.selectedModule.set(m);
    this.decisionAction = action;
    this.decisionNotes = action === 'approve' ? 'Одобрено модератором CP' : 'Не соответствует требованиям безопасности';
    this.isDecisionModalOpen.set(true);
  }

  async submitDecision() {
    const m = this.selectedModule();
    if (!m) return;

    try {
      if (this.decisionAction === 'approve') {
        await this.api.approveModule(m.id, this.decisionNotes);
      } else {
        await this.api.rejectModule(m.id, this.decisionNotes);
      }
      this.isDecisionModalOpen.set(false);
      await this.loadModules();
    } catch (e) {
      console.error(e);
    }
  }
}
