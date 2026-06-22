import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule, MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { MediaRef } from '../../model/readonly-toot-view';
import { SafeUrlPipe } from '../../pipes/safe-url.pipe';
import { registerGlacierSvgIcons } from '../../../icons/glacier-svg-icons';

/**
 * Renders a single media attachment in a read-only toot.
 *
 * Security:
 * - Image src bound via [attr.src]="media.proxyUrl | safeUrl" (SafeUrlPipe validates scheme).
 * - Video/audio src: same pattern.
 * - No [innerHTML] anywhere.
 *
 * Accessibility (VIEW-08 + VIEW-09 remediation):
 *
 * VIEW-08: The missing-alt `<figcaption>` no longer carries `role="alert"`.
 *   Previously this fired on every image render, flooding screen readers with
 *   alerts. The warning is a plain informational `<figcaption>` — not urgent.
 *   WCAG 4.1.3 misuse resolved.
 *
 * VIEW-09a: video/audio `[attr.aria-label]` uses `media.altText || null` so
 *   that an empty altText does NOT produce `aria-label=""` (which forces screen
 *   readers to announce an empty string — worse than absent).
 *
 * VIEW-09b: When video/audio has no altText, a localized "Keine Untertitel
 *   verfügbar" notice is surfaced via `data-testid="no-captions-notice"`.
 *
 * General:
 * - Images: [attr.alt] from media.altText; warning badge when empty.
 * - Video/audio: `controls` attribute + aria-label from description.
 * - All media is NOT auto-playing (no `autoplay` attribute).
 * - `@media (prefers-reduced-motion: reduce)` consideration: no animations
 *   are applied to media elements (the media itself is user-controlled via
 *   the native controls attribute).
 */
@Component({
  selector: 'app-media-ref',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, MatIconModule, SafeUrlPipe],
  template: `
    @switch (media.type) {
      @case ('image') {
        <figure class="media-figure">
          <img
            [attr.src]="media.proxyUrl | safeUrl"
            [attr.alt]="media.altText || null"
            class="media-image"
            loading="lazy"
            style="max-width:100%"
          />
          @if (!media.altText) {
            <!--
              VIEW-08: Plain <figcaption> with NO role="alert".
              role="alert" would announce on every image toot → SR flooding.
              This is a gentle informational notice, not an urgent alert.
            -->
            <figcaption
              class="media-missing-alt"
              data-testid="missing-alt-warning"
            >
              <mat-icon aria-hidden="true" svgIcon="warning_icon"></mat-icon>
              <span i18n="@@share.toot.media.missing-alt">Dieses Bild hat keinen Alternativtext</span>
            </figcaption>
          }
        </figure>
      }
      @case ('gifv') {
        <figure class="media-figure">
          <img
            [attr.src]="media.proxyUrl | safeUrl"
            [attr.alt]="media.altText || null"
            class="media-image"
            loading="lazy"
            style="max-width:100%"
          />
          @if (!media.altText) {
            <!-- VIEW-08: No role="alert" on gifv missing-alt either -->
            <figcaption class="media-missing-alt" data-testid="missing-alt-warning">
              <mat-icon aria-hidden="true" svgIcon="warning_icon"></mat-icon>
              <span i18n="@@share.toot.media.missing-alt">Dieses Bild hat keinen Alternativtext</span>
            </figcaption>
          }
        </figure>
      }
      @case ('video') {
        <figure class="media-figure">
          <video
            controls
            [attr.src]="media.proxyUrl | safeUrl"
            [attr.aria-label]="media.altText || null"
            class="media-video"
            style="max-width:100%"
            data-testid="media-video"
          >
            <!--
              VIEW-09b: Fallback text for browsers without native <video> support.
              Uses altText when available; otherwise a localized "no captions" notice.
            -->
            @if (media.altText) {
              <span i18n="@@share.toot.media.video.label">Video: {{ media.altText }}</span>
            } @else {
              <span i18n="@@share.toot.media.no-captions">Keine Untertitel verfügbar</span>
            }
          </video>
          <!--
            VIEW-09b: Surface a caption/transcript affordance notice when no
            altText is available. Placed outside the <video> element so it is
            always visible in the DOM (not just as <video> fallback content).
          -->
          @if (!media.altText) {
            <figcaption
              class="media-no-captions"
              data-testid="no-captions-notice"
              i18n="@@share.toot.media.no-captions.notice"
            >Keine Beschreibung oder Untertitel verfügbar</figcaption>
          }
        </figure>
      }
      @case ('audio') {
        <figure class="media-figure">
          <audio
            controls
            [attr.src]="media.proxyUrl | safeUrl"
            [attr.aria-label]="media.altText || null"
            data-testid="media-audio"
          >
            @if (media.altText) {
              <span i18n="@@share.toot.media.audio.label">Audio: {{ media.altText }}</span>
            } @else {
              <span i18n="@@share.toot.media.no-captions">Keine Untertitel verfügbar</span>
            }
          </audio>
          @if (!media.altText) {
            <figcaption
              class="media-no-captions"
              data-testid="no-captions-notice"
              i18n="@@share.toot.media.no-captions.notice"
            >Keine Beschreibung oder Untertitel verfügbar</figcaption>
          }
        </figure>
      }
    }
  `,
  styles: [`
    .media-figure { margin: 8px 0; }
    .media-image { display: block; border-radius: 4px; }
    .media-video { display: block; }
    .media-missing-alt {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 0.75rem;
      color: var(--mat-sys-error, red);
      margin-top: 4px;
    }
    .media-no-captions {
      font-size: 0.75rem;
      color: var(--mat-sys-on-surface-variant, #666);
      margin-top: 4px;
      font-style: italic;
    }
    @media (prefers-reduced-motion: reduce) {
      .media-figure * { animation: none; transition: none; }
    }
  `],
})
export class MediaRefComponent {
  @Input({ required: true }) media!: MediaRef;

  constructor(iconRegistry: MatIconRegistry, sanitizer: DomSanitizer) {
    // Local SVG icon — the Material Icons webfont is intentionally not shipped.
    registerGlacierSvgIcons(iconRegistry, sanitizer, ['warning_icon']);
  }
}
