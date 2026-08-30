import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { CpApiService } from '../core/cp-api.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  async function createFixture(login = vi.fn()) {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: CpApiService, useValue: { login } }
      ]
    }).compileComponents();
    return TestBed.createComponent(LoginComponent);
  }

  it('uses a named form with explicitly labelled credential fields', async () => {
    const fixture = await createFixture();
    fixture.detectChanges();

    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    const controls = Array.from(form.querySelectorAll('input')) as HTMLInputElement[];

    expect(form.getAttribute('aria-labelledby')).not.toBeNull();
    expect(controls).toHaveLength(2);
    for (const control of controls) {
      expect(control.id).not.toBe('');
      expect(form.querySelector(`label[for="${control.id}"]`)).not.toBeNull();
    }
  });

  it('keeps credentials recoverable and exposes an inline authentication error', async () => {
    const login = vi.fn().mockRejectedValue({ status: 401 });
    const fixture = await createFixture(login);
    fixture.componentInstance.login = 'operator';
    fixture.componentInstance.password = 'secret';
    fixture.detectChanges();

    await fixture.componentInstance.submit();
    fixture.detectChanges();

    const error = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    const password = fixture.nativeElement.querySelector('#cp-password') as HTMLInputElement;
    const submit = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;

    expect(error.textContent).toContain('Неверный логин или пароль');
    expect(password.value).toBe('secret');
    expect(password.getAttribute('aria-invalid')).toBe('true');
    expect(password.getAttribute('aria-describedby')).toBe(error.id);
    expect(submit.getAttribute('aria-busy')).toBe('false');
  });
});
