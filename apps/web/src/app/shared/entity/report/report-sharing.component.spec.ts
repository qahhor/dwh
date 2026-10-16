import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReportSharingComponent } from './report-sharing.component';

describe('role access editor', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function open() {
    const fixture = TestBed.createComponent(ReportSharingComponent);
    fixture.componentRef.setInput('path', '/dashboards/9');
    fixture.detectChanges();
    fixture.componentInstance.show();
    http.expectOne('/api/v1/dashboards/9/sharing').flush({ revision: 3, roleIds: [7] });
    http.expectOne('/api/v1/report-sharing/roles?limit=20').flush({ items: [], hasMore: true, nextCursor: 'next' });
    return fixture;
  }
  it('preserves selected roles on a revision conflict until explicit reload', () => {
    const fixture = open();
    const component = fixture.componentInstance;
    component.selected.set([7, 8]);
    component.save();
    const req = http.expectOne('/api/v1/dashboards/9/sharing');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush({ detail: 'Changed', status: 409 }, { status: 409, statusText: 'Conflict' });
    expect(component.selected()).toEqual([7, 8]);
    expect(component.conflict()).toBe(true);
    component.save();
    http.expectNone('/api/v1/dashboards/9/sharing');
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(true));
    component.reload();
    http.expectOne('/api/v1/dashboards/9/sharing').flush({ revision: 4, roleIds: [2] });
    expect(component.selected()).toEqual([2]);
    expect(component.conflict()).toBe(false);
  });
  it('cancels a pending read when the dialog is reused for another path', () => {
    const fixture = TestBed.createComponent(ReportSharingComponent);
    fixture.componentRef.setInput('path', '/dashboards/1');
    fixture.detectChanges();
    fixture.componentInstance.show();
    const first = http.expectOne('/api/v1/dashboards/1/sharing');
    http.expectOne('/api/v1/report-sharing/roles?limit=20').flush({ items: [], hasMore: false });
    fixture.componentInstance.close();
    fixture.componentRef.setInput('path', '/dashboards/2');
    fixture.detectChanges();
    fixture.componentInstance.show();
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/v1/dashboards/2/sharing').flush({ revision: 3, roleIds: [9] });
    http.expectOne('/api/v1/report-sharing/roles?limit=20').flush({ items: [], hasMore: false });
    fixture.componentInstance.save();
    const write = http.expectOne('/api/v1/dashboards/2/sharing');
    expect(write.request.body).toEqual({ roleIds: [9] });
    write.flush({ revision: 4, roleIds: [9] });
  });
  it('retains local role edits when reload is declined', () => {
    const fixture = open();
    fixture.componentInstance.selected.set([8]);
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    fixture.componentInstance.reload();
    expect(confirm).toHaveBeenCalled();
    expect(fixture.componentInstance.selected()).toEqual([8]);
    http.expectNone('/api/v1/dashboards/9/sharing');
  });
  it('asks before navigation drops selected roles and blocks navigation while saving', async () => {
    const fixture = open();
    const component = fixture.componentInstance;
    component.selected.set([8]);
    vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    const leave = (component as unknown as { canLeaveRecordPage?: () => import('rxjs').Observable<boolean> })
      .canLeaveRecordPage;
    expect(leave).toBeTypeOf('function');
    expect(await firstValueFrom(leave!.call(component))).toBe(false);
    expect(component.selected()).toEqual([8]);
    component.save();
    expect(await firstValueFrom(leave!.call(component))).toBe(false);
    http.expectOne('/api/v1/dashboards/9/sharing').flush({ revision: 4, roleIds: [8] });
    expect(await firstValueFrom(leave!.call(component))).toBe(true);
  });
  it('does not drop selected roles missing from the first visible catalog window', () => {
    const fixture = open();
    const changed: unknown[] = [];
    fixture.componentInstance.changed.subscribe((value) => changed.push(value));
    fixture.componentInstance.save();
    const req = http.expectOne('/api/v1/dashboards/9/sharing');
    expect(req.request.body).toEqual({ roleIds: [7] });
    req.flush({ revision: 4, roleIds: [7] });
    expect(changed).toEqual([{ revision: 4, roleIds: [7] }]);
    expect(fixture.componentInstance.open()).toBe(false);
  });
});
