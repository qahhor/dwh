import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../core/services/api.service';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { ProjectsComponent } from './projects.component';

describe('ProjectsComponent UI contracts', () => {
  async function createFixture() {
    await TestBed.configureTestingModule({
      imports: [ProjectsComponent],
      providers: [
        { provide: ApiService, useValue: { get: vi.fn(() => of([])), post: vi.fn(() => of({})), patch: vi.fn(() => of({})) } },
        { provide: PermissionService, useValue: { canCreate: () => true, canUpdate: () => true } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
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
    expect(fixture.nativeElement.querySelector('.view-switcher[role="group"]')).not.toBeNull();
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
});
