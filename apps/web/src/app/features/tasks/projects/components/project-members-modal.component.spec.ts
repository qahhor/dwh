import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../../../core/services/api.service';
import { ProjectMember } from '../projects.models';
import { ProjectMembersModalComponent } from './project-members-modal.component';

const members: ProjectMember[] = [
  { projectId: 7, userId: 1, userName: 'Ольга Петрова', userEmail: 'olga@example.test', accessKind: 'MEMBER' },
  { projectId: 7, userId: 2, userName: 'Андрей Ким', userEmail: '', accessKind: 'MANAGER' },
  { projectId: 7, userId: 3, userName: 'Бахром Алиев', userEmail: 'bahrom@example.test', accessKind: 'AUDITOR' }
];

async function createFixture(canUpdateProject: boolean, list: ProjectMember[] = members) {
  await TestBed.configureTestingModule({
    imports: [ProjectMembersModalComponent],
    providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [] })) } }]
  }).compileComponents();
  const fixture = TestBed.createComponent(ProjectMembersModalComponent);
  const component = fixture.componentInstance;
  component.isOpen = true;
  component.project = { id: 7, name: 'Склад' } as never;
  component.members = list;
  component.canUpdateProject = canUpdateProject;
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

function table(root: HTMLElement): HTMLElement {
  return root.querySelector('[data-testid="project-members-table"]') as HTMLElement;
}

function rowTexts(root: HTMLElement): string[][] {
  return [...table(root).querySelectorAll('[role="rowgroup"] > [role="row"]')].map(row =>
    [...row.querySelectorAll('[role="cell"]')].map(cell => (cell.textContent ?? '').replace(/\s+/g, ' ').trim()));
}

describe('ProjectMembersModalComponent', () => {
  it('lists every member with the role named and an unknown role shown as it came', async () => {
    const fixture = await createFixture(true);
    const root = document.body;

    expect(table(root).querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Участники проекта');
    const rows = rowTexts(root);
    expect(rows.map(row => row[0])).toEqual(['ОПОльга Петрова', 'АКАндрей Ким', 'БАБахром Алиев']);
    expect(rows.map(row => row[2])).toEqual(['Участник (Member)', 'Руководитель (Manager)', 'AUDITOR']);
    expect(rows[1][1]).toBe('—');
    fixture.destroy();
  });

  it('names each remove button after its member and asks before removing', async () => {
    const fixture = await createFixture(true);
    const buttons = [...table(document.body).querySelectorAll<HTMLButtonElement>('button')];

    expect(buttons.map(button => button.getAttribute('aria-label'))).toEqual([
      'Удалить Ольга Петрова из проекта',
      'Удалить Андрей Ким из проекта',
      'Удалить Бахром Алиев из проекта'
    ]);
    const removed = vi.fn();
    fixture.componentInstance.removeMember.subscribe(removed);
    buttons[1].click();
    expect(fixture.componentInstance.memberToRemove?.userId).toBe(2);
    expect(removed).not.toHaveBeenCalled();

    fixture.componentInstance.confirmRemove();
    expect(removed).toHaveBeenCalledWith({ projectId: 7, userId: 2 });
    fixture.destroy();
  });

  it('hides the remove column from someone who may not change the project', async () => {
    const fixture = await createFixture(false);
    const root = document.body;

    expect(table(root).querySelectorAll('[role="columnheader"]')).toHaveLength(3);
    expect(table(root).querySelector('button')).toBeNull();
    fixture.destroy();
  });

  it('shows the empty state when the project has no members', async () => {
    const fixture = await createFixture(true, []);

    expect(table(document.body).textContent).toContain('В проекте пока нет участников.');
    fixture.destroy();
  });
});
