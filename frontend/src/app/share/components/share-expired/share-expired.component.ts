import {
  Component,
  OnInit,
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
 * Accessibility:
 * - Focus lands on the H1 heading on init (UX plan §1.2).
 * - No "error" framing — friendly message with no blame.
 * - "Zur Startseite" link for users to continue navigating.
 * - No data fetching — completely static.
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

  @ViewChild('heading') headingRef!: ElementRef<HTMLHeadingElement>;

  ngAfterViewInit(): void {
    // Move focus to heading so screen readers announce immediately
    // (UX plan §1.2: "focus starts on heading")
    this.headingRef.nativeElement.focus();
  }
}
