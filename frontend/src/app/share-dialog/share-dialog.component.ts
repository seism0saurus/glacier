import {
  Component,
  OnInit,
  ChangeDetectionStrategy,
  signal,
  computed,
  Inject,
  LOCALE_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogModule, MatDialogRef, MatDialog } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ShareLinkService } from '../share/services/share-link.service';
import { QrCodeComponent } from '../qr-code/qr-code.component';
import {
  ShareLinkCreated,
  ShareLinkEntry,
} from '../share/model/readonly-toot-view';

/**
 * Material dialog for the sharer to create, view, and revoke share links.
 *
 * Accessibility (UX plan §1.1):
 * - Focus moves to dialog title on open (handled by MatDialog).
 * - On close, caller must restore focus to the QR badge.
 * - Revoke confirmation dialog: focus on "Abbrechen" (not the destructive action).
 * - LiveAnnouncer used for all async state changes.
 *
 * CSRF: delegated entirely to ShareLinkService.
 * Rate limiting: 429 snackbar shown by ShareLinkService; dialog just disables button.
 */
@Component({
  selector: 'app-share-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    QrCodeComponent,
  ],
  template: `
    <h1 mat-dialog-title i18n="@@share.dialog.title">Wand teilen</h1>

    <mat-dialog-content>
      <!-- Lead paragraph -->
      <p i18n="@@share.dialog.lead">
        Erstellen Sie eine zugängliche Kopie Ihrer Wand für Leute mit Bildschirmleser oder Zoom.
        Jeder mit dem Link oder QR-Code sieht Ihre Wand.
      </p>

      <!-- Cap reached message -->
      @if (capReached()) {
        <p
          id="cap-message"
          class="cap-message"
          role="alert"
          i18n="@@share.cap.reached"
        >
          Maximal 3 aktive Links. Widerrufen Sie einen vorhandenen Link, um einen neuen zu erstellen.
        </p>
      }

      <!-- Active link display (shown after creation) -->
      @if (activeLink()) {
        <div class="created-link" data-testid="created-link">
          <!-- QR code -->
          <app-qr-code
            [url]="activeLink()!.readonlyUrl"
            [ariaLabel]="qrAriaLabel"
          ></app-qr-code>

          <!-- URL copy field.
               TOOT-07: The URL input is described by the shown-once warning
               below via aria-describedby="share-url-shown-once-hint" so AT
               reads the hint when the input receives focus. This replaces the
               previous role="alert" which fired simultaneously with the
               LiveAnnouncer call and focus-move, producing triple announcement.
               TOOT-05: The copy icon-button has a CSS min-size guard
               (min-width/min-height: 40px via mat-icon-button default, plus
               the .copy-btn-min-size class below) to ensure ≥24px target at
               narrow flex widths (WCAG 2.5.8). -->
          <div class="url-copy-row">
            <mat-form-field appearance="outline" class="url-field">
              <mat-label i18n="@@share.dialog.url.label">Link kopieren</mat-label>
              <input
                matInput
                readonly
                [value]="activeLink()!.readonlyUrl"
                aria-describedby="share-url-shown-once-hint"
                data-testid="share-url-input"
              />
            </mat-form-field>
            <button
              mat-icon-button
              type="button"
              (click)="copyUrl()"
              [attr.aria-label]="copyButtonLabel"
              class="copy-btn-min-size"
              data-testid="copy-button"
            >
              <mat-icon fontIcon="content_copy"></mat-icon>
            </button>
          </div>

          <!-- Shown-once warning (SR-SQLITE-21): URL is only displayed at creation time.
               TOOT-07: Changed from role="alert" to a persistent help text element.
               role="alert" fired on DOM insertion, simultaneously with the single
               LiveAnnouncer.announce() call in createLink() and the focus-move to the
               URL input, causing ~3 simultaneous announcements.
               Now: persistent text with id="share-url-shown-once-hint", associated
               with the URL input above via aria-describedby. AT reads it when the
               input receives focus. The LiveAnnouncer call in createLink() remains
               the sole dynamic announcement. -->
          <p
            id="share-url-shown-once-hint"
            class="shown-once-warning"
            i18n="@@share.dialog.url.shown-once.warning"
          >Wichtig: Dieser Link wird nur jetzt angezeigt und kann später nicht erneut abgerufen werden. Bitte kopiere ihn jetzt.</p>

          <!-- Expiry -->
          <p class="expiry-text" aria-live="off">
            <span i18n="@@share.dialog.expires">Läuft ab am </span>
            <time [attr.datetime]="activeLink()!.expiresAt">
              {{ formatExpiry(activeLink()!.expiresAt) }}
            </time>
          </p>

          <!-- Revoke button -->
          <button
            mat-stroked-button
            type="button"
            color="warn"
            (click)="confirmRevoke(activeLink()!.shareLinkId)"
            data-testid="revoke-button"
            i18n="@@share.dialog.revoke"
          >Zugriff entziehen</button>
        </div>
      }

      <!-- List of existing active links -->
      @if (existingLinks().length > 0) {
        <section aria-labelledby="active-links-heading">
          <h2
            id="active-links-heading"
            class="section-heading"
            i18n="@@share.dialog.active-links.heading"
          >Aktive Links</h2>
          @for (link of existingLinks(); track link.shareLinkId; let i = $index) {
            <div class="link-row" data-testid="link-row">
              <span class="link-expiry">{{ formatExpiry(link.expiresAt) }}</span>
              <!--
                TOOT-14: multiple "Widerrufen" buttons are indistinguishable to AT
                (SR-SQLITE-21: URL not shown). Give each button a unique aria-label
                that encodes an ordinal and creation-date so AT users can tell them
                apart. The URL is intentionally absent from the label per SR-SQLITE-21.
              -->
              <button
                mat-stroked-button
                type="button"
                color="warn"
                (click)="confirmRevoke(link.shareLinkId)"
                [attr.aria-label]="revokeButtonLabel(i, link.expiresAt)"
                data-testid="revoke-row-button"
              >
                <span i18n="@@share.dialog.revoke.button.label">Widerrufen</span>
              </button>
            </div>
          }
        </section>
      }

      <!-- Error message -->
      @if (csrfError()) {
        <p role="alert" class="error-message" i18n="@@share.dialog.csrf.failed">
          Verbindung verloren. Dialog schließen und erneut öffnen.
        </p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <!-- Create link button -->
      @if (!activeLink() && !loading()) {
        <button
          mat-flat-button
          type="button"
          color="primary"
          (click)="createLink()"
          [disabled]="capReached()"
          [attr.aria-describedby]="capReached() ? 'cap-message' : null"
          data-testid="create-button"
          i18n="@@share.dialog.create.button"
        >Link erstellen</button>
      }

      @if (loading()) {
        <mat-progress-spinner
          mode="indeterminate"
          diameter="24"
          i18n-aria-label="@@share.dialog.creating.aria"
          aria-label="Erstelle Link…"
        ></mat-progress-spinner>
      }

      <!-- Close button -->
      <button
        mat-button
        type="button"
        mat-dialog-close
        data-testid="close-button"
        i18n="@@share.dialog.close"
      >Schließen</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .created-link { display: flex; flex-direction: column; gap: 12px; margin: 16px 0; }
    .url-copy-row { display: flex; align-items: center; gap: 8px; }
    .url-field { flex: 1; }
    /* TOOT-05: ensure copy icon-button never shrinks below WCAG 2.5.8 minimum
       target size (24 × 24 CSS px) when the flex row is at narrow widths.
       mat-icon-button default is 40px; this guard prevents flex-shrink: 0 being
       overridden by ancestor flex rules in narrow containers. */
    .copy-btn-min-size { flex-shrink: 0; min-width: 40px; min-height: 40px; }
    .shown-once-warning { font-size: 0.875rem; font-weight: 500; color: var(--mat-sys-error, #b3261e); margin: 0; }
    .expiry-text { font-size: 0.875rem; margin: 0; }
    .cap-message { color: var(--mat-sys-error, red); }
    .error-message { color: var(--mat-sys-error, red); }
    .section-heading { font-size: 1rem; margin: 16px 0 8px; }
    .link-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
    .link-expiry { flex: 1; font-size: 0.875rem; }
    .visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
  `],
})
export class ShareDialogComponent implements OnInit {

