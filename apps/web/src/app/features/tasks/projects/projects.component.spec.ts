import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectsComponent } from './projects.component';

describe('ProjectsComponent UI contracts', () => {
  async function createFixture(api: Record<string, unknown> = {
    get: vi.fn(() => of([])),
    post: vi.fn(() => of({})),
    patch: vi.fn(() => of({}))
  }) {
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: { canCreate: () => true, canUpdate: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
        { provide: Router, useValue: { navigate: vi.fn() } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('labels filters and keeps the projects table inside a named scroll region', async () => {
    const fixture = await createFixture();

    const search = fixture.nativeElement.querySelector('#project-search') as HTMLInputElement;
    const region = fixture.nativeElement.querySelector('.table-wrapper[role="region"]') as HTMLElement;

    expect(fixture.nativeElement.querySelector(`label[for="${search.id}"]`)).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="group"][aria-label="Режим отображения проектов"]')).not.toBeNull();
    expect(region.tabIndex).toBe(0);
    expect(region.querySelector('table')?.getAttribute('aria-label')).toBe('Список проектов');
  });

  it('connects project modal labels, required state and validation message', async () => {
    const fixture = await createFixture();
    fixture.componentInstance.openCreateModal();
    fixture.componentInstance.isCreateSubmitted = true;
    fixture.detectChanges();

    const name = fixture.nativeElement.querySelector('#project-create-name') as HTMLInputElement;
    const error = fixture.nativeElement.querySelector('#project-create-name-error') as HTMLElement;

    expect(fixture.nativeElement.querySelector(`label[for="${name.id}"]`)).not.toBeNull();
    expect(name.required).toBe(true);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe(error.id);
    expect(fixture.nativeElement.querySelector('#project-create-description')).not.toBeNull();
  });

  it('reveals a newly created project even when it belongs on a later page', async () => {
    const existing = Array.from({ length: 10 }, (_, index) => ({
      id: index + 1,
      name: `Project ${index + 1}`,
      state: 'A' as const,
      createdAt: '2026-08-30T00:00:00Z'
    }));
    const created = {
      id: 11,
      name: 'Newly created project',
      state: 'A' as const,
      createdAt: '2026-08-30T00:00:00Z'
    };
    let wasCreated = false;
    const api = {
      get: vi.fn((url: string) => of(url.endsWith('/stats') ? [] : wasCreated ? [...existing, created] : existing)),
      post: vi.fn(() => {
        wasCreated = true;
        return of(created);
      }),
      patch: vi.fn(() => of({}))
    };
    const fixture = await createFixture(api);
    const component = fixture.componentInstance;
    component.searchQuery = 'old filter';
    component.selectedState = 'P';
    component.openCreateModal();
    component.createForm = { name: created.name, description: '' };

    component.submitCreateProject();

    expect(component.searchQuery).toBe('');
    expect(component.selectedState).toBe('all');
    expect(component.currentPage).toBe(2);
    expect(component.paginatedProjects().map(project => project.id)).toContain(created.id);
  });
});
