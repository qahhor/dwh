import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { Task } from '../../core/models/task.models';
import { TasksComponent } from './tasks.component';

describe('TasksComponent UI contracts', () => {
  async function createFixture() {
    const api = {
      get: vi.fn((path: string) => of(path === '/tasks'
        ? { items: [], nextCursor: null, hasMore: false }
        : [])),
      post: vi.fn(() => of({})),
      patch: vi.fn(() => of({})),
      delete: vi.fn(() => of({}))
    };
    await TestBed.configureTestingModule({
      imports: [TasksComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: { canCreate: () => true, canUpdate: () => true, canDelete: () => true, hasPermission: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(TasksComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('labels filters, view controls and the task table interaction', async () => {
    const fixture = await createFixture();
    const task: Task = {
      id: 42,
      title: 'Проверить отчёт',
      statusId: 1,
      priority: 'high',
      attributes: {},
      createdAt: '2026-08-30T00:00:00Z'
    };
    fixture.componentInstance.tasks.set([task]);
    fixture.detectChanges();

    const search = fixture.nativeElement.querySelector('#task-search') as HTMLInputElement;
    const region = fixture.nativeElement.querySelector('.table-wrapper[role="region"]') as HTMLElement;
    const row = fixture.nativeElement.querySelector('tr.task-row') as HTMLTableRowElement;

    expect(fixture.nativeElement.querySelector(`label[for="${search.id}"]`)).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="group"][aria-label="Режим отображения задач"]')).not.toBeNull();
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('table')?.getAttribute('aria-label')).toBe('Список задач');
    expect(row.getAttribute('role')).toBe('button');
    expect(row.tabIndex).toBe(0);
    expect(fixture.nativeElement.querySelector('button[aria-label="Редактировать задачу #42"]')).not.toBeNull();
  });

  it('connects create-task labels, required state and shared field names', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateTaskModal();
    fixture.componentInstance.isCreateSubmitted = true;
    fixture.detectChanges();

    const title = fixture.nativeElement.querySelector('#task-create-title') as HTMLInputElement;
    const error = fixture.nativeElement.querySelector('#task-create-title-error') as HTMLElement;

    expect(fixture.nativeElement.querySelector(`label[for="${title.id}"]`)).not.toBeNull();
    expect(title.required).toBe(true);
    expect(title.getAttribute('aria-invalid')).toBe('true');
    expect(title.getAttribute('aria-describedby')).toBe(error.id);
    expect(fixture.nativeElement.querySelector('[role="group"][aria-label="Тип задачи"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('ui-searchable-select button[aria-label="Родительская задача"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('ui-user-multi-select button[aria-label="Наблюдатели"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('ui-markdown-editor textarea')?.getAttribute('id')).not.toBe('');
  });

  it('labels task dictionaries and confirms destructive actions in-app', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.taskTypes.set([{
      id: 5,
      code: 'review',
      name: 'Проверка',
      icon: 'fact_check',
      color: '#6366f1',
      orderNo: 10,
      isSystem: false
    }]);
    fixture.componentInstance.openSettingsModal();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="tablist"][aria-label="Справочники задач"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="task-type-code"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="task-type-name"]')).not.toBeNull();

    const remove = fixture.nativeElement.querySelector('button[aria-label="Удалить тип задачи Проверка"]') as HTMLButtonElement;
    remove.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Удалить тип задачи «Проверка»?');
  });

  it('renders each subtask action as a native named button', async () => {
    const fixture = await createFixture();
    const parent: Task = {
      id: 10,
      title: 'Родительская задача',
      statusId: 1,
      priority: 'medium',
      attributes: {},
      createdAt: '2026-08-30T00:00:00Z'
    };
    const subtask: Task = {
      ...parent,
      id: 11,
      title: 'Проверить подзадачу'
    };
    fixture.componentInstance.selectedTask.set(parent);
    fixture.componentInstance.taskSubtasks.set([subtask]);
    fixture.detectChanges();

    const action = fixture.nativeElement.querySelector('.subtask-row') as HTMLButtonElement;
    expect(action.tagName).toBe('BUTTON');
    expect(action.type).toBe('button');
    expect(action.getAttribute('aria-label')).toBe('Открыть подзадачу #11: Проверить подзадачу');
  });
});
