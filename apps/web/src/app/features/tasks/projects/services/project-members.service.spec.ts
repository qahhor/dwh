import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { SMTModalConfirmConfig, SMTModalService } from '@shared/ui-kit/components/modal';
import { ProjectMember } from '../projects.models';
import { ProjectMembersService } from './project-members.service';

const PROJECT: Project = { id: 42, name: 'Project 42', state: 'A', createdAt: '2026-09-06T00:00:00Z' };
const member = (userId: number, userName: string): ProjectMember => ({
  projectId: 42,
  userId,
  userName,
  userEmail: `${userName}@example.com`,
  accessKind: 'MANAGER',
});
/** A page of members as the server answers it. */
const page = (items: ProjectMember[], nextCursor: string | null = null) => ({
  items,
  nextCursor,
  hasMore: nextCursor !== null,
  totalEstimated: items.length,
});

describe('ProjectMembersService', () => {
  let memberReads: Observable<unknown>[];
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; delete: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirmed: SMTModalConfirmConfig | undefined;
  let members: ProjectMembersService;
  /** Starts the resource's read and waits until its answer is in. */
  const settle = async () => {
    TestBed.tick();
    await TestBed.inject(ApplicationRef).whenStable();
  };
  const reads = () => api.get.mock.calls.filter(([url]) => url === '/tasks/projects/42/members/page').length;

  beforeEach(() => {
    memberReads = [];
    confirmed = undefined;
    api = {
      // A member action reads the project for its revision first (ADR-0032 6.7).
      get: vi.fn((url: string) =>
        url === '/entities/ms.projects/42'
          ? of({ id: 42, revision: 7 })
          : (memberReads.shift() ?? of(page([member(10, 'Alice')]))),
      ),
      post: vi.fn(() => of({})),
      delete: vi.fn(() => of({})),
    };
    toast = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        ProjectMembersService,
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: toast },
        {
          provide: SMTModalService,
          useValue: {
            confirm: (options: SMTModalConfirmConfig) => {
              confirmed = options;
              return of(true);
            },
          },
        },
      ],
    });
    members = TestBed.inject(ProjectMembersService);
  });

  it('reads the members of the project whose dialog opens, and shows none once it closes', async () => {
    const pending = new Subject<unknown>();
    memberReads = [pending];

    members.openMembersModal(PROJECT);
    TestBed.tick();
    expect(members.selectedProjectForMembers()).toEqual(PROJECT);
    expect(api.get).toHaveBeenCalledWith(
      '/tasks/projects/42/members/page',
      { limit: 50, cursor: undefined },
      { notifyError: false },
    );
    expect(members.isLoadingMembers()).toBe(true);
    pending.next(page([member(10, 'Alice')]));
    await settle();
    expect(members.projectMembers()).toEqual([member(10, 'Alice')]);
    expect(members.isLoadingMembers()).toBe(false);

    members.closeMembersModal();
    await settle();
    expect(members.selectedProjectForMembers()).toBeNull();
    expect(members.projectMembers()).toEqual([]);
  });

  it('adds a member, then reads the members again; a refusal is shown with its reason', async () => {
    members.openMembersModal(PROJECT);
    await settle();
    memberReads = [of(page([member(10, 'Alice'), member(20, 'Bob')]))];

    members.onAddProjectMember({ projectId: 42, userId: 20, accessKind: 'MEMBER' });
    await settle();
    expect(api.post).toHaveBeenCalledWith(
      '/entities/ms.projects/42/actions/add_member',
      { userId: 20, accessKind: 'MEMBER' },
      { notifyError: false, ifMatch: 7 },
    );
    expect(toast.success).toHaveBeenCalled();
    expect(members.projectMembers().map((m) => m.userId)).toEqual([10, 20]);
    expect(members.isAddingMember()).toBe(false);

    api.post.mockReturnValue(throwError(() => ({ status: 409, detail: 'Already a member' })));
    members.onAddProjectMember({ projectId: 42, userId: 20, accessKind: 'MEMBER' });
    expect(toast.error).toHaveBeenCalledWith('Already a member');
    expect(members.isAddingMember()).toBe(false);
  });

  it('puts a refusal about the person or the access level under that field, without a toast', async () => {
    members.openMembersModal(PROJECT);
    await settle();
    api.post.mockReturnValue(
      throwError(() => ({ status: 422, errors: [{ field: 'accessKind', message: 'Unknown level' }] })),
    );
    members.onAddProjectMember({ projectId: 42, userId: 20, accessKind: 'BOSS' });
    expect(members.addErrors()).toEqual({ accessKind: 'Unknown level' });
    expect(toast.error).not.toHaveBeenCalled();

    members.openMembersModal(PROJECT);
    expect(members.addErrors()).toEqual({});
  });

  it('adds the next page below the members shown, and offers more only while the server has them', async () => {
    memberReads = [of(page([member(10, 'Alice')], 'c1')), of(page([member(20, 'Bob')]))];
    members.openMembersModal(PROJECT);
    await settle();
    expect(members.hasMoreMembers()).toBe(true);

    members.loadMoreMembers();
    await settle();

    expect(api.get).toHaveBeenLastCalledWith(
      '/tasks/projects/42/members/page',
      { limit: 50, cursor: 'c1' },
      { notifyError: false },
    );
    expect(members.projectMembers().map((m) => m.userId)).toEqual([10, 20]);
    expect(members.hasMoreMembers()).toBe(false);
  });

  it('keeps the members on screen when reading them again fails', async () => {
    members.openMembersModal(PROJECT);
    await settle();
    memberReads = [throwError(() => ({ status: 503 }))];

    members.onAddProjectMember({ projectId: 42, userId: 20, accessKind: 'MEMBER' });
    await settle();

    expect(toast.error).toHaveBeenCalledWith('Ошибка загрузки участников проекта');
    expect(members.projectMembers()).toEqual([member(10, 'Alice')]);
  });

  it('removes a member from the confirmation, marks the row meanwhile and reads the members again', async () => {
    members.openMembersModal(PROJECT);
    await settle();
    const removal = new Subject<unknown>();
    api.post.mockReturnValue(removal);

    members.onRemoveProjectMember({ projectId: 42, userId: 10, userName: 'Иван' });
    expect(confirmed?.message).toContain('Иван');
    expect(api.post).not.toHaveBeenCalled();
    confirmed!.action!().subscribe();
    expect(members.removingMemberId()).toBe(10);
    expect(api.post).toHaveBeenCalledWith(
      '/entities/ms.projects/42/actions/remove_member',
      { userId: 10 },
      { notifyError: false, ifMatch: 7 },
    );

    const before = reads();
    removal.next({});
    removal.complete();
    await settle();
    expect(members.removingMemberId()).toBeNull();
    expect(toast.success).toHaveBeenCalled();
    expect(reads()).toBe(before + 1);
    expect(confirmed!.actionError!({ detail: 'Owner stays' })).toBe('Owner stays');
  });
});
