import { bootstrapApplication } from '@angular/platform-browser';
import { inject, provideAppInitializer } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { I18nService } from './app/core/services/i18n.service';

bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch()),
    provideAppInitializer(() => inject(I18nService).initialize())
  ]
}).catch(err => console.error(err));
