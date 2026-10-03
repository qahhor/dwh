import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { LoginHeaderComponent } from './login-header.component';

describe('LoginHeaderComponent', () => {
  function render(): HTMLElement {
    const fixture = TestBed.createComponent(LoginHeaderComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('names the product once as an image and titles the sign-in card', () => {
    const host = render();

    const brand = host.querySelector('.brand-lockup') as HTMLElement;
    expect(brand.getAttribute('role')).toBe('img');
    expect(brand.getAttribute('aria-label')).toBe('SmartupCMS');
    for (const part of Array.from(brand.children)) expect(part.getAttribute('aria-hidden')).toBe('true');

    expect(host.querySelector('h1')?.textContent?.trim()).toBe(translateTest('auth.login.corporate_login'));
    expect(host.querySelector('.login-subtitle')?.textContent?.trim()).toBe(
      translateTest('auth.login.platform_tagline'),
    );
  });
});
