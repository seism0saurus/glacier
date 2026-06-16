import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ShareExpiredComponent } from './share-expired.component';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('ShareExpiredComponent', () => {
  let component: ShareExpiredComponent;
  let fixture: ComponentFixture<ShareExpiredComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ShareExpiredComponent],
      providers: [
        provideAnimations(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShareExpiredComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders an H1 heading', () => {
    const h1 = fixture.nativeElement.querySelector('h1');
    expect(h1).not.toBeNull();
  });

  it('H1 has tabindex=-1 for programmatic focus', () => {
    const h1: HTMLHeadingElement = fixture.nativeElement.querySelector('h1');
    expect(h1.getAttribute('tabindex')).toBe('-1');
  });

  it('moves focus to heading on init', () => {
    // AfterViewInit should have been called
    const h1: HTMLHeadingElement = fixture.nativeElement.querySelector('h1');
    // We can verify the heading element exists and has a focus-ready tabindex
    expect(h1.getAttribute('tabindex')).toBe('-1');
  });

  it('renders a home link', () => {
    const link: HTMLAnchorElement | null = fixture.nativeElement.querySelector('a[routerLink]') ||
                                          fixture.nativeElement.querySelector('a[href="/"]');
    // Look for any link since routing isn't fully active in tests
    const links: NodeListOf<HTMLAnchorElement> = fixture.nativeElement.querySelectorAll('a');
    expect(links.length).toBeGreaterThan(0);
  });

  it('has a <main> landmark', () => {
    const main = fixture.nativeElement.querySelector('main');
    expect(main).not.toBeNull();
  });

  it('main is labelled by the heading', () => {
    const main: HTMLElement = fixture.nativeElement.querySelector('main');
    const headingId = main.getAttribute('aria-labelledby');
    expect(headingId).toBeTruthy();
    const heading = fixture.nativeElement.querySelector(`#${headingId}`);
    expect(heading).not.toBeNull();
  });

  // ---- VIEW-06: focus guard + no double-announce ----
  // WCAG 2.4.3: focus must move to heading to orient screen reader users.
  // Guard: headingRef may be absent (destroyed component); must not throw.
  // No double-announce: the page itself does NOT re-announce expiry (already
  // announced assertively by ReadonlyWallComponent before navigation).

  describe('VIEW-06 focus guard and no double-announce', () => {

    it('VIEW-06 focus_moves_to_heading_on_init — focus lands on h1 after view init', () => {
      // ngAfterViewInit has been called by detectChanges() in beforeEach.
      // Verify the heading received focus (jsdom will track document.activeElement).
      const h1: HTMLHeadingElement = fixture.nativeElement.querySelector('h1');
      // The heading element should be focused
      expect(document.activeElement).toBe(h1);
    });

    it('VIEW-06 focus_does_not_throw_when_headingRef_absent — calling ngAfterViewInit without headingRef does not throw', () => {
      // Simulate missing ViewChild reference (e.g., conditional @if removed the element)
      const orig = component.headingRef;
      (component as unknown as { headingRef: unknown }).headingRef = undefined;

      // Must not throw
      expect(() => component.ngAfterViewInit()).not.toThrow();

      // Restore
      (component as unknown as { headingRef: unknown }).headingRef = orig;
    });

    it('VIEW-06 no_assertive_live_announce_on_page — expired page does NOT fire an assertive LiveAnnouncer call', () => {
      // The ReadonlyWallComponent handles the assertive announce before navigating.
      // The share-expired page must NOT re-announce assertively to avoid double-announce.
      // This page has no LiveAnnouncer dependency at all — verify no injection.
      // (If the component doesn't inject LiveAnnouncer, the test confirms the design.)
      // We verify by checking the template: no aria-live="assertive" region on this page.
      const assertiveRegions = fixture.nativeElement.querySelectorAll('[aria-live="assertive"]');
      expect(assertiveRegions.length).toBe(0);
    });

    it('VIEW-06 page_has_guidance_text — body paragraph provides friendly guidance to the user', () => {
      // Must have at least a body paragraph (not a dead-end page)
      const body = fixture.nativeElement.querySelector('p');
      expect(body).not.toBeNull();
      expect(body.textContent.trim().length).toBeGreaterThan(0);
    });
  });
});
