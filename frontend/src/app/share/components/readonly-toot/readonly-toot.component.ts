import {
  Component,
  Input,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule, MatIconRegistry } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SafeUrl, DomSanitizer } from '@angular/platform-browser';
import { registerGlacierSvgIcons } from '../../../icons/glacier-svg-icons';
import { ReadonlyTootView } from '../../model/readonly-toot-view';
import { SafeUrlPipe } from '../../pipes/safe-url.pipe';
import { MediaRefComponent } from '../media-ref/media-ref.component';
import { PollRefComponent } from '../poll-ref/poll-ref.component';
import { CwToggleComponent } from '../cw-toggle/cw-toggle.component';

/**
 * Renders one `ReadonlyTootView` as structured native HTML.
 *
 * Security contract (ADR-SHARE-03):
 * - ALL user-supplied content rendered via {{ interpolation }} ONLY.
 * - NO [innerHTML] anywhere in this component or its children.
 * - Links bound via [attr.href]="url | safeUrl" — null href suppresses
 *   the attribute entirely when the URL fails validation.
 * - External links carry target="_blank" rel="noopener noreferrer".
 * - `dir=auto` on text container defends against bidi override spoofing.
 *
 * Accessibility contract (UX plan §2):
 * - `<article role="article" aria-labelledby>` per toot — matches the main wall's
 *   `WallComponent` convention (`role="feed"` requires `role="article"` children
 *   per WAI-ARIA 1.2 §5.3.2; `role="group"` here previously tripped axe-core's
 *   `aria-required-children` rule once a share link with real toot content was
 *   reachable in the a11y e2e stack — see share-link-a11y.spec.ts).
 * - `<h3>` heading with author + time.
 * - `<time datetime>` element.
 * - Bidi-stripped badge when `toot.bidiStripped=true`.
 * - CW toggle rendered by CwToggleComponent.
 */
