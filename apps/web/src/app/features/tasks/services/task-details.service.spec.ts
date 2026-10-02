import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Task, TaskComment } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { TaskDetailsService } from './task-details.service';

const task = (id: number, title = `Task ${id}`): Task => ({
  id,
  title,
  typeCode: 'task',
  statusCode: 's1',
  priority: 'medium',
  attributes: {},
  createdAt: '2026-09-05T00:00:00Z',
});
const comment = (id: number, taskId: number): TaskComment =>
  ({ id, taskId, userId: 1, userName: null, userLogin: null, textMarkdown: `c${id}`, createdAt: '' }) as TaskComment;
/** The task list on the general runtime (ADR-0032 8), one task's record and the subtasks read from the list. */
const LIST = '/entities/ms.tasks';
const record = (id: number) => `${LIST}/${id}`;
const FILE = { fileId: 'file-1', fileName: 'one.txt', sizeBytes: 3, mimeType: 'text/plain', createdAt: '' };

/** Opened from the list: no record in the address and no edit dialog in the way. */
const onList = () => null;

/** A server page holding every row (plan item 3.5: growing collections answer KeysetPage). */
const page = <T>(items: T[]) => ({ items, hasMore: false, totalEstimated: items.length, totalExact: true });

describe('TaskDetailsService', () => {
  let responses: Record<string, Observable<unknown>>;
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; delete: ReturnType<typeof vi.fn> };
  let details: TaskDetailsService;
  const open = (t: Task) => details.openTaskDetails(t, () => false, onList, vi.fn());

  beforeEach(() => {
    responses = {};
    api = {
      get: vi.fn((path: string) => responses[path] ?? of(path === LIST ? page([]) : [])),
      post: vi.fn((path: string) => responses[`POST ${path}`] ?? of({})),
      delete: vi.fn(() => of({})),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
    details = TestBed.inject(TaskDetailsService);
  });

  it('shows the fresh card and its comments, and keeps only comments of that task', () => {
    responses[record(2)] = of(task(2, 'Fresh'));
    responses[LIST] = of(page([task(21)]));
    responses['/tasks/2/comments'] = of(page([comment(1, 2), comment(2, 9)]));

    open(task(2));

    expect(api.get).toHaveBeenCalledWith(record(2), undefined, { notifyError: false });
    expect(api.get).toHaveBeenCalledWith('/tasks/2/members', undefined, { notifyError: false });
    expect(api.get).toHaveBeenCalledWith(
      LIST,
      { filter: JSON.stringify([{ field: 'parentTaskId', op: 'eq', value: 2 }]), limit: 100 },
      { notifyError: false },
    );
    expect(details.selectedTask()?.title).toBe('Fresh');
    expect(details.taskSubtasks().map((t) => t.id)).toEqual([21]);
    expect(details.comments().map((c) => c.id)).toEqual([1]);
    expect(details.detailLoading()).toBe(false);
    // Reading does not change the task: the card marks it viewed by its own command (plan 10/10, item 3.10).
    expect(api.post).toHaveBeenCalledWith('/tasks/2/view', null, { notifyError: false });
  });

  it('reads a long thread a page at a time and appends the next page below the first', () => {
    responses[record(2)] = of(task(2, 'Long'));
    responses['/tasks/2/comments'] = of({ items: [comment(1, 2)], nextCursor: 'c2', hasMore: true });

    open(task(2));
    expect(details.commentsNextCursor()).toBe('c2');

    responses['/tasks/2/comments'] = of(page([comment(2, 2)]));
    details.loadMoreComments(onList);

    expect(api.get).toHaveBeenLastCalledWith('/tasks/2/comments', { cursor: 'c2' }, { notifyError: false });
    expect(details.comments().map((c) => c.id)).toEqual([1, 2]);
    expect(details.commentsNextCursor()).toBeNull();
    expect(details.commentsLoading()).toBe(false);
  });

  it('ignores out-of-order detail and comment responses for a previously selected task', () => {
    const [detail1, detail2, comments1, comments2] = [1, 2, 3, 4].map(() => new Subject<unknown>());
    Object.assign(responses, {
      [record(1)]: detail1,
      [record(2)]: detail2,
      '/tasks/1/comments': comments1,
      '/tasks/2/comments': comments2,
    });

    open(task(1));
    open(task(2));
    detail2.next(task(2, 'Fresh 2'));
    comments2.next(page([comment(20, 2)]));
    detail1.next(task(1, 'Late 1'));
    comments1.next(page([comment(10, 1)]));

    expect(details.selectedTask()?.title).toBe('Fresh 2');
    expect(details.taskSubtasks()).toEqual([]);
    expect(details.comments().map((c) => c.taskId)).toEqual([2]);
  });

  it('ignores detail and comment responses after the card closes', () => {
    const [late, lateComments] = [new Subject<unknown>(), new Subject<unknown>()];
    Object.assign(responses, { [record(3)]: late, '/tasks/3/comments': lateComments });

    open(task(3));
    details.closeTaskDetails(true, onList, vi.fn());
    late.next(task(3, 'Late'));
    lateComments.next(page([comment(30, 3)]));

    expect(details.selectedTask()).toBeNull();
    expect(details.taskSubtasks()).toEqual([]);
    expect(details.comments()).toEqual([]);
  });

  it('marks a missing or forbidden card as not found, and a mismatched answer as an error', () => {
    responses[record(4)] = new Observable((subscriber) => subscriber.error({ status: 404 }));
    open(task(4));
    expect(details.detailLoadError()).toBe(true);
    expect(details.detailNotFound()).toBe(true);

    responses[record(4)] = of(task(5));
    details.retryTaskDetails(onList);
    expect(details.detailLoadError()).toBe(true);
    expect(details.detailNotFound()).toBe(false);
  });

  it('does not attach a late file response to a newly selected task, and removes a file from the open one', () => {
    const attach = new Subject<unknown>();
    responses['POST /tasks/3/files'] = attach;

    open(task(3));
    details.onTaskFileAttached(FILE);
    open(task(4));
    attach.next({});
    expect(details.taskFiles()).toEqual([]);

    details.taskFiles.set([FILE]);
    details.onTaskFileRemoved(FILE);
    expect(api.delete).toHaveBeenCalledWith('/tasks/4/files/file-1');
    expect(details.taskFiles()).toEqual([]);
  });

  it('keeps comment drafts scoped to their task', () => {
    open(task(1));
    details.commentDraft = 'draft one';
    open(task(2));
    details.commentDraft = 'draft two';

    open(task(1));
    expect(details.commentDraft).toBe('draft one');
    open(task(2));
    expect(details.commentDraft).toBe('draft two');
  });

  it('posts one comment at a time, then clears the draft and reads the comments again', () => {
    const post = new Subject<unknown>();
    responses['POST /tasks/4/comments'] = post;
    open(task(4));
    details.commentDraft = 'hello';

    details.submitComment(() => true, onList);
    details.submitComment(() => true, onList);

    expect(api.post.mock.calls.filter(([path]) => path === '/tasks/4/comments')).toHaveLength(1);
    expect(details.isCommentSubmitting()).toBe(true);
    const commentReads = api.get.mock.calls.filter(([path]) => path === '/tasks/4/comments').length;
    post.next({});
    post.complete();
    expect(details.commentDraft).toBe('');
    expect(details.isCommentSubmitting()).toBe(false);
    expect(api.get.mock.calls.filter(([path]) => path === '/tasks/4/comments')).toHaveLength(commentReads + 1);
  });

  it('does not post a comment without the right to comment', () => {
    open(task(5));
    details.commentDraft = 'not allowed';

    details.submitComment(() => false, onList);

    expect(api.post).not.toHaveBeenCalled();
  });

  it('opens another record through the address while one is shown there', () => {
    const navigate = vi.fn();

    details.openTaskDetails(
      task(8),
      () => false,
      () => '7',
      navigate,
    );

    expect(navigate).toHaveBeenCalledWith('8');
    expect(api.get).not.toHaveBeenCalled();
  });
});
