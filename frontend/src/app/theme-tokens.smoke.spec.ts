/**
 * Smoke tests for MD3 system-token theme (XCUT-07).
 *
 * These tests verify that the `mat.theme()` mixin has been applied and that
 * the expected CSS custom properties (`--mat-sys-*`) are emitted on the
 * document root. They must FAIL with the legacy prebuilt theme and PASS
 * after the MD3 `styles.scss` is wired into the Karma build.
 *
 * No component fixture is needed: `mat.theme()` applies tokens to the
 * `:root` / `html` selector, so they are visible via `getComputedStyle`
 * on `document.documentElement` in a browser context.
 */
describe('MD3 theme tokens', () => {
  it('exposes --mat-sys-primary on the document root', () => {
    const v = getComputedStyle(document.documentElement)
      .getPropertyValue('--mat-sys-primary')
      .trim();
    expect(v.length).toBeGreaterThan(0);
  });

  it('exposes brand surface token --mat-sys-surface', () => {
    const v = getComputedStyle(document.documentElement)
      .getPropertyValue('--mat-sys-surface')
      .trim();
    expect(v.length).toBeGreaterThan(0);
  });
});
