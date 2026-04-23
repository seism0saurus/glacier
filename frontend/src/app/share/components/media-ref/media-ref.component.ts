import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { SafeUrl } from '@angular/platform-browser';
import { MediaRef } from '../../model/readonly-toot-view';
import { SafeUrlPipe } from '../../pipes/safe-url.pipe';

/**
 * Renders a single media attachment in a read-only toot.
 *
 * Security:
 * - Image src bound via [attr.src]="media.proxyUrl | safeUrl" (SafeUrlPipe validates scheme).
 * - Video/audio src: same pattern.
 * - No [innerHTML] anywhere.
 *
 * Accessibility:
 * - Images: [attr.alt] from media.altText; warning badge when empty.
 * - Video/audio: `controls` attribute + aria-label from description.
 * - All media is NOT auto-playing (no `autoplay` attribute).
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
            <figcaption
              class="media-missing-alt"
              role="alert"
              data-testid="missing-alt-warning"
            >
              <mat-icon aria-hidden="true" fontIcon="warning"></mat-icon>
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
            <figcaption class="media-missing-alt" role="alert" data-testid="missing-alt-warning">
              <mat-icon aria-hidden="true" fontIcon="warning"></mat-icon>
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
            [attr.aria-label]="media.altText"
            class="media-video"
            style="max-width:100%"
            data-testid="media-video"
          >
            <span i18n="@@share.toot.media.video.label">Video: {{ media.altText }}</span>
          </video>
        </figure>
      }
      @case ('audio') {
        <figure class="media-figure">
          <audio
            controls
            [attr.src]="media.proxyUrl | safeUrl"
            [attr.aria-label]="media.altText"
            data-testid="media-audio"
          >
            <span i18n="@@share.toot.media.audio.label">Audio: {{ media.altText }}</span>
          </audio>
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
  `],
})
export class MediaRefComponent {
  @Input({ required: true }) media!: MediaRef;
}
