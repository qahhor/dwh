import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardApi } from './dashboard.api';
import { DashboardStore } from './dashboard.store';
import type { DashboardDetail } from './dashboard.models';

const PANEL: DashboardDetail = {
  id: 2,
  name: 'Sales',
  revision: 3,
  modifiedAt: '',
  canEdit: true,
  canShare: true,
  shared: false,
  widgetCount: 2,
  widgets: [
    { viewId: 7, width: 1, available: false },
    { viewId: 8, periodField: 'date', width: 2, available: false },
  ],
};
describe('DashboardStore', () => {
  let reads: Subject<DashboardDetail>[];
  let writes: Subject<DashboardDetail>[];
  let api: {
    remove: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    get: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    list: ReturnType<typeof vi.fn>;
  };
  let store: DashboardStore;
  beforeEach(() => {
    reads = [];
    writes = [];
    api = {
      remove: vi.fn(() => of(undefined)),
      create: vi.fn(() => {
        const write = new Subject<DashboardDetail>();
        writes.push(write);
        return write;
      }),
      get: vi.fn(() => {
        const read = new Subject<DashboardDetail>();
        reads.push(read);
        return read;
      }),
      update: vi.fn(() => {
        const write = new Subject<DashboardDetail>();
        writes.push(write);
        return write;
      }),
      list: vi.fn(() => of({ items: [], hasMore: false, nextCursor: null })),
    };
    TestBed.configureTestingModule({ providers: [DashboardStore, { provide: DashboardApi, useValue: api }] });
    store = TestBed.inject(DashboardStore);
  });
  function loaded() {
    store.load(2);
    expect(api.get).toHaveBeenCalledWith(2);
    reads[0].next(PANEL);
    reads[0].complete();
  }
  it('does not let a pending selection replace a newly created panel', () => {
    store.load(2);
    store.create('New panel').subscribe();
    writes[0].next({ ...PANEL, id: 9, name: 'New panel' });
    writes[0].complete();
    reads[0].next(PANEL);
    reads[0].complete();
    expect(store.selectedId()).toBe(9);
    expect(store.selected()?.name).toBe('New panel');
    expect(store.loading()).toBe(false);
  });
  it('renames and deletes the selected panel using its current revision', () => {
    loaded();
    store.renameSaved(' Renamed ').subscribe();
    expect(api.update).toHaveBeenCalledWith(2, expect.objectContaining({ name: 'Renamed' }), 3);
    writes[0].next({ ...PANEL, name: 'Renamed', revision: 4 });
    writes[0].complete();
    store.remove().subscribe();
    expect(api.remove).toHaveBeenCalledWith(2, 4);
    expect(store.selected()).toBeNull();
    expect(store.panels.items()).toEqual([]);
  });
  it('keeps unique bounded widget layouts and applies field mapping explicitly', () => {
    loaded();
    store.edit();
    store.add(7);
    expect(store.draft()?.widgets).toHaveLength(2);
    for (let id = 10; id < 30; id++) store.add(id);
    expect(store.draft()?.widgets).toHaveLength(12);
    store.configure(0, 'createdAt', 2);
    expect(store.draft()?.widgets[0]).toEqual({ viewId: 7, periodField: 'createdAt', width: 2 });
    store.configure(0, null, 1);
    expect(store.draft()?.widgets[0]).toEqual({ viewId: 7, width: 1 });
    store.takeOff(0);
    expect(store.draft()?.widgets[0].viewId).toBe(8);
    store.cancel();
    expect(store.draft()).toBeNull();
    expect(store.dirty()).toBe(false);
  });
  it('uses the revision returned by sharing for the next layout save', () => {
    loaded();
    store.sharingChanged(4, true);
    store.edit();
    store.save();
    expect(api.update).toHaveBeenCalledWith(2, expect.anything(), 4);
    writes[0].next({ ...PANEL, revision: 5 });
    writes[0].complete();
  });
  it('does not let an earlier panel response overwrite the latest selection', () => {
    store.load(2);
    store.load(5);
    expect(reads).toHaveLength(2);
    reads[1].next({ ...PANEL, id: 5 });
    reads[1].complete();
    reads[0].next(PANEL);
    reads[0].complete();
    expect(store.selected()?.id).toBe(5);
    expect(store.loading()).toBe(false);
  });
  it('preserves edited name and layout on 409 and blocks blind resubmission', () => {
    loaded();
    store.edit();
    store.rename('My changes');
    store.move(1, -1);
    store.save();
    expect(api.update).toHaveBeenCalledWith(
      2,
      {
        name: 'My changes',
        widgets: [
          { viewId: 8, periodField: 'date', width: 2 },
          { viewId: 7, width: 1 },
        ],
      },
      3,
    );
    writes[0].error({ status: 409, detail: 'Conflict' });
    expect(store.draft()?.name).toBe('My changes');
    expect(store.conflict()).toBe(true);
    store.save();
    expect(writes).toHaveLength(1);
  });
  it('replaces a draft only on explicit reload after a conflict', () => {
    loaded();
    store.edit();
    store.rename('Mine');
    store.save();
    writes[0].error({ status: 409 });
    store.load(2);
    reads[1].next({ ...PANEL, revision: 4, name: 'Other editor' });
    reads[1].complete();
    expect(store.draft()).toBeNull();
    expect(store.selected()?.name).toBe('Other editor');
    expect(store.conflict()).toBe(false);
  });
  it('does not expose edit state for a shared read-only panel', () => {
    store.load(2);
    expect(reads).toHaveLength(1);
    reads[0].next({ ...PANEL, canEdit: false });
    reads[0].complete();
    store.edit();
    store.rename('forbidden');
    store.save();
    expect(store.draft()).toBeNull();
    expect(api.update).not.toHaveBeenCalled();
  });
  it('keeps server field validation visible and retains the draft', () => {
    loaded();
    store.edit();
    store.rename('Bad');
    store.save();
    writes[0].error({
      status: 422,
      detail: 'Invalid layout',
      errors: [{ field: 'widgets[0].periodField', message: 'Choose a date' }],
    });
    expect(store.error()).toContain('Choose a date');
    expect(store.draft()?.name).toBe('Bad');
  });
  it('updates the baseline only after the server accepts the complete layout', () => {
    loaded();
    store.edit();
    store.rename('Saved');
    store.save();
    expect(store.selected()?.name).toBe('Sales');
    writes[0].next({ ...PANEL, revision: 4, name: 'Saved' });
    writes[0].complete();
    expect(store.selected()?.revision).toBe(4);
    expect(store.draft()).toBeNull();
    expect(store.busy()).toBe(false);
  });
});