@Component({
  selector: 'app-readonly-toot',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    MatIconModule,
    MatTooltipModule,
    SafeUrlPipe,
    MediaRefComponent,
    PollRefComponent,
    CwToggleComponent,
  ],
  template: `
    <article
      role="article"
      [attr.aria-labelledby]="headingId"
      class="readonly-toot"
    >
      <!-- Heading — identifies the toot for screen reader landmark navigation.
           The author handle and time are interpolated OUTSIDE the i18n message: an
           HTML i18n attribute wrapping a {{ }} interpolation serializes the placeholder
           as {$INTERPOLATION}, which the runtime catalog (loadTranslations) would have
           to match exactly — a bare {acct}/{time} in messages.en.json is parsed as a
           malformed ICU expression and throws at render. Keeping the message text
           placeholder-free (as with share.readonly.expires) avoids that entirely while
           the screen reader still reads "Toot by <acct> — <time>". -->
      <h3 [id]="headingId">
        <span class="visually-hidden"
          ><span i18n="@@share.toot.heading">Toot von</span> {{ toot.authorAcct }} — </span>
        <time [attr.datetime]="toot.createdAt">{{ formattedTime }}</time>
      </h3>

      <!-- Author info -->
      <div class="toot-author">
        <!-- Avatar: always use proxied URL from DTO; safeUrlPipe provides defence-in-depth -->
        <img
          [attr.src]="toot.authorAvatarProxyUrl | safeUrl"
          [attr.alt]="avatarAlt"
          class="toot-avatar"
          width="48"
          height="48"
          loading="lazy"
        />
        <div class="toot-author-names">
          <!-- authorDisplayName: interpolation — no innerHTML -->
          <span class="toot-display-name">{{ toot.authorDisplayName }}</span>
          <span class="toot-acct">{{ toot.authorAcct }}</span>
        </div>
      </div>

      <!-- Bidi-stripped badge (security notice per UX plan §2) -->
      @if (toot.bidiStripped) {
        <span
          data-testid="bidi-stripped-badge"
          class="bidi-badge"
          [matTooltip]="bidiTooltipText"
          [attr.aria-label]="bidiAriaLabel"
        >
          <mat-icon aria-hidden="true" svgIcon="info_icon"></mat-icon>
          <span i18n="@@share.toot.bidi.stripped.label">Richtungszeichen entfernt</span>
        </span>
      }

      <!-- Content warning toggle (rendered by CwToggleComponent) -->
      @if (toot.spoilerText) {
        <app-cw-toggle
          [tootId]="toot.id"
          [spoilerText]="toot.spoilerText"
          data-testid="cw-toggle"
        ></app-cw-toggle>
      }

      <!-- Toot text body — dir=auto defends against bidi override spoofing -->
      <p
        data-testid="toot-text"
        dir="auto"
        class="toot-text"
      >{{ toot.textContent }}</p>

      <!-- Inline links extracted from toot body -->
      @if (toot.links && toot.links.length > 0) {
        <ul class="toot-links" aria-label="Links in diesem Toot">
          @for (link of toot.links; track link.url) {
            <li>
              <!-- href is null when safeUrlPipe rejects the URL; Angular omits null attributes -->
              <a
                [attr.href]="link.url | safeUrl"
                target="_blank"
                rel="noopener noreferrer"
                [attr.aria-description]="openOriginalLabel"
              >{{ link.displayText }}</a>
            </li>
          }
        </ul>
      }

      <!-- Media attachments -->
      @if (toot.media && toot.media.length > 0) {
        <div class="toot-media-list" role="list">
          @for (media of toot.media; track media.proxyUrl) {
            <app-media-ref [media]="media" role="listitem"></app-media-ref>
          }
        </div>
      }

      <!-- Poll (read-only) -->
      @if (toot.poll) {
        <app-poll-ref [poll]="toot.poll"></app-poll-ref>
      }

      <!-- Open original link -->
      <div class="toot-actions">
        <a
          [attr.href]="toot.authorProfileUrl | safeUrl"
          target="_blank"
          rel="noopener noreferrer"
          i18n="@@share.toot.open-original"
          [attr.aria-description]="openOriginalLabel"
        >Original öffnen</a>
      </div>
    </article>
  `,
  styles: [`
    .readonly-toot {
      padding: var(--mat-sys-spacing, 16px);
      border-bottom: 1px solid var(--mat-sys-outline-variant, #ccc);
    }
    .toot-author {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 8px;
    }
    .toot-avatar {
      border-radius: 50%;
      object-fit: cover;
    }
    .toot-author-names {
      display: flex;
      flex-direction: column;
    }
    .toot-display-name { font-weight: bold; }
    .toot-acct { color: var(--mat-sys-on-surface-variant, #666); font-size: 0.875rem; }
    .toot-text { white-space: pre-wrap; word-break: break-word; }
    .bidi-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 0.75rem;
      color: var(--mat-sys-secondary, #555);
      margin-bottom: 8px;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      overflow: hidden;
      clip: rect(0,0,0,0);
      white-space: nowrap;
      border: 0;
    }
    .toot-links { padding-left: 16px; }
    .toot-actions { margin-top: 8px; }
  `],
})
export class ReadonlyTootComponent {

  @Input({ required: true }) toot!: ReadonlyTootView;

  constructor(iconRegistry: MatIconRegistry, sanitizer: DomSanitizer) {
    // Local SVG icon — the Material Icons webfont is intentionally not shipped.
    registerGlacierSvgIcons(iconRegistry, sanitizer, ['info_icon']);
  }

  /** Unique heading ID to satisfy aria-labelledby. */
  get headingId(): string {
    return `toot-heading-${this.toot.id}`;
  }

  /** Accessible avatar alt text (German source per i18n discipline). */
  get avatarAlt(): string {
    return $localize`:@@share.toot.avatar.fallback.label:Avatar von ${this.toot.authorAcct}`;
  }

  /** Accessible "opens in new tab" description (German source). */
  get openOriginalLabel(): string {
    return $localize`:@@share.toot.open-original:Original öffnen (öffnet in neuem Tab)`;
  }

  get bidiAriaLabel(): string {
    return $localize`:@@share.toot.bidi.stripped.label:Richtungszeichen entfernt`;
  }

  get bidiTooltipText(): string {
    return $localize`:@@share.toot.bidi.stripped.tooltip:Dieser Toot enthielt bidirektionale Steuerzeichen, die aus Sicherheitsgründen entfernt wurden.`;
  }

  get formattedTime(): string {
    try {
      return new Intl.DateTimeFormat(undefined, {
        hour: '2-digit',
        minute: '2-digit',
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      }).format(new Date(this.toot.createdAt));
    } catch {
      return this.toot.createdAt;
    }
  }
}
