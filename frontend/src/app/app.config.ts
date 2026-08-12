import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';

export const appConfig: ApplicationConfig = {
  providers: [
    // Event coalescing: pointer-heavy canvas interaction generates a lot of events, and there is
    // no reason to run change detection for each one separately.
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideHttpClient(withFetch()),
  ],
};
