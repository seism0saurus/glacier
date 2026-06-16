import {
  Component,
  AfterViewInit,
  ElementRef,
  ViewChild,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';

/**
 * Static page shown when a share link has expired or been revoked.
 *
 * Accessibility (VIEW-06 remediation):
 * - Focus lands on the H1 heading on init so screen readers announce the page
 *   title immediately (UX plan §1.2).
 * - The focus move is guarded: if `headingRef` is absent (e.g. the component
 *   is destroyed before AfterViewInit fires), the call is skipped silently.
 * - No assertive live region on this page — the assertive announcement was
 *   already made by ReadonlyWallComponent before navigation (announce-then-
 *   navigate pattern, ADR-RELAY-05). Re-announcing here would double-announce.
 * - "Zur Startseite" link gives users a clear exit — no dead-end page.
 * - Friendly, blame-free message ("nicht mehr aktiv", not "error").
 * - No data fetching — completely static.
 * - Anti-enumeration: 404 and revoked are treated identically (expiry page).
 */
@Component({
  selector: 'app-share-expired',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, RouterModule, MatButtonModule],
  template: `
    <main class="expired-page" aria-labelledby="expired-heading">
      <h1
        #heading
        id="expired-heading"
        tabindex="-1"
        class="expired-title"
        i18n="@@share.expired.title"
      >Dieser Link ist nicht mehr aktiv</h1>

      <p
        class="expired-body"
        i18n="@@share.expired.body"
      >Der geteilte Link ist abgelaufen oder wurde widerrufen.</p>

      <a
        mat-button
        routerLink="/"
        class="expired-home-link"
        i18n="@@share.expired.home"
      >Zur Startseite</a>
    </main>
  `,
  styles: [`
    .expired-page {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 48px 24px;
      min-height: 60vh;
      text-align: center;
    }
    .expired-title {
      font-size: 1.75rem;
      margin-bottom: 16px;
    }
    .expired-body {
      color: var(--mat-sys-on-surface-variant, #666);
      margin-bottom: 32px;
    }
    /* Remove the default focus ring ugliness while keeping it accessible */
    h1:focus { outline: 2px solid var(--mat-sys-primary, #0000ff); outline-offset: 4px; }
  `],
})
export class ShareExpiredComponent implements AfterViewInit {

  @ViewChild('heading') headingRef?: ElementRef<HTMLHeadingElement>;

  /**
   * VIEW-06: Move focus to the H1 heading so screen readers announce the page.
   *
   * Guard: headingRef may be absent if the ViewChild query finds nothing
   * (e.g. under test conditions or if the template is changed). The optional
   * chain (?.) prevents a TypeError in those scenarios.
   *
   * No duplicate assertive announcement: ReadonlyWallComponent already called
   * LiveAnnouncer.announce(..., 'assertive') before navigating here. Calling
   * focus() on a tabindex="-1" heading triggers a polite screen-reader read
   * of the heading text, which is the correct behaviour (orientation, not alarm).
   */
  ngAfterViewInit(): void {
    this.headingRef?.nativeElement?.focus();
  }
}
