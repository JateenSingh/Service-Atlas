import { Injectable, effect, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'service-atlas.theme';

/**
 * Theme state (UX-1: dark by default, light available).
 *
 * The choice is written to the document element rather than to a class on a component, so the
 * canvas, dialogs and any portalled content all read the same tokens.
 */
@Injectable({ providedIn: 'root' })
export class ThemeStore {
  private readonly current = signal<Theme>(readStoredTheme());

  readonly theme = this.current.asReadonly();

  constructor() {
    effect(() => {
      const theme = this.current();
      document.documentElement.setAttribute('data-theme', theme);
      try {
        localStorage.setItem(STORAGE_KEY, theme);
      } catch {
        // Private browsing or a locked-down profile: the theme simply will not persist.
      }
    });
  }

  toggle(): void {
    this.current.update((theme) => (theme === 'dark' ? 'light' : 'dark'));
  }

  set(theme: Theme): void {
    this.current.set(theme);
  }
}

function readStoredTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'dark' || stored === 'light') {
      return stored;
    }
  } catch {
    // ignore
  }
  return 'dark';
}
