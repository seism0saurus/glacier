import {MatIconRegistry} from '@angular/material/icon';
import {DomSanitizer} from '@angular/platform-browser';

/**
 * Single source of truth for Glacier's locally-bundled Material-style SVG icons.
 *
 * Why local SVGs (not the Material Icons font)?
 * --------------------------------------------
 * Angular Material's `<mat-icon>` defaults to *font ligatures*
 * (`<mat-icon>share</mat-icon>` / `fontIcon="content_copy"`), which only render
 * once the Material Icons/Symbols webfont is loaded — historically via a
 * `<link href="fonts.googleapis.com/…">` tag (a runtime fetch from Google).
 *
 * Glacier deliberately ships **no** such webfont and must never fetch icons
 * dynamically from Google. Every icon is therefore registered here as a local
 * SVG asset and referenced via `svgIcon="…"` so it is fully self-contained in
 * the Spring Boot jar (the Angular `assets/` tree is copied into
 * `src/main/resources/static/` at build time).
 *
 * The map key is the registered icon name (suffixed `_icon`, matching the
 * existing `cancel_icon` / `trash_icon` / `legal_notice_icon` convention); the
 * value is the asset path resolved against `<base href="/">`, so it is stable
 * regardless of the current route depth (e.g. `/share/:shareId`).
 */
export const GLACIER_ICON_ASSETS: Readonly<Record<string, string>> = {
  share_icon: 'assets/icons/share.svg',
  content_copy_icon: 'assets/icons/content_copy.svg',
  wifi_icon: 'assets/icons/wifi.svg',
  sync_icon: 'assets/icons/sync.svg',
  sync_problem_icon: 'assets/icons/sync_problem.svg',
  cloud_download_icon: 'assets/icons/cloud_download.svg',
  cloud_off_icon: 'assets/icons/cloud_off.svg',
  block_icon: 'assets/icons/block.svg',
  lock_open_icon: 'assets/icons/lock_open.svg',
  warning_icon: 'assets/icons/warning.svg',
  info_icon: 'assets/icons/info.svg',
  close_icon: 'assets/icons/close.svg',
};

/**
 * Registers the requested locally-bundled SVG icons with the (root-singleton)
 * {@link MatIconRegistry}. Components call this from their constructor — exactly
 * like the existing per-component `addSvgIcon` registrations in
 * `FooterComponent` and `HashtagComponent` — passing only the names they use.
 *
 * Registering the same name more than once is harmless: the registry keys on
 * the name, so a repeat registration (e.g. `warning_icon` used by both
 * `cw-toggle` and `media-ref`) simply overwrites with the identical config.
 *
 * @param registry  the Material icon registry (root singleton).
 * @param sanitizer used to mark the local asset path as a trusted resource URL.
 * @param names     icon names to register; defaults to the full set.
 */
export function registerGlacierSvgIcons(
  registry: MatIconRegistry,
  sanitizer: DomSanitizer,
  names: readonly string[] = Object.keys(GLACIER_ICON_ASSETS),
): void {
  for (const name of names) {
    const path = GLACIER_ICON_ASSETS[name];
    if (path) {
      registry.addSvgIcon(name, sanitizer.bypassSecurityTrustResourceUrl(path));
    }
  }
}