  activeLink = signal<ShareLinkCreated | null>(null);
  existingLinks = signal<ShareLinkEntry[]>([]);
  loading = signal(false);
  capReached = signal(false);
  csrfError = signal(false);

  get qrAriaLabel(): string {
    return $localize`:@@share.qr.hint:Geteilten Link anzeigen`;
  }

  get copyButtonLabel(): string {
    return $localize`:@@share.dialog.copy.button:Kopieren`;
  }

  constructor(
    private dialogRef: MatDialogRef<ShareDialogComponent>,
    private shareLinkService: ShareLinkService,
    private dialog: MatDialog,
    private liveAnnouncer: LiveAnnouncer,
    @Inject(LOCALE_ID) private locale: string,
  ) {}

  ngOnInit(): void {
    this.loadExistingLinks();
  }

  private loadExistingLinks(): void {
    this.shareLinkService.listShareLinks().subscribe({
      next: (links) => this.existingLinks.set(links),
      error: () => { /* Non-critical; list is optional. */ },
    });
  }

  createLink(): void {
    this.loading.set(true);
    this.csrfError.set(false);

    this.shareLinkService.createShareLink().subscribe({
      next: (created) => {
        this.loading.set(false);
        this.activeLink.set(created);
        this.liveAnnouncer.announce(
          $localize`:@@share.dialog.created.announce:Link erstellt. Läuft in 7 Tagen ab.`,
          'polite',
        );
        // Move focus to URL field via timeout to allow Angular to render
        setTimeout(() => {
          const input = document.querySelector<HTMLInputElement>('[data-testid="share-url-input"]');
          input?.focus();
          input?.select();
        }, 100);
      },
      error: (err) => {
        this.loading.set(false);
        if (err?.status === 0 || err?.status >= 500) {
          this.csrfError.set(true);
        }
        // 429 is handled by ShareLinkService snackbar
      },
    });
  }

