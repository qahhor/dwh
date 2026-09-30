import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { ApiService } from '@core/services/api.service';
import { ProjectMember } from '../projects.models';
import { ProjectMembersModalComponent } from './project-members-modal.component';

const members: ProjectMember[] = [
  { projectId: 7, userId: 1, userName: 'Ольга Петрова', userEmail: 'olga@example.test', accessKind: 'MEMBER' },
  { projectId: 7, userId: 2, userName: 'Андрей Ким', userEmail: '', accessKind: 'MANAGER' },
  { projectId: 7, userId: 3, userName: 'Бахром Алиев', userEmail: 'bahrom@example.test', accessKind: 'AUDITOR' },
];

async function createFixture(
  canUpdateProject: boolean,
  list: ProjectMember[] = members,
  get: (url: string, params: { search: string }) => Observable<unknown> = () => of({ items: [] }),
) {
  await TestBed.configureTestingModule({
    imports: [ProjectMembersModalComponent],
    providers: [{ provide: ApiService, useValue: { get } }],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProjectMembersModalComponent);
  fixture.componentRef.setInput('isOpen', true);
  fixture.componentRef.setInput('project', { id: 7, name: 'Склад' } as never);
  fixture.componentRef.setInput('members', list);
  fixture.componentRef.setInput('canUpdateProject', canUpdateProject);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

function table(root: HTMLElement): HTMLElement {
  return root.querySelector('[data-testid="project-members-table"]') as HTMLElement;
}

function rowTexts(root: HTMLElement): string[][] {
  return [...table(root).querySelectorAll('[role="rowgroup"] > [role="row"]')].map((row) =>
    [...row.querySelectorAll('[role="cell"]')].map((cell) => (cell.textContent ?? '').replace(/\s+/g, ' ').trim()),
  );
}

describe('ProjectMembersModalComponent', () => {
  it('lists every member with the role named and an unknown role shown as it came', async () => {
    const fixture = await createFixture(true);
    const root = document.body;

    expect(table(root).querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Участники проекта');
    const rows = rowTexts(root);
    expect(rows.map((row) => row[0])).toEqual(['ОПОльга Петрова', 'АКАндрей Ким', 'БАБахром Алиев']);
    expect(rows.map((row) => row[2])).toEqual(['Участник (Member)', 'Руководитель (Manager)', 'AUDITOR']);
    expect(rows[1][1]).toBe('—');
    fixture.destroy();
  });

  it('names each remove button after its member and hands the removal to the page', async () => {
    const fixture = await createFixture(true);
    const buttons = [...table(document.body).querySelectorAll<HTMLButtonElement>('button')];

    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual([
      'Удалить Ольга Петрова из проекта',
      'Удалить Андрей Ким из проекта',
      'Удалить Бахром Алиев из проекта',
    ]);
    const removed = vi.fn();
    fixture.componentInstance.removeMember.subscribe(removed);
    buttons[1].click();
    expect(removed).toHaveBeenCalledWith({ projectId: 7, userId: 2, userName: 'Андрей Ким' });
    fixture.destroy();
  });

  it('hides the remove column from someone who may not change the project', async () => {
    const fixture = await createFixture(false);
    const root = document.body;

    expect(table(root).querySelectorAll('[role="columnheader"]')).toHaveLength(3);
    expect(table(root).querySelector('button')).toBeNull();
    fixture.destroy();
  });

  it('offers the next page of members only while more follow, and asks the page for it', async () => {
    const fixture = await createFixture(false);
    const asked = vi.fn();
    fixture.componentInstance.loadMore.subscribe(asked);
    const loadMore = () =>
      document.body.querySelector('[data-testid="project-members-load-more"]') as HTMLButtonElement | null;
    expect(loadMore()).toBeNull();

    fixture.componentRef.setInput('hasMore', true);
    fixture.detectChanges();
    loadMore()!.click();

    expect(asked).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('shows the empty state when the project has no members', async () => {
    const fixture = await createFixture(true, []);

    expect(table(document.body).textContent).toContain('В проекте пока нет участников.');
    fixture.destroy();
  });

  it('searches users after a pause, keeps searching after a failed search, and hands the pick to the page', async () => {
    const alisher = { id: 5, name: 'Алишер', login: 'alisher' } as User;
    const get = vi.fn((_url: string, params: { search: string }) =>
      params.search === 'bad' ? throwError(() => ({ status: 503 })) : of({ items: [alisher] }),
    );
    const fixture = await createFixture(true, members, get);
    const modal = fixture.componentInstance;
    const added = vi.fn();
    modal.addMember.subscribe(added);
    vi.useFakeTimers();
    try {
      const search = async (text: string) => {
        modal.onSearchInput(text);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(250);
      };

      await search('bad');
      expect(modal.foundUsers()).toEqual([]);
      expect(modal.isUserDropdownOpen()).toBe(false);
      await search('али');
      expect(get).toHaveBeenLastCalledWith('/iam/users', { state: 'A', search: 'али', limit: 15 });
      expect(modal.foundUsers()).toEqual([alisher]);
      expect(modal.isUserDropdownOpen()).toBe(true);

      modal.selectUser(alisher);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);
      modal.submitAddMember();
      expect(added).toHaveBeenCalledWith({ projectId: 7, userId: 5, accessKind: 'MEMBER' });
      expect(modal.selectedUser()).toBeNull();
      // Choosing and clearing the user fill the box without searching again.
      expect(get).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
      fixture.destroy();
    }
  });
});
