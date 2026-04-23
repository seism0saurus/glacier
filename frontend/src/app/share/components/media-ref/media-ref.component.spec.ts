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

  describe('missing alt text warning', () => {
    beforeEach(() => {
      component.media = imageNoAlt;
      fixture.detectChanges();
    });

    it('shows missing-alt warning when description is empty', () => {
      const warning = fixture.nativeElement.querySelector('[data-testid="missing-alt-warning"]');
      expect(warning).not.toBeNull();
    });

    it('warning has role=alert for screen readers', () => {
      const warning = fixture.nativeElement.querySelector('[data-testid="missing-alt-warning"]');
      expect(warning.getAttribute('role')).toBe('alert');
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