  copyUrl(): void {
    const url = this.activeLink()?.readonlyUrl;
    if (!url) return;

    if (navigator.clipboard?.writeText) {
      // Secure path: use the async Clipboard API.
      navigator.clipboard.writeText(url).then(() => {
        this.liveAnnouncer.announce(
          $localize`:@@share.dialog.copied.announce:Link kopiert`,
          'polite',
        );
      });
    } else {
      // TOOT-06: Insecure-transport fallback (no Clipboard API — a first-class
      // Glacier mode per glacier-fallback-mode-discipline).
      // Previously only called select(), with no copy attempt and no announcement.
      // Now:
      //   1. select() the input for visual feedback.
      //   2. Attempt document.execCommand('copy') (legacy synchronous API).
      //   3. Announce success or a keyboard-copy instruction to AT users.
      const input = document.querySelector<HTMLInputElement>('[data-testid="share-url-input"]');
      input?.select();

      const copied = input ? document.execCommand('copy') : false;

      if (copied) {
        this.liveAnnouncer.announce(
          $localize`:@@share.dialog.copy.fallback.announce:Link kopiert`,
          'polite',
        );
      } else {
        // Neither API worked; announce a keyboard-copy instruction so keyboard /
        // AT users know what to do next (WCAG 3.3.1 — error identification).
        this.liveAnnouncer.announce(
          $localize`:@@share.dialog.copy.keyboard.hint:Markiert — bitte mit Strg+C kopieren`,
          'polite',
        );
      }
    }
  }

  confirmRevoke(shareLinkId: string): void {
    const ref = this.dialog.open(ShareRevokeConfirmDialogComponent);
    ref.afterClosed().subscribe((confirmed: boolean) => {
      if (confirmed) {
        this.executeRevoke(shareLinkId);
      }
    });
  }

