import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ReadonlyTootComponent } from './readonly-toot.component';
import { SafeUrlPipe } from '../../pipes/safe-url.pipe';
import { ReadonlyTootView, LinkRef } from '../../model/readonly-toot-view';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

/**
 * Exhaustive XSS corpus + accessibility tests for ReadonlyTootComponent.
 *
 * Security principle: the component MUST use {{ interpolation }} for all
 * user-supplied content.  No [innerHTML].  Links use [attr.href] + safeUrlPipe.
 *
 * These tests verify that malicious DTO inputs are rendered as literal text
 * (HTML-escaped by Angular's interpolation) and never executed.
 */
describe('ReadonlyTootComponent', () => {
  let component: ReadonlyTootComponent;
  let fixture: ComponentFixture<ReadonlyTootComponent>;

  /** Minimum valid toot used as baseline for XSS corpus tests. */
  const baseToot: ReadonlyTootView = {
    id: 'safe-1',
    authorDisplayName: 'Alice',
    authorAcct: '@alice@example.com',
    authorProfileUrl: 'https://example.com/@alice',
    authorAvatarProxyUrl: 'https://example.com/avatar.png',
    createdAt: '2026-04-22T14:32:00Z',
    textContent: 'Hello world',
    spoilerText: '',
    sensitive: false,
    bidiStripped: false,
    links: [],
    mentions: [],
    hashtags: [],
    customEmojis: [],
    media: [],
    poll: null,
    language: 'en',
  };

  /**
   * Sets the toot @Input and triggers change detection.
   *
   * Uses fixture.componentRef.setInput() so Angular's OnPush strategy
   * correctly marks the component as dirty and re-renders. Direct property
   * assignment (component.toot = ...) does not notify Angular of the change
   * under OnPush, so the template would not update.
   */
  function setToot(overrides: Partial<ReadonlyTootView>): void {
    fixture.componentRef.setInput('toot', { ...baseToot, ...overrides });
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReadonlyTootComponent, SafeUrlPipe],
      providers: [
        provideAnimations(),
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReadonlyTootComponent);
    component = fixture.componentInstance;
    component.toot = baseToot;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders the bidi-stripped info badge as a local SVG icon (not a font ligature)', () => {
    setToot({ bidiStripped: true });
    const icon: HTMLElement = fixture.nativeElement.querySelector('mat-icon');
    expect(icon).withContext('bidi info icon must exist').toBeTruthy();
    expect(icon.getAttribute('data-mat-icon-type'))
      .withContext('icon must use the local svgIcon path, not a font ligature')
      .toBe('svg');
    expect(icon.getAttribute('data-mat-icon-name')).toBe('info_icon');
  });

  // ---- Semantic structure ----

  it('renders an <article> as root element', () => {
    const article = fixture.debugElement.query(By.css('article'));
    expect(article).not.toBeNull();
  });

  it('article has role="group" and aria-labelledby pointing to heading', () => {
    const article = fixture.nativeElement.querySelector('article');
    expect(article.getAttribute('role')).toBe('group');
    const labelledBy = article.getAttribute('aria-labelledby');
    const heading = fixture.nativeElement.querySelector(`#${labelledBy}`);
    expect(heading).not.toBeNull();
  });

  it('renders a <time> element with datetime attribute', () => {
    const time = fixture.nativeElement.querySelector('time');
    expect(time).not.toBeNull();
    expect(time.getAttribute('datetime')).toBe('2026-04-22T14:32:00Z');
  });

  // ---- XSS corpus: authorDisplayName ----

  describe('authorDisplayName XSS corpus', () => {
    it('renders <script> in display name as literal text, not HTML', () => {
      setToot({ authorDisplayName: '<script>alert(1)</script>' });
      const el: HTMLElement = fixture.nativeElement;
      // Must NOT contain an actual <script> element
      expect(el.querySelector('script')).toBeNull();
      // The raw string should appear as text content
      expect(el.textContent).toContain('<script>alert(1)</script>');
    });

    it('renders onerror= in display name as literal text', () => {
      setToot({ authorDisplayName: '<img src=x onerror=alert(1)>' });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('img[onerror]')).toBeNull();
    });

    it('renders HTML entities in display name as literal text', () => {
      setToot({ authorDisplayName: '&lt;b&gt;bold&lt;/b&gt;' });
      const el: HTMLElement = fixture.nativeElement;
      // Should show the literal entity string, not parsed HTML
      expect(el.textContent).toContain('&lt;b&gt;');
    });
  });

  // ---- XSS corpus: authorAcct ----

  describe('authorAcct XSS corpus', () => {
    it('renders <script> in acct as literal text', () => {
      setToot({ authorAcct: '<script>evil()</script>@example.com' });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('script')).toBeNull();
      expect(el.textContent).toContain('<script>');
    });
  });

  // ---- XSS corpus: textContent ----

  describe('textContent XSS corpus', () => {
    it('renders <script> in text as literal text', () => {
      setToot({ textContent: '<script>alert("xss")</script>Hello' });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('script')).toBeNull();
    });

    it('renders MutationXSS vector as literal text', () => {
      setToot({
        textContent: '<noscript><p title="</noscript><img src=x onerror=alert(1)>">',
      });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('img[onerror]')).toBeNull();
    });

    it('renders nested entities in text as literal text', () => {
      setToot({ textContent: '&#x6A;&#x61;&#x76;&#x61;&#x73;&#x63;&#x72;&#x69;&#x70;&#x74;&#x3A;0' });
      const el: HTMLElement = fixture.nativeElement;
      // No script should execute
      expect(el.querySelector('script')).toBeNull();
    });
  });

  // ---- XSS corpus: link URLs ----

  describe('link URL XSS corpus', () => {
    function setLinks(links: LinkRef[]): void {
      setToot({ links });
    }

    it('renders javascript: link WITHOUT href (safeUrlPipe returns null)', () => {
      setLinks([{ url: 'javascript:alert(1)', displayText: 'Click me' }]);
      const anchors: NodeListOf<HTMLAnchorElement> = fixture.nativeElement.querySelectorAll('a[href]');
      // No anchor should have a javascript: href
      Array.from(anchors).forEach((a) => {
        expect(a.href).not.toContain('javascript:');
      });
    });

    it('renders data: URI link WITHOUT href', () => {
      setLinks([{ url: 'data:text/html,<script>alert(1)</script>', displayText: 'data' }]);
      const anchors: NodeListOf<HTMLAnchorElement> = fixture.nativeElement.querySelectorAll('a');
      Array.from(anchors).forEach((a) => {
        expect(a.getAttribute('href') ?? '').not.toContain('data:');
      });
    });

    it('renders https: link with href intact', () => {
      setLinks([{ url: 'https://mastodon.social/@alice/123', displayText: 'View' }]);
      const anchor: HTMLAnchorElement | null = fixture.nativeElement.querySelector(
        'a[href="https://mastodon.social/@alice/123"]'
      );
      expect(anchor).not.toBeNull();
    });

    it('renders external links with target=_blank and rel=noopener noreferrer', () => {
      setLinks([{ url: 'https://mastodon.social/@alice/123', displayText: 'View' }]);
      const anchor: HTMLAnchorElement | null = fixture.nativeElement.querySelector('a[target="_blank"]');
      expect(anchor).not.toBeNull();
      expect(anchor?.rel).toContain('noopener');
      expect(anchor?.rel).toContain('noreferrer');
    });
  });

  // ---- bidiStripped badge ----

  describe('bidiStripped badge', () => {
    it('does NOT show bidi badge when bidiStripped=false', () => {
      setToot({ bidiStripped: false });
      const badge = fixture.nativeElement.querySelector('[data-testid="bidi-stripped-badge"]');
      expect(badge).toBeNull();
    });

    it('shows bidi badge when bidiStripped=true', () => {
      setToot({ bidiStripped: true });
      const badge = fixture.nativeElement.querySelector('[data-testid="bidi-stripped-badge"]');
      expect(badge).not.toBeNull();
    });

    it('bidi badge has an accessible label', () => {
      setToot({ bidiStripped: true });
      const badge = fixture.nativeElement.querySelector('[data-testid="bidi-stripped-badge"]');
      const hasLabel =
        badge?.hasAttribute('aria-label') ||
        badge?.hasAttribute('title') ||
        badge?.textContent?.trim().length > 0;
      expect(hasLabel).toBeTrue();
    });
  });

  // ---- Content warning ----

  describe('Content Warning (CW)', () => {
    it('renders CW toggle when spoilerText is non-empty', () => {
      setToot({ spoilerText: 'Content warning: sensitive topic' });
      const toggle = fixture.nativeElement.querySelector('[data-testid="cw-toggle"]');
      expect(toggle).not.toBeNull();
    });

    it('does NOT render CW toggle when spoilerText is empty', () => {
      setToot({ spoilerText: '' });
      const toggle = fixture.nativeElement.querySelector('[data-testid="cw-toggle"]');
      expect(toggle).toBeNull();
    });

    it('CW spoiler text is rendered as literal text, not HTML', () => {
      setToot({ spoilerText: '<img src=x onerror=alert(1)>' });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('img[onerror]')).toBeNull();
    });
  });

  // ---- dir=auto (bidi defence) ----

  it('sets dir=auto on text content container for RTL/LTR detection', () => {
    const textContainer = fixture.nativeElement.querySelector('[data-testid="toot-text"]');
    expect(textContainer?.getAttribute('dir')).toBe('auto');
  });
});
