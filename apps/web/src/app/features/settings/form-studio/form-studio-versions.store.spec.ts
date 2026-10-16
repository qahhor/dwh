import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { FormStudioStore } from './form-studio.store';
import { STUDIO_PREVIEW, STUDIO_VERSION } from '@testing/form-studio';
import { studioDraftPath } from './form-studio-lifecycle.models';
import { FormStudioVersionsStore } from './form-studio-versions.store';

const STUDIO_DRAFT_PATH = studioDraftPath('example.products');

describe('FormStudioVersionsStore', () => {
  let versions: FormStudioVersionsStore;
  const api = { get: vi.fn(), post: vi.fn() };
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  beforeEach(() => {
    api.get.mockReset();
    api.post.mockReset();
    TestBed.configureTestingModule({
      providers: [FormStudioStore, FormStudioVersionsStore, { provide: ApiService, useValue: api }],
    });
    versions = TestBed.inject(FormStudioVersionsStore);
    TestBed.inject(FormStudioStore).replaceWithSaved(STUDIO_PREVIEW);
  });
  it('loads version history with the returned cursor and keeps the immutable selected package separate', () => {
    const first = { version: 2, sourceRevision: 2, checksum: 'b'.repeat(64), createdAt: '2026-10-16T00:00:00Z' };
    const second = { version: 1, sourceRevision: 1, checksum: 'a'.repeat(64), createdAt: '2026-10-15T00:00:00Z' };
    api.get
      .mockReturnValueOnce(of({ items: [first], nextCursor: 'next-2', hasMore: true, totalReturned: 1 }))
      .mockReturnValueOnce(of({ items: [second], nextCursor: null, hasMore: false, totalReturned: 1 }))
      .mockReturnValueOnce(of(structuredClone(STUDIO_VERSION)));
    versions.load();
    versions.load(true);
    expect(api.get).toHaveBeenNthCalledWith(
      2,
      STUDIO_DRAFT_PATH + '/versions',
      { limit: 20, cursor: 'next-2' },
      { notifyError: false },
    );
    expect(versions.items().map((item) => item.version)).toEqual([2, 1]);
    expect(versions.hasMore()).toBe(false);
    versions.select(1);
    expect(versions.selected()).toEqual(STUDIO_VERSION);
    expect(versions.items()).toHaveLength(2);
  });
  it('shows a history error and recovers without an empty-state assertion', () => {
    api.get
      .mockReturnValueOnce(throwError(() => ({ detail: 'No access' })))
      .mockReturnValueOnce(of({ items: [], nextCursor: null, hasMore: false, totalReturned: 0 }));
    versions.load();
    expect(versions.error()).toBe('No access');
    expect(versions.loading()).toBe(false);
    versions.load();
    expect(versions.error()).toBe('');
    expect(versions.items()).toEqual([]);
  });
  it('releases a pending artifact read when preparation selects a newer version', () => {
    const pending = new Subject<typeof STUDIO_VERSION>();
    api.get
      .mockReturnValueOnce(pending)
      .mockReturnValueOnce(of({ items: [], nextCursor: null, hasMore: false, totalReturned: 0 }));
    versions.select(1);
    expect(versions.selecting()).toBe(true);
    versions.acceptPrepared({ ...STUDIO_VERSION, version: 2 });
    expect(versions.selecting()).toBe(false);
    pending.next(STUDIO_VERSION);
    pending.complete();
    expect(versions.selected()?.version).toBe(2);
    expect(versions.busy()).toBe(false);
  });
  it('clears stale history on a failed refresh but preserves loaded pages on append failure', () => {
    const item = { version: 1, sourceRevision: 1, checksum: 'a'.repeat(64), createdAt: '2026-10-15T00:00:00Z' };
    api.get
      .mockReturnValueOnce(of({ items: [item], nextCursor: 'next', hasMore: true, totalReturned: 1 }))
      .mockReturnValue(throwError(() => ({ detail: 'No access' })));
    versions.load();
    versions.load(true);
    expect(versions.items()).toEqual([item]);
    versions.load();
    expect(versions.items()).toEqual([]);
    expect(versions.hasMore()).toBe(false);
  });
  it('ignores an older file read after a newer package was selected', async () => {
    let finish!: (value: ArrayBuffer) => void;
    const older = {
      size: 10,
      arrayBuffer: () =>
        new Promise<ArrayBuffer>((resolve) => {
          finish = resolve;
        }),
    } as File;
    const newer = new File([JSON.stringify({ ...STUDIO_VERSION, version: 2 })], 'new.json');
    api.post.mockReturnValue(of({ valid: true, version: 2, sourceRevision: 2, checksum: STUDIO_VERSION.checksum }));
    const first = versions.verifyFile(older);
    await versions.verifyFile(newer);
    finish(new TextEncoder().encode(JSON.stringify(STUDIO_VERSION)).buffer);
    await first;
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][0]).toBe(STUDIO_DRAFT_PATH + '/versions/2/verify');
    expect(versions.verified()?.version).toBe(2);
  });
  it('rejects invalid UTF-8 without sending normalized replacement characters', async () => {
    await versions.verifyFile(new File([new Uint8Array([0xff])], 'invalid.json'));
    expect(api.post).not.toHaveBeenCalled();
    expect(versions.verificationError()).not.toBe('');
    expect(versions.busy()).toBe(false);
  });
  it('rechecks access on every download and preserves the selection when access is revoked', async () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:studio');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    api.get
      .mockReturnValueOnce(of(STUDIO_VERSION))
      .mockReturnValueOnce(throwError(() => ({ status: 403, detail: 'Access revoked' })))
      .mockReturnValueOnce(of(STUDIO_VERSION));
    versions.select(1);
    versions.downloadSelected();
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(download).not.toHaveBeenCalled();
    expect(versions.artifactError()).toBe('Access revoked');
    expect(versions.selected()).toEqual(STUDIO_VERSION);
    versions.downloadSelected();
    expect(download).toHaveBeenCalledOnce();
    expect(await (createObjectURL.mock.calls[0][0] as Blob).text()).toBe(JSON.stringify(STUDIO_VERSION, null, 2));
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:studio');
    expect(versions.artifactError()).toBe('');
    expect(versions.busy()).toBe(false);
  });
  it('shows a download failure without damaging the immutable version or blocking a retry', () => {
    vi.stubGlobal('URL', {
      createObjectURL: () => {
        throw new Error('Download failed');
      },
    });
    api.get.mockReturnValue(of(STUDIO_VERSION));
    versions.select(1);
    versions.downloadSelected();
    expect(versions.selected()).toEqual(STUDIO_VERSION);
    expect(versions.artifactError()).not.toBe('');
    expect(versions.busy()).toBe(false);
  });
  it('verifies the original JSON bytes against its stored version without rewriting the selected package', async () => {
    api.get.mockReturnValue(of(structuredClone(STUDIO_VERSION)));
    versions.select(1);
    api.post.mockReturnValue(of({ valid: true, version: 1, sourceRevision: 1, checksum: STUDIO_VERSION.checksum }));
    const text = JSON.stringify(STUDIO_VERSION, null, 2);
    versions.verifyText(text);
    const [path, body, options] = api.post.mock.calls[0];
    expect(path).toBe(STUDIO_DRAFT_PATH + '/versions/1/verify');
    expect(body).toBeInstanceOf(Blob);
    expect(await body.text()).toBe(text);
    expect(options).toEqual({ notifyError: false });
    expect(versions.verified()?.checksum).toBe(STUDIO_VERSION.checksum);
    expect(versions.selected()).toEqual(STUDIO_VERSION);
  });
  it.each([
    '{',
    '{"version":0}',
    '{"version":-1}',
    '{"version":1.5}',
    '{"version":9007199254740992}',
    'я'.repeat(33000),
  ])('rejects a malformed or oversized package before any request', (text) => {
    versions.verifyText(text);
    expect(api.post).not.toHaveBeenCalled();
    expect(versions.verified()).toBeNull();
    expect(versions.verificationError()).not.toBe('');
  });
  it('describes stale package dependencies without promising an unavailable save action', () => {
    api.get.mockReturnValue(of(STUDIO_VERSION));
    versions.select(1);
    const detail = TestBed.inject(I18nService).translate('error.md.form_studio_dependencies_changed');
    api.post.mockReturnValue(
      throwError(() => ({ status: 409, messageKey: 'error.md.form_studio_dependencies_changed', detail })),
    );
    versions.verifyText(JSON.stringify(STUDIO_VERSION));
    expect(versions.verificationError()).not.toContain('Сохраните');
    expect(versions.verificationError()).toContain('Зависимости пакета');
    expect(versions.verified()).toBeNull();
    expect(versions.selected()).toEqual(STUDIO_VERSION);
    expect(TestBed.inject(FormStudioStore).draft()).toEqual(STUDIO_PREVIEW.declaration);
  });
  it('does not retain an earlier verification success after a tampered package is rejected', () => {
    api.post
      .mockReturnValueOnce(of({ valid: true, version: 1, sourceRevision: 1, checksum: STUDIO_VERSION.checksum }))
      .mockReturnValueOnce(throwError(() => ({ status: 422, detail: 'Checksum mismatch' })));
    versions.verifyText(JSON.stringify(STUDIO_VERSION));
    versions.verifyText(JSON.stringify({ ...STUDIO_VERSION, checksum: 'f'.repeat(64) }));
    expect(versions.verified()).toBeNull();
    expect(versions.verificationError()).toBe('Checksum mismatch');
  });
});
