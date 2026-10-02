import { Injectable, inject } from '@angular/core';
import { AuthService } from '@core/services/auth.service';
import { TaskStatusFilter, statusFilterCode } from '../tasks.models';

/** The quick presets of the task screen. */
export type TaskPresetKey = 'all' | 'my' | 'executor' | 'observer' | 'reported' | 'overdue';

/**
 * The screen's quick filters (ADR-0016): each one is a condition of the runtime list `ms.tasks`, added to the view's
 * own filter — "active" is `terminal = false`, "mine" is `responsibleId = me`, "executor" is `executorIds in [me]`.
 */
@Injectable({
  providedIn: 'root',
})
export class TaskFilterService {
  private readonly authService = inject(AuthService, { optional: true });

  activePreset: TaskPresetKey = 'all';
  viewMode: 'table' | 'kanban' = 'table';
  searchQuery = '';
  selectedPriority = '';
  selectedProjectId: number | null = null;
  statusFilterMode: TaskStatusFilter = 'active';
  readonly pageSize = 50;
  showExportMenu = false;

  taskSearchTimer?: ReturnType<typeof setTimeout>;

  hasActiveFilters(): boolean {
    return (
      !!this.searchQuery ||
      !!this.selectedPriority ||
      this.selectedProjectId !== null ||
      this.statusFilterMode !== 'active' ||
      this.activePreset !== 'all'
    );
  }

  clearSearch(onReload: () => void): void {
    clearTimeout(this.taskSearchTimer);
    this.searchQuery = '';
    onReload();
  }

  setPreset(preset: TaskPresetKey, onReload: () => void): void {
    if (this.activePreset === preset) return;
    this.activePreset = preset;
    onReload();
  }

  setStatusFilterMode(mode: TaskStatusFilter, onReload: () => void): void {
    this.statusFilterMode = mode;
    onReload();
  }

  onProjectFilterChange(projectId: number | null, onReload: () => void): void {
    this.selectedProjectId = projectId;
    onReload();
  }

  onPriorityFilterChange(priority: string, onReload: () => void): void {
    this.selectedPriority = priority;
    onReload();
  }

  resetFilters(onReload: () => void): void {
    clearTimeout(this.taskSearchTimer);
    this.searchQuery = '';
    this.selectedPriority = '';
    this.selectedProjectId = null;
    this.statusFilterMode = 'active';
    this.activePreset = 'all';
    onReload();
  }

  /** The quick filters as conditions of the list's filter expression. */
  buildConditions(): unknown[] {
    const conditions: unknown[] = [];
    const status = statusFilterCode(this.statusFilterMode);
    if (status !== null) conditions.push(eq('statusCode', status));
    else if (this.statusFilterMode === 'active') conditions.push(eq('terminal', false));
    if (this.selectedPriority) conditions.push(eq('priority', this.selectedPriority));
    if (this.selectedProjectId) conditions.push(eq('projectId', this.selectedProjectId));

    const me = this.authService?.currentUser()?.id;
    if (this.activePreset === 'overdue') conditions.push(eq('overdue', true));
    else if (me != null) {
      if (this.activePreset === 'my') conditions.push(eq('responsibleId', me));
      else if (this.activePreset === 'executor') conditions.push({ field: 'executorIds', op: 'in', value: [me] });
      else if (this.activePreset === 'observer') conditions.push({ field: 'observerIds', op: 'in', value: [me] });
      else if (this.activePreset === 'reported') conditions.push(eq('reporterId', me));
    }
    return conditions;
  }

  cleanup(): void {
    clearTimeout(this.taskSearchTimer);
  }
}

function eq(field: string, value: unknown): unknown {
  return { field, op: 'eq', value };
}
