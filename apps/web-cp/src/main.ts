import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';

bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes),
    // Имена по умолчанию Angular совпадают с тем, что выставляет Spring Security
    // (cookie XSRF-TOKEN, заголовок X-XSRF-TOKEN) — токен подставляется сам.
    provideHttpClient(withFetch(), withXsrfConfiguration({
      cookieName: 'XSRF-TOKEN',
      headerName: 'X-XSRF-TOKEN'
    }))
  ]
}).catch(err => console.error(err));
