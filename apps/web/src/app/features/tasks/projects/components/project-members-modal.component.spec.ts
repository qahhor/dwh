import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { ApiService } from '@core/services/api.service';
import { ProjectMember } from '../projects.models';
import { ProjectMembersModalComponent } from './project-members-modal.component';

const members: ProjectMember[] = [
  { projectId: 7, userId: 1, userName: 'Ольга Петрова', userEmail: 'olga@example.test', accessKind: 'R' },
  { projectId: 7, userId: 2, userName: 'Андрей Ким', userEmail: '', accessKind: 'W' },
  { projectId: 7, userId: 3, userName: 'Бахром Алиев', userEmail: 'bahrom@example.test', accessKind: 'AUDITOR' },
];

async function createFixture(
  canUpdateProject: boolean,
  list: ProjectMember[] = members,
  get: (url: string, params: { q: string }) => Observable<unknown> = () => of({ items: [] }),
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
  it('keeps the dialog open during a pending addition', async () => {
    const fixture = await createFixture(true);
    const closed = vi.fn();
    fixture.componentInstance.closeModal.subscribe(closed);
    fixture.componentRef.setInput('isAddingMember', true);
    fixture.detectChanges();
    expect(document.body.querySelector('.smt-modal__close')).toBeNull();
    const close = document.body.querySelector('[data-testid="project-members-actions"] button') as HTMLButtonElement;
    close.click();
    expect(closed).not.toHaveBeenCalled();
    fixture.componentRef.setInput('isAddingMember', false);
    fixture.detectChanges();
    close.click();
    expect(closed).toHaveBeenCalledTimes(1);
    fixture.destroy();
  });

  it('keeps focus transitions inside the person picker untouched and ignores a pending add', async () => {
    const fixture = await createFixture(true);
    const modal = fixture.componentInstance;
    const person = { id: 5, name: 'Selected' } as User;
    modal.foundUsers.set([person]);
    modal.isUserDropdownOpen.set(true);
    fixture.detectChanges();
    const input = document.body.querySelector('#project-member-search') as HTMLInputElement;
    const result = document.body.querySelector('.user-search-wrapper .user-dropdown-list button') as HTMLButtonElement;
    input.dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: result }));
    fixture.detectChanges();
    expect(modal.userError()).toBe('');
    modal.selectUser(person);
    fixture.componentRef.setInput('isAddingMember', true);
    fixture.detectChanges();
    const added = vi.fn();
    modal.addMember.subscribe(added);
    modal.submitAddMember();
    expect(added).not.toHaveBeenCalled();
    expect(modal.selectedUser()).toEqual(person);
    fixture.destroy();
  });
  it('checks the person on blur and clears its refusal after changing the search', async () => {
    const fixture = await createFixture(true);
    const input = document.body.querySelector('#project-member-search') as HTMLInputElement;
    input.focus();
    input.blur();
    fixture.detectChanges();
    expect(input.closest('smt-control')?.textContent).toContain('Обязательное поле');
    fixture.componentRef.setInput('addErrors', { userId: 'Person refused', accessKind: 'Role refused' });
    fixture.detectChanges();
    input.value = 'Another';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.componentInstance.addErrors()['userId']).toBeUndefined();
    expect(fixture.componentInstance.addErrors()['accessKind']).toBe('Role refused');
    fixture.destroy();
  });
  it('lists every member with the role named and an unknown role shown as it came', async () => {
    const fixture = await createFixture(true);
    const root = document.body;

    expect(table(root).querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Участники проекта');
    const rows = rowTexts(root);
    expect(rows.map((row) => row[0])).toEqual(['ОПОльга Петрова', 'АКАндрей Ким', 'БАБахром Алиев']);
    expect(rows.map((row) => row[2])).toEqual(['Чтение', 'Редактирование', 'AUDITOR']);
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
    const get = vi.fn((_url: string, params: { q: string }) =>
      params.q === 'bad' ? throwError(() => ({ status: 503 })) : of({ items: [alisher] }),
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
      expect(modal.searchStatus()).toBe('error');
      await search('али');
      expect(get).toHaveBeenLastCalledWith('/entities/md.users', {
        filter: JSON.stringify([{ field: 'state', op: 'eq', value: 'A' }]),
        q: 'али',
        limit: 15,
      });
      expect(modal.foundUsers()).toEqual([alisher]);
      expect(modal.isUserDropdownOpen()).toBe(true);

      modal.selectUser(alisher);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);
      modal.submitAddMember();
      expect(added).toHaveBeenCalledWith({ projectId: 7, userId: 5, accessKind: 'R' });
      expect(modal.selectedUser()).toEqual(alisher);
      fixture.componentRef.setInput('addErrors', { accessKind: 'Role refused' });
      fixture.detectChanges();
      expect(modal.selectedUser()).toEqual(alisher);
      fixture.componentRef.setInput('addedCount', 1);
      fixture.detectChanges();
      expect(modal.selectedUser()).toBeNull();
      // Choosing and clearing the user fill the box without searching again.
      expect(get).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
      fixture.destroy();
    }
  });

  it.each(['clear', 'reopen', 'added'] as const)('can repeat the same person search after %s', async (reset) => {
    const person = { id: 5, name: 'Alex', login: 'alex' } as User;
    const get = vi.fn(() => of({ items: [person] }));
    const fixture = await createFixture(true, members, get);
    const modal = fixture.componentInstance;
    vi.useFakeTimers();
    try {
      modal.onSearchInput('alex');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);
      modal.selectUser(person);
      if (reset === 'clear') modal.clearSelectedUser();
      if (reset === 'reopen') {
        fixture.componentRef.setInput('isOpen', false);
        fixture.detectChanges();
        fixture.componentRef.setInput('isOpen', true);
      }
      if (reset === 'added') fixture.componentRef.setInput('addedCount', 1);
      fixture.detectChanges();
      expect(modal.selectedUser()).toBeNull();
      expect(modal.foundUsers()).toEqual([]);
      modal.onSearchInput('alex');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);
      expect(get).toHaveBeenCalledTimes(2);
      expect(modal.foundUsers()).toEqual([person]);
    } finally {
      vi.useRealTimers();
      fixture.destroy();
    }
  });

  it('cancels a pending person search on clear so a late response cannot restore old options', async () => {
    const response = new Subject<{ items: User[] }>();
    const get = vi.fn(() => response);
    const fixture = await createFixture(true, members, get);
    const modal = fixture.componentInstance;
    vi.useFakeTimers();
    try {
      modal.onSearchInput('alex');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);
      modal.clearSelectedUser();
      fixture.detectChanges();
      response.next({ items: [{ id: 5, name: 'Alex' } as User] });
      expect(modal.foundUsers()).toEqual([]);
      expect(modal.isUserDropdownOpen()).toBe(false);
    } finally {
      vi.useRealTimers();
      fixture.destroy();
    }
  });

  it('distinguishes search failure from empty results and can retry the same query', async () => {
    const get = vi
      .fn()
      .mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValue(of({ items: [] }));
    const fixture = await createFixture(true, members, get);
    const modal = fixture.componentInstance;
    vi.useFakeTimers();
    try {
      modal.onSearchInput('alex');
      fixture.detectChanges();
      expect(document.body.querySelector('[data-testid="project-member-search-loading"]')).not.toBeNull();
      await vi.advanceTimersByTimeAsync(250);
      fixture.detectChanges();
      expect(document.body.querySelector('[data-testid="project-member-search-error"]')).not.toBeNull();
      const retry = document.body.querySelector('[data-testid="project-member-search-retry"]') as HTMLButtonElement;
      retry.click();
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(250);
      fixture.detectChanges();
      expect(get).toHaveBeenCalledTimes(2);
      expect(document.body.querySelector('[data-testid="project-member-search-error"]')).toBeNull();
      expect(document.body.querySelector('[data-testid="project-member-search-empty"]')).not.toBeNull();
    } finally {
      vi.useRealTimers();
      fixture.destroy();
    }
  });

  it('offers only server-supported read/write access and defaults the addition to read', async () => {
    const fixture = await createFixture(true);
    const modal = fixture.componentInstance;
    const added = vi.fn();
    modal.addMember.subscribe(added);
    expect(modal.accessKindOptions().map((option) => option.id)).toEqual(['R', 'W']);
    modal.selectUser({ id: 5, name: 'Alex' } as User);
    modal.submitAddMember();
    expect(added).toHaveBeenLastCalledWith({ projectId: 7, userId: 5, accessKind: 'R' });
    modal.changeAccessKind('W');
    modal.submitAddMember();
    expect(added).toHaveBeenLastCalledWith({ projectId: 7, userId: 5, accessKind: 'W' });
    modal.changeAccessKind('MEMBER');
    expect(modal.selectedAccessKind()).toBe('W');
    fixture.destroy();
  });

  it('names canonical read/write member access instead of exposing storage codes', async () => {
    const fixture = await createFixture(true, [
      { ...members[0], accessKind: 'R' },
      { ...members[1], accessKind: 'W' },
    ]);
    expect(rowTexts(document.body).map((row) => row[2])).toEqual([
      '\u0427\u0442\u0435\u043d\u0438\u0435',
      '\u0420\u0435\u0434\u0430\u043a\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435',
    ]);
    fixture.destroy();
  });

  it('asks for a person when Add is pressed without one, and shows the server refusal under its field', async () => {
    const fixture = await createFixture(true);
    const added = vi.fn();
    fixture.componentInstance.addMember.subscribe(added);
    const add = document.body.querySelector('[data-testid="project-member-add"]') as HTMLButtonElement;
    const userError = () =>
      (
        document.body.querySelector('smt-control:has(#project-member-search) .smt-control__error')?.textContent ?? ''
      ).trim();

    expect(add.disabled).toBe(false);
    add.click();
    fixture.detectChanges();
    expect(added).not.toHaveBeenCalled();
    expect(userError()).toBe('Обязательное поле');

    fixture.componentRef.setInput('addErrors', { accessKind: 'Неизвестный уровень' });
    fixture.detectChanges();
    expect(
      document.body.querySelector('smt-control:has(#project-member-role) .smt-control__error')?.textContent?.trim(),
    ).toBe('Неизвестный уровень');
    fixture.destroy();
  });
});
