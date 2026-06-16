import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MediaRefComponent } from './media-ref.component';
import { MediaRef } from '../../model/readonly-toot-view';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('MediaRefComponent', () => {
  let component: MediaRefComponent;
  let fixture: ComponentFixture<MediaRefComponent>;

  const imageMedia: MediaRef = {
    proxyUrl: 'https://example.com/image.jpg',
    type: 'image',
    altText: 'A beautiful sunset',
  };

  const videoMedia: MediaRef = {
    proxyUrl: 'https://example.com/video.mp4',
    type: 'video',
    altText: 'Conference talk recording',
  };

  const audioMedia: MediaRef = {
    proxyUrl: 'https://example.com/audio.mp3',
    type: 'audio',
    altText: 'Podcast episode',
  };

  const imageNoAlt: MediaRef = {
    proxyUrl: 'https://example.com/no-alt.jpg',
    type: 'image',
    altText: '',
  };

  const videoNoAlt: MediaRef = {
    proxyUrl: 'https://example.com/video-no-alt.mp4',
    type: 'video',
    altText: '',
  };

  const audioNoAlt: MediaRef = {
    proxyUrl: 'https://example.com/audio-no-alt.mp3',
    type: 'audio',
    altText: '',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MediaRefComponent],
      providers: [provideAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(MediaRefComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    component.media = imageMedia;
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  describe('image rendering', () => {
    beforeEach(() => {
      component.media = imageMedia;
      fixture.detectChanges();
    });

    it('renders <img> for image type', () => {
      const img = fixture.nativeElement.querySelector('img');
      expect(img).not.toBeNull();
    });

    it('has alt text from description', () => {
      const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
      expect(img.alt).toBe('A beautiful sunset');
    });

    it('does NOT autoplay', () => {
      const img: HTMLImageElement = fixture.nativeElement.querySelector('img');
      expect(img.hasAttribute('autoplay')).toBeFalse();
    });
  });

  // ---- VIEW-08: missing-alt figcaption must NOT have role="alert" ----
  // WCAG 4.1.3 misuse: role="alert" fires on every image toot causing SR flooding.
  // The figcaption is a plain informational notice, not an urgent alert.

  describe('VIEW-08 missing alt warning has no role=alert', () => {
    beforeEach(() => {
      component.media = imageNoAlt;
      fixture.detectChanges();
    });

    it('VIEW-08 shows missing-alt warning when description is empty', () => {
      const warning = fixture.nativeElement.querySelector('[data-testid="missing-alt-warning"]');
      expect(warning).not.toBeNull();
    });

    it('VIEW-08 missing_alt_warning_has_no_role_alert — figcaption must NOT have role="alert"', () => {
      // role="alert" fires on every image render, flooding screen readers.
      // The warning should be a plain <figcaption> with no role or role="none".
      const warning = fixture.nativeElement.querySelector('[data-testid="missing-alt-warning"]');
      expect(warning).not.toBeNull();
      const role = warning.getAttribute('role');
      expect(role).not.toBe('alert');
    });

    it('VIEW-08 missing_alt_warning_renders_as_figcaption — the warning is in a <figcaption> element', () => {
      const warning = fixture.nativeElement.querySelector('[data-testid="missing-alt-warning"]');
      expect(warning.tagName.toLowerCase()).toBe('figcaption');
    });

    it('VIEW-08 gifv_missing_alt_has_no_role_alert — gifv missing-alt also has no role="alert"', () => {
      component.media = { ...imageNoAlt, type: 'gifv' };
      fixture.detectChanges();
      const warning = fixture.nativeElement.querySelector('[data-testid="missing-alt-warning"]');
      expect(warning).not.toBeNull();
      expect(warning.getAttribute('role')).not.toBe('alert');
    });
  });

  // ---- VIEW-09: video/audio aria-label null when no altText + caption affordance ----
  // WCAG 1.2.2 / 1.2.3: video/audio with no altText must NOT have aria-label=""
  // (empty string is worse than absent). Caption/transcript affordance required.

  describe('VIEW-09 video/audio aria-label and caption affordance', () => {

    it('VIEW-09 video_with_alt_has_aria_label — video with altText has non-empty aria-label', () => {
      component.media = videoMedia;
      fixture.detectChanges();
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      expect(video.getAttribute('aria-label')).toBe('Conference talk recording');
    });

    it('VIEW-09 video_without_alt_has_null_aria_label — video with empty altText has NO aria-label attribute', () => {
      // aria-label="" is worse than absent — it forces SRs to announce an empty string
      component.media = videoNoAlt;
      fixture.detectChanges();
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      // getAttribute returns null when the attribute is absent; "" is NOT acceptable
      const ariaLabel = video.getAttribute('aria-label');
      expect(ariaLabel === null || ariaLabel === undefined).toBeTrue();
    });

    it('VIEW-09 audio_with_alt_has_aria_label — audio with altText has non-empty aria-label', () => {
      component.media = audioMedia;
      fixture.detectChanges();
      const audio: HTMLAudioElement = fixture.nativeElement.querySelector('[data-testid="media-audio"]');
      expect(audio.getAttribute('aria-label')).toBe('Podcast episode');
    });

    it('VIEW-09 audio_without_alt_has_null_aria_label — audio with empty altText has NO aria-label attribute', () => {
      component.media = audioNoAlt;
      fixture.detectChanges();
      const audio: HTMLAudioElement = fixture.nativeElement.querySelector('[data-testid="media-audio"]');
      const ariaLabel = audio.getAttribute('aria-label');
      expect(ariaLabel === null || ariaLabel === undefined).toBeTrue();
    });

    it('VIEW-09 video_has_controls_attribute — video always has controls so keyboard users can operate it', () => {
      component.media = videoMedia;
      fixture.detectChanges();
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      expect(video.hasAttribute('controls')).toBeTrue();
    });

    it('VIEW-09 audio_has_controls_attribute — audio always has controls so keyboard users can operate it', () => {
      component.media = audioMedia;
      fixture.detectChanges();
      const audio: HTMLAudioElement = fixture.nativeElement.querySelector('[data-testid="media-audio"]');
      expect(audio.hasAttribute('controls')).toBeTrue();
    });

    it('VIEW-09 video_does_not_autoplay', () => {
      component.media = videoMedia;
      fixture.detectChanges();
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      expect(video.hasAttribute('autoplay')).toBeFalse();
    });

    it('VIEW-09 video_no_caption_shows_localized_notice — video without alt shows a "no captions" notice', () => {
      component.media = videoNoAlt;
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      // A localized notice about missing captions/subtitles must appear
      const captionNotice = el.querySelector('[data-testid="no-captions-notice"]');
      expect(captionNotice).not.toBeNull();
    });

    it('VIEW-09 video_with_caption_shows_description — video with altText does NOT show the "no captions" notice', () => {
      component.media = videoMedia;
      fixture.detectChanges();
      const captionNotice = fixture.nativeElement.querySelector('[data-testid="no-captions-notice"]');
      expect(captionNotice).toBeNull();
    });
  });

  describe('video rendering', () => {
    beforeEach(() => {
      component.media = videoMedia;
      fixture.detectChanges();
    });

    it('renders <video> with controls attribute', () => {
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      expect(video).not.toBeNull();
      expect(video.hasAttribute('controls')).toBeTrue();
    });

    it('has aria-label from description', () => {
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      expect(video.getAttribute('aria-label')).toBe('Conference talk recording');
    });

    it('does NOT autoplay', () => {
      const video: HTMLVideoElement = fixture.nativeElement.querySelector('[data-testid="media-video"]');
      expect(video.hasAttribute('autoplay')).toBeFalse();
    });
  });

  describe('audio rendering', () => {
    beforeEach(() => {
      component.media = audioMedia;
      fixture.detectChanges();
    });

    it('renders <audio> with controls attribute', () => {
      const audio: HTMLAudioElement = fixture.nativeElement.querySelector('[data-testid="media-audio"]');
      expect(audio).not.toBeNull();
      expect(audio.hasAttribute('controls')).toBeTrue();
    });

    it('has aria-label from description', () => {
      const audio: HTMLAudioElement = fixture.nativeElement.querySelector('[data-testid="media-audio"]');
      expect(audio.getAttribute('aria-label')).toBe('Podcast episode');
    });
  });

  describe('XSS: no javascript: in src', () => {
    it('does NOT render javascript: src on image', () => {
      component.media = { ...imageMedia, proxyUrl: 'javascript:alert(1)' };
      fixture.detectChanges();
      const img: HTMLImageElement | null = fixture.nativeElement.querySelector('img');
      if (img) {
        expect(img.getAttribute('src') ?? '').not.toContain('javascript:');
      }
    });
  });
});