  private executeRevoke(shareLinkId: string): void {
    // TOOT-11: announce in-progress state before the HTTP call completes so AT
    // users know the action is underway (WCAG 4.1.3 / Nielsen #1).
    this.liveAnnouncer.announce(
      $localize`:@@share.dialog.revoke.in-progress.announce:Widerrufe Link…`,
      'polite',
    );

    this.shareLinkService.revokeShareLink(shareLinkId).subscribe({
      complete: () => {
        // Remove from local lists
        this.existingLinks.update((list) =>
          list.filter((l) => l.shareLinkId !== shareLinkId)
        );
        if (this.activeLink()?.shareLinkId === shareLinkId) {
          this.activeLink.set(null);
        }
        this.liveAnnouncer.announce(
          $localize`:@@share.dialog.revoked.announce:Link widerrufen`,
          'assertive',
        );
      },
      error: (err) => {
        if (err?.status === 404) {
          this.liveAnnouncer.announce(
            $localize`:@@share.dialog.revoke.already-expired.announce:Link war bereits abgelaufen und wurde entfernt`,
            'polite',
          );
          // 404: link is already gone — clean up from list
          this.existingLinks.update((list) =>
            list.filter((l) => l.shareLinkId !== shareLinkId)
          );
        } else {
          // TOOT-11: non-404 failure (500 / network error).
          // Keep the row in the list — DO NOT optimistically remove it.
          // Announce a generic failure so AT users can retry.
          this.liveAnnouncer.announce(
            $localize`:@@share.dialog.revoke.failed.announce:Widerruf fehlgeschlagen. Bitte erneut versuchen.`,
            'assertive',
          );
        }
      },
    });
  }

  /**
   * TOOT-14: Generates a unique accessible label for a "Widerrufen" button in
   * the active-links list, so screen-reader users can tell buttons apart.
   *
   * SR-SQLITE-21: the link URL must NOT appear in the label. We use an ordinal
   * (1-based) and the expiry date as the human label. Example:
   *   "Link 1 widerrufen — läuft ab am 15.06.2026"
   *
   * @param index - 0-based index of the link in existingLinks()
   * @param expiresAt - ISO date string for the link's expiry
   */
  revokeButtonLabel(index: number, expiresAt: string): string {
    const ordinal = index + 1;
    const formattedDate = this.formatExpiry(expiresAt);
    return $localize`:@@share.dialog.revoke.button.label.aria:Link ${ordinal} widerrufen — läuft ab am ${formattedDate}`;
  }

  formatExpiry(isoDate: string): string {
    try {
      return new Intl.DateTimeFormat(this.locale, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      }).format(new Date(isoDate));
    } catch {
      return isoDate;
    }
  }
}

/**
 * Minimal confirmation dialog for the revoke action.
 *
 * Focus starts on "Abbrechen" (not the destructive action) per WCAG 3.3.4.
 */
@Component({
  selector: 'app-share-revoke-confirm-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h1 mat-dialog-title i18n="@@share.dialog.revoke.confirm.title">Link widerrufen?</h1>
    <mat-dialog-content>
      <p i18n="@@share.dialog.revoke.confirm.body">
        Alle Personen, die diesen Link nutzen, verlieren sofort den Zugriff.
        Dies lässt sich nicht rückgängig machen.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <!-- Cancel gets focus first (WCAG 3.3.4 — destructive action not default) -->
      <button
        mat-button
        type="button"
        [mat-dialog-close]="false"
        cdkFocusInitial
        data-testid="revoke-cancel"
        i18n="@@share.dialog.revoke.confirm.cancel"
      >Abbrechen</button>
      <button
        mat-flat-button
        type="button"
        color="warn"
        [mat-dialog-close]="true"
        data-testid="revoke-confirm"
        i18n="@@share.dialog.revoke.confirm.confirm"
      >Widerrufen</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShareRevokeConfirmDialogComponent {}
