import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nService } from '../../core/services/i18n.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { AppShellComponent } from './app-shell.component';

describe('Announcement banner API integration', () => {
  let fixture: ComponentFixture<AppShellComponent>;
  let http: HttpTestingController;
  const first = { id: 9, title: 'Maintenance tonight', body: 'Save your work before 22:00.', bannerType: 'WARNING', publishedAt: '2026-09-07T00:00:00Z' };
  const second = { id: 8, title: 'Office notice', body: 'The second unread announcement.', bannerType: 'INFO', publishedAt: '2026-09-06T00:00:00Z' };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [AppShellComponent], providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([])
    ] }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PermissionService).setPermissions(['platform.announcements.view']);
    fixture = TestBed.createComponent(AppShellComponent);
  });

  afterEach(() => { fixture.destroy(); });

  function activeRequest() {
    return http.expectOne(request => request.url === '/api/v1/announcements/active');
  }

  function render() {
    fixture.detectChanges();
    activeRequest().flush([first, second]);
    fixture.detectChanges();
  }

  it('renders the actual published title and body returned by the server', () => {
    render();
    const banner = fixture.nativeElement.querySelector('.announcement-banner') as HTMLElement;
    expect(banner.textContent).toContain('Maintenance tonight');
    expect(banner.textContent).toContain('Save your work before 22:00.');
    http.verify();
  });

  it('reloads localized content when the UI language changes and cancels the old language read', () => {
    fixture.detectChanges();
    const oldRead = activeRequest();
    expect(oldRead.request.params.get('language')).toBe('ru');
    TestBed.inject(I18nService).currentLang.set('de');
    fixture.detectChanges();
    expect(oldRead.cancelled).toBe(true);
    const germanRead = activeRequest();
    expect(germanRead.request.params.get('language')).toBe('de');
    germanRead.flush([{ ...first, title: 'Wartung heute', body: 'Bitte speichern.' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.announcement-banner').textContent).toContain('Wartung heute');
    http.verify();
  });

  it('sends one read mutation and advances to the next unread announcement', () => {
    render();
    const close = fixture.nativeElement.querySelector('.banner-close') as HTMLButtonElement;
    close.click();
    fixture.detectChanges();
    expect(close.disabled).toBe(true);
    fixture.componentInstance.dismissAnnouncement();
    const read = http.expectOne('/api/v1/announcements/9/read');
    read.flush(null);
    fixture.detectChanges();
    activeRequest().flush([second]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.announcement-banner').textContent).toContain('Office notice');
    expect((fixture.nativeElement.querySelector('.banner-close') as HTMLButtonElement).disabled).toBe(false);
    http.verify();
  });

  it('keeps a failed dismissal visible and reports one error so it can be retried', () => {
    render();
    const close = fixture.nativeElement.querySelector('.banner-close') as HTMLButtonElement;
    close.click();
    http.expectOne('/api/v1/announcements/9/read').flush({ detail: 'Synthetic failure' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.announcement-banner').textContent).toContain('Maintenance tonight');
    expect(close.disabled).toBe(false);
    expect(TestBed.inject(ToastService).toasts().filter(toast => toast.type === 'error')).toHaveLength(1);
    http.verify();
  });
});
