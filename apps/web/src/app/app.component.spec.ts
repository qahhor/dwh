import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { AppComponent } from './app.component';
import { AuthService } from './core/services/auth.service';
import { ToastService } from './core/services/toast.service';

describe('Application notification host', () => {
  it('renders a password-change success notification outside the authenticated shell', async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]), ToastService,
        { provide: AuthService, useValue: { isLoading: signal(false), checkSession: () => of(null) } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    TestBed.inject(ToastService).success('Пароль изменён. Войдите снова с новым паролем.');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('ui-toast-container')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent)
      .toContain('Пароль изменён. Войдите снова с новым паролем.');
  });
});
