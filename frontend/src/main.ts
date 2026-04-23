/**
 * Application entry point.
 *
 * Localisation (D-18, ADR-08)
 * ---------------------------
 * The default compile-time locale is `de` (German) — all new user-visible
 * strings are authored in German using `$localize` tagged template literals.
 * For English-speaking users (`navigator.language` starts with "en") the
 * English catalogue is loaded at runtime from `/assets/i18n/messages.en.json`
 * via fetch so that strings are available before Angular bootstraps.
 *
 * This keeps a single build artefact (compatible with frontend-maven-plugin →
 * ng build pipeline) and avoids per-locale bundle explosion.
 */
import '@angular/localize/init';
import {loadTranslations} from '@angular/localize';

import {platformBrowserDynamic} from '@angular/platform-browser-dynamic';
import {AppModule} from './app/app.module';

/**
 * Loads the English translation catalogue from the assets directory and
 * applies it with `loadTranslations()` before Angular bootstraps.
 * Returns immediately for all other locales (German strings are the default).
 */
async function loadI18n(): Promise<void> {
  const lang = (navigator.language ?? '').toLowerCase();
  if (!lang.startsWith('en')) {
    // Default locale is de — no catalogue to load
    return;
  }

  try {
    const response = await fetch('/assets/i18n/messages.en.json');
    if (!response.ok) {
      console.warn('[i18n] Could not load messages.en.json — falling back to de strings');
      return;
    }
    const translations: Record<string, string> = await response.json();
    loadTranslations(translations);
  } catch (err) {
    // Non-fatal: if the catalogue fails to load, the German default strings are shown
    console.warn('[i18n] Failed to load English catalogue', err);
  }
}

loadI18n().then(() => {
  platformBrowserDynamic()
    .bootstrapModule(AppModule)
    .catch(err => console.error(err));
});
