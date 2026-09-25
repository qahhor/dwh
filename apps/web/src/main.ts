import { bootstrapApplication } from '@angular/platform-browser';
import { inject, provideAppInitializer } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { idempotencyKeyInterceptor } from './app/core/http/idempotency-key.interceptor';
import { sessionExpiredInterceptor } from './app/core/http/session-expired.interceptor';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { I18nService } from './app/core/services/i18n.service';
import { LanguageTabSync } from './app/core/services/language-tab-sync';
import { IdleLockService } from './app/core/services/idle-lock.service';

bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      // Order matters: a 401 is seen once, after the retries of a keyed change are over.
      withInterceptors([sessionExpiredInterceptor, idempotencyKeyInterceptor])
    ),
    provideAppInitializer(() => inject(I18nService).initialize()),
    provideAppInitializer(() => inject(LanguageTabSync).start()),
    // Created at start so it follows sign-in and sign-out by itself.
    provideAppInitializer(() => void inject(IdleLockService))
  ]
}).catch(err => console.error(err));
